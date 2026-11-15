package com.clashofmusic.app.data.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.repository.FavoritesRepository
import com.clashofmusic.app.domain.repository.LibraryRepository
import com.clashofmusic.app.services.MusicPlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlayerManager(
    private val context: Context,
    private val favoritesRepository: FavoritesRepository,
    private val libraryRepository: LibraryRepository,
    val radioCache: RadioAudioCache? = null,
    private val radioRecommender: RadioRecommender? = null,
    private val managePlaybackService: Boolean = true
) {
    companion object { private const val TAG = "MusicPlayerManager" }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val queue = PlaybackQueue()
    val queueState = queue.state
    private val endPolicy = PlaybackEndPolicy()
    private var exoPlayer: ExoPlayer? = null
    val player: ExoPlayer? get() = exoPlayer
    private var editingTimeline = false
    private var progressJob: Job? = null
    private var favoriteJob: Job? = null
    private var autoplayJob: Job? = null
    internal val pendingAutoplay: Job? get() = autoplayJob?.takeIf { it.isActive }
    private var sleepTimerJob: Job? = null
    private val failedEntries = mutableSetOf<String>()
    private val _radioState = MutableStateFlow(RadioState())
    val radioState = _radioState.asStateFlow()
    val radioMixTrace: RadioMixTrace? get() = radioRecommender?.lastTrace
    private var radioJob: Job? = null
    private var radioSeed: DownloadedSong? = null
    private val radioHistory = ArrayDeque<DownloadedSong>()
    private var radioGeneration = 0L
    private var radioAdvanceRequested = false

    private val _currentSong = MutableStateFlow<DownloadedSong?>(null)
    val currentSong = _currentSong.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite = _isFavorite.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode = _repeatMode.asStateFlow()
    private val _sleepTimerMode = MutableStateFlow(SleepTimerMode.OFF)
    val sleepTimerMode = _sleepTimerMode.asStateFlow()
    private val _sleepTimerRemainingSeconds = MutableStateFlow(0L)
    val sleepTimerRemainingSeconds = _sleepTimerRemainingSeconds.asStateFlow()

    init { initPlayer() }

    fun initPlayer() {
        if (exoPlayer != null) return
        val builder = ExoPlayer.Builder(context)
        radioCache?.let { builder.setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(it.dataSourceFactory)) }
        exoPlayer = builder.build().apply {
            repeatMode = _repeatMode.value
            setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                    updateProgress()
                    if (playing) startProgressUpdates() else progressJob?.cancel()
                }

                override fun onRepeatModeChanged(repeatMode: Int) { _repeatMode.value = repeatMode }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) radioAdvanceRequested = false
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                        finishTrackSleepTimer()
                    }
                    updateProgress()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateProgress()
                    if (editingTimeline || playbackState != Player.STATE_ENDED) return
                    if (finishTrackSleepTimer() || endPolicy.autoplayBlocked) return
                    if (queue.value.current != null && _repeatMode.value == Player.REPEAT_MODE_OFF) startAutoplayFromLibrary()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (editingTimeline) return
                    // Defensive fallback for a boundary reported before pause-at-end's callback.
                    if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                        && endPolicy.endOfTrackArmed) {
                        val previous = queue.value.currentEntryId
                        finishTrackSleepTimer()
                        if (previous != null) {
                            val index = queue.value.entries.indexOfFirst { it.id == previous }
                            if (index >= 0) exoPlayer?.seekTo(index, _durationMs.value)
                        }
                        return
                    }
                    syncCurrentFromPlayer()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "Player error: ${error.message}", error)
                    queue.value.currentEntryId?.let { failedEntries.add(it) }
                    val next = queue.value.upcoming.firstOrNull { it.id !in failedEntries }
                    if (_radioState.value.active) {
                        _radioState.value = _radioState.value.copy(message = "No se pudo reproducir esta pista. Comprueba la conexión o pasa a la siguiente.")
                        // Never race through the entire queue automatically on a connection failure.
                        pause()
                    } else if (next != null && !endPolicy.autoplayBlocked) playQueueEntry(next.id) else pause()
                }
            })
        }
    }

    private fun mediaItem(entry: PlaybackQueueEntry): MediaItem {
        val song = entry.song
        val file = File(song.filePath)
        val uri = if (song.filePath.isNotBlank() && file.isFile && file.length() > 0L) Uri.fromFile(file)
            else Uri.parse(song.fileUri)
        val artwork = song.thumbnailUrl?.let {
            if (it.startsWith("/") || File(it).isAbsolute) Uri.fromFile(File(it)) else Uri.parse(it)
        }
        return MediaItem.Builder().setMediaId(entry.id).setUri(uri)
            .setCustomCacheKey(if (song.format == "RADIO") song.songId else null).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(if (entry.origin == QueueEntryOrigin.PREVIEW) "[Vista Previa] ${song.title}" else song.title)
                .setArtist(song.artist).setAlbumTitle(song.album).setArtworkUri(artwork)
                .setExtras(Bundle().apply { putString("clashSongId", song.songId); putString("clashQueueEntryId", entry.id) })
                .build()
        ).build()
    }

    /** Reconcile by occurrence ID without restarting the current audio or losing its position. */
    private fun syncTimeline(replace: Boolean = false, targetEntryId: String? = null) {
        val player = exoPlayer ?: return
        val desired = queue.value.entries
        editingTimeline = true
        try {
            if (replace) {
                player.setMediaItems(desired.map(::mediaItem), queue.value.currentIndex.coerceAtLeast(0), 0L)
                player.prepare()
            } else {
                desired.forEachIndexed { index, entry ->
                    if (index < player.mediaItemCount && player.getMediaItemAt(index).mediaId == entry.id) return@forEachIndexed
                    val existing = (index until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == entry.id }
                    if (existing != null) player.moveMediaItem(existing, index) else player.addMediaItem(index, mediaItem(entry))
                }
                while (player.mediaItemCount > desired.size) player.removeMediaItem(player.mediaItemCount - 1)
                if (targetEntryId != null) {
                    val index = desired.indexOfFirst { it.id == targetEntryId }
                    if (index >= 0) player.seekTo(index, 0L)
                }
            }
        } finally { editingTimeline = false }
        syncCurrentFromPlayer()
        check(player.mediaItemCount == queue.value.entries.size)
        check(queue.value.entries.indices.all { player.getMediaItemAt(it).mediaId == queue.value.entries[it].id })
    }

    private fun syncCurrentFromPlayer() {
        val entryId = exoPlayer?.currentMediaItem?.mediaId ?: return
        if (!queue.select(entryId)) return
        val song = queue.value.current?.song
        if (_currentSong.value != song) {
            _currentSong.value = song
            _isFavorite.value = false
            favoriteJob?.cancel()
            if (song != null) favoriteJob = scope.launch {
                favoritesRepository.isFavorite(song.songId).collect { _isFavorite.value = it }
            }
        }
        val player = exoPlayer
        if (player != null && player.mediaItemCount == queue.value.entries.size &&
            queue.value.entries.indices.any { player.getMediaItemAt(it).mediaId != queue.value.entries[it].id }) {
            syncTimeline()
        }
        updateProgress()
        if (_radioState.value.active) updateRadioWindow()
    }

    fun playSong(song: DownloadedSong, newPlaylist: List<DownloadedSong> = emptyList()) {
        endRadio()
        initPlayer()
        cancelAutoplay()
        endPolicy.userPlayback()
        failedEntries.clear()
        val songs = newPlaylist.ifEmpty { listOf(song) }
        val target = songs.indexOfFirst { it.id == song.id || it.songId == song.songId }.coerceAtLeast(0)
        queue.load(songs, target)
        syncTimeline(replace = true)
        resume()
    }

    fun refreshSongMetadata(song: DownloadedSong) {
        val previous = queue.value.entries
        queue.updateSongMetadata(song)
        val player = exoPlayer ?: return
        editingTimeline = true
        try {
            queue.value.entries.forEachIndexed { index, entry ->
                if (entry.song != previous.getOrNull(index)?.song && index < player.mediaItemCount) {
                    // The URI and occurrence ID stay unchanged; replace only presentation metadata.
                    player.replaceMediaItem(index, player.getMediaItemAt(index).buildUpon()
                        .setMediaMetadata(mediaItem(entry).mediaMetadata).build())
                }
            }
        } finally { editingTimeline = false }
        syncCurrentFromPlayer()
    }

    fun playQueueEntry(entryId: String) {
        val index = queue.value.entries.indexOfFirst { it.id == entryId }
        if (index < 0) return
        cancelAutoplay()
        endPolicy.userPlayback()
        exoPlayer?.seekTo(index, 0L)
        syncCurrentFromPlayer()
        resume()
    }

    fun playTrackAtIndex(index: Int) { queue.value.entries.getOrNull(index)?.let { playQueueEntry(it.id) } }

    fun playNextInQueue(song: DownloadedSong) = enqueue(song, true)
    fun addToQueue(song: DownloadedSong) = enqueue(song, false)
    private fun enqueue(song: DownloadedSong, next: Boolean) {
        if (queue.value.current == null) { playSong(song); return }
        cancelAutoplay()
        queue.enqueue(song, next)
        syncTimeline()
    }

    fun clearQueue() { cancelAutoplay(); queue.clearManualQueue(); syncTimeline() }
    fun removeFromQueue(entryId: String) { cancelAutoplay(); queue.removeQueuedEntry(entryId); syncTimeline() }

    fun playPreviewUrl(directStreamUrl: String, song: DownloadedSong) {
        endRadio()
        initPlayer()
        cancelAutoplay()
        endPolicy.userPlayback()
        val entryId = queue.preview(song.copy(id = song.songId, filePath = "", fileUri = directStreamUrl, format = "PREVIEW"))
        syncTimeline(targetEntryId = entryId)
        exoPlayer?.prepare()
        resume()
    }

    fun playPreviewSong(song: DownloadedSong) {
        val url = if (song.filePath.isNotBlank()) Uri.fromFile(File(song.filePath)).toString() else song.fileUri
        playPreviewUrl(url, song)
    }

    fun togglePlayPause() {
        if (exoPlayer?.playWhenReady == true) pause() else resume()
    }

    fun resume(startService: Boolean = true) {
        val player = exoPlayer ?: return
        if (queue.value.current == null) return
        cancelAutoplay()
        endPolicy.userPlayback()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (startService && managePlaybackService) MusicPlaybackService.start(context)
        player.play()
    }

    fun pause() {
        radioAdvanceRequested = false
        cancelAutoplay()
        exoPlayer?.pause()
        _isPlaying.value = false
        updateProgress()
    }

    fun playNext() {
        val player = exoPlayer ?: return
        if (queue.value.current == null) return
        cancelAutoplay()
        endPolicy.userPlayback()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            syncCurrentFromPlayer()
            resume()
        } else {
            if (_radioState.value.active) {
                player.play()
                if (managePlaybackService) MusicPlaybackService.start(context)
            }
            startAutoplayFromLibrary(startService = true)
        }
    }

    fun playPrevious() {
        val player = exoPlayer ?: return
        cancelAutoplay()
        endPolicy.userPlayback()
        if (player.currentPosition > 3000L || !player.hasPreviousMediaItem()) player.seekTo(0L)
        else player.seekToPreviousMediaItem()
        syncCurrentFromPlayer()
        resume()
    }

    fun seekTo(positionMs: Long) {
        cancelAutoplay()
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        updateProgress()
    }
    fun seekRelative(offsetMs: Long) = seekTo(((_positionMs.value + offsetMs).coerceIn(0L, _durationMs.value)))

    fun toggleShuffle() = setShuffleEnabled(!queue.value.shuffleEnabled)
    fun setShuffleEnabled(enabled: Boolean) {
        cancelAutoplay()
        queue.setShuffle(enabled)
        syncTimeline()
    }

    fun toggleRepeatMode() = setRepeatMode(when (_repeatMode.value) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    })
    fun setRepeatMode(mode: Int) {
        require(mode in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL)
        exoPlayer?.repeatMode = mode
        _repeatMode.value = mode
    }

    fun toggleFavoriteCurrent() {
        val song = _currentSong.value ?: return
        scope.launch { favoritesRepository.toggleFavoriteFromDownloaded(song) }
    }

    private fun startAutoplayFromLibrary(startService: Boolean = false) {
        if (_radioState.value.active) { fillRadio(advanceWhenReady = true); return }
        if (autoplayJob?.isActive == true || endPolicy.autoplayBlocked) return
        val requested = queue.value
        if (requested.current == null) return
        autoplayJob = scope.launch {
            try {
                val allSongs = withTimeoutOrNull(5000L) { libraryRepository.observeDownloadedSongs().first() }
                    ?: return@launch
                ensureActive()
                if (queue.value.revision != requested.revision || endPolicy.autoplayBlocked) return@launch
                val used = requested.entries.map { it.song.songId }.toSet()
                val candidates = allSongs.filterNot { it.songId in used }.ifEmpty { allSongs }
                val additions = candidates.shuffled().take(20)
                if (additions.isEmpty()) return@launch
                queue.append(additions)
                syncTimeline()
                exoPlayer?.seekTo(requested.currentIndex + 1, 0L)
                exoPlayer?.prepare()
                syncCurrentFromPlayer()
                // Do not call resume(): it would cancel this autoplay request itself.
                exoPlayer?.play()
                if (startService && managePlaybackService) MusicPlaybackService.start(context)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Autoplay error: ${e.message}") }
        }
    }

    private fun cancelAutoplay() { autoplayJob?.cancel(); autoplayJob = null }

    fun startRadioFromCurrent() { _currentSong.value?.let(::startRadio) }

    fun startRadio(song: DownloadedSong) {
        if (radioCache == null || radioRecommender == null) return
        clearPlayback(stopService = false)
        initPlayer()
        val seed = if (song.filePath.isNotBlank() || song.fileUri.startsWith("content://")) song
            else song.copy(id = "radio_${song.songId}", filePath = "", fileUri = "clashradio://track/${song.songId}", format = "RADIO", quality = "Original", fileSizeBytes = 0)
        radioSeed = seed
        radioHistory.clear()
        radioGeneration++
        _radioState.value = RadioState(active = true)
        setRepeatMode(Player.REPEAT_MODE_OFF)
        queue.loadRadio(seed)
        exoPlayer?.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        radioCache.updateWindow(if (seed.format == "RADIO") listOf(seed.songId) else emptyList(), emptyList())
        syncTimeline(replace = true)
        resume()
    }

    private fun updateRadioWindow() {
        val current = queue.value.current?.song ?: return
        if (radioHistory.lastOrNull()?.songId != current.songId) {
            radioHistory.addLast(current)
            while (radioHistory.size > 200) radioHistory.removeFirst()
        }
        // Keep both the actual player timeline and disk window bounded.
        if (queue.value.currentIndex > RadioWindow.BEHIND) {
            queue.trimRadioHistory()
            syncTimeline()
            return
        }
        val snapshot = queue.value
        val window = RadioWindow.around(snapshot.entries, snapshot.currentIndex).filter { it.song.format == "RADIO" }.map { it.song.songId }
        radioCache?.updateWindow(window, snapshot.upcoming.take(RadioWindow.AHEAD).filter { it.song.format == "RADIO" }.map { it.song.songId })
        if (snapshot.upcoming.size < RadioWindow.AHEAD && !endPolicy.autoplayBlocked) fillRadio()
    }

    private fun fillRadio(advanceWhenReady: Boolean = false) {
        if (advanceWhenReady) radioAdvanceRequested = true
        if (!_radioState.value.active || radioJob?.isActive == true || endPolicy.autoplayBlocked) return
        val seed = radioSeed ?: return
        val current = queue.value.current ?: return
        val generation = radioGeneration
        val needed = (RadioWindow.AHEAD - queue.value.upcoming.size).coerceAtLeast(0)
        if (needed == 0) return
        _radioState.value = _radioState.value.copy(loading = true, message = null)
        radioJob = scope.launch {
            try {
                val history = (radioHistory.toList() + queue.value.entries.map { it.song }).distinctBy { it.songId }
                val additions = withContext(Dispatchers.IO) { radioRecommender!!.next(seed, history, needed) }
                ensureActive()
                if (generation != radioGeneration || !_radioState.value.active) return@launch
                val used = queue.value.entries.map { it.song.songId }.toSet()
                val currentBeforeAppend = queue.value.currentEntryId
                val shouldAdvance = (radioAdvanceRequested || exoPlayer?.playbackState == Player.STATE_ENDED) && exoPlayer?.playWhenReady == true
                radioAdvanceRequested = false
                queue.appendRadio(additions.filterNot { it.songId in used }.take((RadioWindow.AHEAD - queue.value.upcoming.size).coerceAtLeast(0)))
                syncTimeline()
                if (shouldAdvance && queue.value.currentEntryId == currentBeforeAppend && queue.value.upcoming.isNotEmpty()) {
                    exoPlayer?.seekToNextMediaItem()
                    exoPlayer?.prepare()
                    syncCurrentFromPlayer()
                    exoPlayer?.play()
                }
                _radioState.value = _radioState.value.copy(message = if (additions.isEmpty()) radioRecommender?.lastTrace?.message ?: "No hay más pistas nuevas en este mix ahora." else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _radioState.value = _radioState.value.copy(message = "No se pudo ampliar la radio. Comprueba la conexión y reintenta.") }
            finally { if (generation == radioGeneration) _radioState.value = _radioState.value.copy(loading = false) }
        }
    }

    fun retryRadio() {
        if (!_radioState.value.active) return
        _radioState.value = _radioState.value.copy(message = null)
        radioRecommender?.clear()
        _currentSong.value?.let { radioCache?.refreshSource(it.songId) }
        if (exoPlayer?.playerError != null) { exoPlayer?.prepare(); resume() }
        fillRadio(exoPlayer?.playbackState == Player.STATE_ENDED)
    }

    private fun endRadio() {
        radioGeneration++
        radioAdvanceRequested = false
        radioJob?.cancel(); radioJob = null
        radioSeed = null
        radioRecommender?.clear()
        radioHistory.clear()
        _radioState.value = RadioState()
        radioCache?.clear()
        exoPlayer?.setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
    }

    fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimerJob?.cancel()
        _sleepTimerMode.value = mode
        _sleepTimerRemainingSeconds.value = 0L
        endPolicy.armEndOfTrack(mode == SleepTimerMode.END_OF_TRACK)
        exoPlayer?.pauseAtEndOfMediaItems = mode == SleepTimerMode.END_OF_TRACK
        if (mode.minutes <= 0) return
        _sleepTimerRemainingSeconds.value = mode.minutes * 60L
        sleepTimerJob = scope.launch {
            while (_sleepTimerRemainingSeconds.value > 0) {
                delay(1000)
                _sleepTimerRemainingSeconds.value--
            }
            endPolicy.timedSleep()
            pause()
            _sleepTimerMode.value = SleepTimerMode.OFF
        }
    }

    private fun finishTrackSleepTimer(): Boolean {
        if (!endPolicy.consumeTrackBoundary()) return false
        cancelAutoplay()
        exoPlayer?.pause()
        _sleepTimerMode.value = SleepTimerMode.OFF
        _sleepTimerRemainingSeconds.value = 0L
        exoPlayer?.pauseAtEndOfMediaItems = false
        return true
    }

    private fun updateProgress() {
        exoPlayer?.let {
            _positionMs.value = it.currentPosition.coerceAtLeast(0L)
            _durationMs.value = it.duration.coerceAtLeast(0L)
        }
    }
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch { while (isActive) { updateProgress(); delay(500) } }
    }

    fun stop() = clearPlayback(stopService = true)
    fun stopFromService() = clearPlayback(stopService = false)
    private fun clearPlayback(stopService: Boolean) {
        endRadio()
        cancelAutoplay()
        progressJob?.cancel()
        favoriteJob?.cancel()
        sleepTimerJob?.cancel()
        endPolicy.reset()
        editingTimeline = true
        try { exoPlayer?.pause(); exoPlayer?.stop(); exoPlayer?.clearMediaItems() }
        finally { editingTimeline = false }
        queue.clear()
        failedEntries.clear()
        _currentSong.value = null
        _isPlaying.value = false
        _isFavorite.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        _sleepTimerMode.value = SleepTimerMode.OFF
        _sleepTimerRemainingSeconds.value = 0L
        exoPlayer?.pauseAtEndOfMediaItems = false
        if (stopService && managePlaybackService) MusicPlaybackService.stop(context)
    }

    fun release() { stop(); exoPlayer?.release(); exoPlayer = null }
}
