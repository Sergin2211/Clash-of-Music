package com.clashofmusic.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.usecase.ResolveAlbumTrack
import com.clashofmusic.app.domain.usecase.TrackMatchRequest
import com.clashofmusic.app.domain.usecase.CatalogTrackIdentity
import com.clashofmusic.app.domain.usecase.CollectionDownloadState
import com.clashofmusic.app.domain.usecase.TrackMatchResult
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<SongItem> = emptyList(),
    val albumResults: List<AlbumItem> = emptyList(),
    val searchHistory: List<SearchHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    // Filter: 0 = Todos, 1 = Canciones, 2 = Álbumes
    val searchFilter: Int = 0,
    // Selected album for detail inspection
    val selectedAlbum: AlbumItem? = null,
    val albumTracks: List<AlbumTrackItem> = emptyList(),
    val isLoadingAlbumTracks: Boolean = false,
    val isQueueingAlbum: Boolean = false,
    val albumDownloadState: CollectionDownloadState = CollectionDownloadState(),
    val albumDownloadMessage: String? = null,
    val albumTrackMessages: Map<Int, String> = emptyMap(),
    val resolvingAlbumTracks: Set<Int> = emptySet(),
    // Link Extraction State
    val linkInput: String = "",
    val isExtractingLink: Boolean = false,
    val isQueueingLink: Boolean = false,
    val linkDownloadState: CollectionDownloadState = CollectionDownloadState(),
    val extractedPlaylist: RemotePlaylistInfo? = null,
    val linkErrorMessage: String? = null,
    val linkSuccessMessage: String? = null,
    val selectedLinkFormat: AudioFormat = AudioFormat.MP3,
    val selectedAlbumFormat: AudioFormat = AudioFormat.MP3,
    // Selected tab: 0 = Buscador, 1 = Descargar por Link
    val selectedTab: Int = 0,
    val downloadingSongIds: Set<String> = emptySet(),
    val downloadedSongIds: Set<String> = emptySet()
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val musicRepository: com.clashofmusic.app.domain.repository.MusicRepository = ServiceLocator.musicRepository,
    private val historyRepository: com.clashofmusic.app.domain.repository.HistoryRepository = ServiceLocator.historyRepository,
    private val downloadRepository: com.clashofmusic.app.domain.repository.DownloadRepository = ServiceLocator.downloadRepository,
    private val libraryRepository: com.clashofmusic.app.domain.repository.LibraryRepository = ServiceLocator.libraryRepository,
    private val playlistRepository: com.clashofmusic.app.domain.repository.PlaylistRepository = ServiceLocator.playlistRepository,
    private val preferencesRepository: com.clashofmusic.app.domain.repository.PreferencesRepository = ServiceLocator.preferencesRepository,
    private val progressDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val trackResolver = ResolveAlbumTrack(musicRepository, progressDispatcher)
    val playerManager get() = ServiceLocator.playerManager
    private var linkFormatOverride: AudioFormat? = null
    private var albumFormatOverride: AudioFormat? = null
    private var librarySongs: List<DownloadedSong> = emptyList()
    private var tasks: List<DownloadTask> = emptyList()
    private val resolvedSources = mutableMapOf<String, SongItem>()
    private var availabilityJob: Job? = null

    private fun identity(track: AlbumTrackItem, album: AlbumItem) =
        CatalogTrackIdentity(track.title, track.artist, album.title, track.durationSeconds)
    private fun identity(track: RemoteTrackItem) =
        CatalogTrackIdentity(track.title, track.artist, track.album, track.durationSeconds)

    private fun knownSource(track: CatalogTrackIdentity, songs: List<DownloadedSong>, tasks: List<DownloadTask>, known: Map<String, SongItem>): SongItem? {
        val saved = track.find(songs)
        val task = tasks.firstOrNull { track.matches(it.title, it.artist, it.album) && it.songId == saved?.songId }
            ?: tasks.firstOrNull { track.matches(it.title, it.artist, it.album) }
        if (saved != null && Regex("[A-Za-z0-9_-]{11}").matches(saved.songId))
            return SongItem(saved.songId, saved.title, saved.artist, saved.thumbnailUrl, saved.durationSeconds,
                "https://www.youtube.com/watch?v=${saved.songId}")
        if (task != null) return SongItem(task.songId, task.title, task.artist, task.thumbnailUrl, 0, task.url)
        return known[track.key]
    }

    private suspend fun resolveSource(track: CatalogTrackIdentity): TrackMatchResult {
        val songs = librarySongs
        val taskSnapshot = tasks
        val known = resolvedSources.toMap()
        val (source, excluded) = withContext(progressDispatcher) {
            knownSource(track, songs, taskSnapshot, known) to (
                taskSnapshot.filterNot { track.matches(it.title, it.artist, it.album) }.map { it.songId }.toSet() +
                songs.filterNot { track.matches(it.title, it.artist, it.album) }.map { it.songId }.toSet() +
                known.filterKeys { it != track.key }.values.map { it.id })
        }
        source?.let { return TrackMatchResult(song = it) }
        val result = trackResolver.resolve(TrackMatchRequest(track.title, track.artist, track.duration), excluded)
        result.song?.let { resolvedSources[track.key] = it }
        return result
    }

    /** File access is off the UI thread, and progress ticks do not trigger rescans. */
    fun refreshDownloadAvailability() {
        availabilityJob?.cancel()
        val snapshot = _uiState.value
        val songs = librarySongs
        val taskSnapshot = tasks
        val known = resolvedSources.toMap()
        availabilityJob = viewModelScope.launch {
            suspend fun inspect(tracks: List<CatalogTrackIdentity>, format: AudioFormat): CollectionDownloadState =
                withContext(progressDispatcher) {
                    var complete = 0
                    var active = false
                    for (track in tracks) {
                        ensureActive()
                        val saved = track.find(songs)
                        val sourceId = saved?.songId ?: known[track.key]?.id
                        val task = taskSnapshot.firstOrNull { it.isActive &&
                            (it.songId == sourceId || track.matches(it.title, it.artist, it.album)) }
                        if (task != null) active = true
                        if (sourceId != null && libraryRepository.checkDownloadEligibility(sourceId, format, AudioQuality.BEST_320K) ==
                            DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY) complete++
                    }
                    CollectionDownloadState(checking = false, complete = tracks.isNotEmpty() && complete == tracks.size,
                        active = active, missing = tracks.size - complete)
                }
            val albumStatus = inspect(snapshot.albumTracks.map { identity(it, snapshot.selectedAlbum!!) }, snapshot.selectedAlbumFormat)
            val linkStatus = inspect(snapshot.extractedPlaylist?.tracks.orEmpty().map(::identity), snapshot.selectedLinkFormat)
            _uiState.update { current -> current.copy(
                albumDownloadState = if (current.selectedAlbum == snapshot.selectedAlbum && current.selectedAlbumFormat == snapshot.selectedAlbumFormat) albumStatus else current.albumDownloadState,
                linkDownloadState = if (current.extractedPlaylist == snapshot.extractedPlaylist && current.selectedLinkFormat == snapshot.selectedLinkFormat) linkStatus else current.linkDownloadState
            ) }
        }
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var searchJob: Job? = null
    private var linkExtractionJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesRepository.observeSettings().collect { settings ->
                _uiState.update { it.copy(
                    selectedLinkFormat = linkFormatOverride ?: settings.defaultFormat,
                    selectedAlbumFormat = albumFormatOverride ?: settings.defaultFormat
                ) }
                refreshDownloadAvailability()
            }
        }
        // Observe search history from Room
        viewModelScope.launch {
            historyRepository.observeRecentHistory().collect { history ->
                _uiState.update { it.copy(searchHistory = history) }
            }
        }

        // Observe downloading tasks from DownloadRepository
        viewModelScope.launch {
            downloadRepository.observeTasks().map { list -> list.map { it.copy(progress = 0f, downloadSpeed = null, etaSeconds = 0, statusText = "") } }
                .distinctUntilChanged().flowOn(progressDispatcher).collect { list ->
                tasks = list
                _uiState.update { it.copy(downloadingSongIds = list.filter { task -> task.isActive }.map { task -> task.songId }.toSet()) }
                refreshDownloadAvailability()
            }
        }

        // Observe downloaded songs from LibraryRepository
        viewModelScope.launch {
            libraryRepository.observeDownloadedSongs().collect { songs ->
                librarySongs = songs
                val downloadedIds = songs.map { it.songId }.toSet() + songs.map { it.id }.toSet()
                _uiState.update { it.copy(downloadedSongIds = downloadedIds) }
                refreshDownloadAvailability()
            }
        }

        // Live search with 300ms debounce
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.trim().length >= 2) {
                        performSearch(q.trim())
                    } else if (q.isBlank()) {
                        _uiState.update {
                            it.copy(
                                searchResults = emptyList(),
                                albumResults = emptyList(),
                                isSearching = false,
                                errorMessage = null
                            )
                        }
                    }
                }
        }
    }

    fun onTabSelected(tabIndex: Int) {
        _uiState.update { it.copy(selectedTab = tabIndex) }
    }

    fun onSearchFilterChanged(filter: Int) {
        _uiState.update { it.copy(searchFilter = filter) }
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        queryFlow.value = newQuery
    }

    fun onLinkFormatSelected(format: com.clashofmusic.app.domain.model.AudioFormat) {
        linkFormatOverride = format
        _uiState.update { it.copy(selectedLinkFormat = format, linkDownloadState = CollectionDownloadState()) }
        refreshDownloadAvailability()
    }


    fun onLinkInputChanged(newLink: String) {
        linkExtractionJob?.cancel()
        _uiState.update {
            it.copy(linkInput = newLink, extractedPlaylist = null, isExtractingLink = false,
                linkErrorMessage = null, linkSuccessMessage = null)
        }
        // Auto-extract if valid url
        if (musicRepository.isSupportedPlaylistUrl(newLink)) {
            extractLink(newLink)
        }
    }

    fun onHistoryItemClicked(query: String) {
        _uiState.update { it.copy(query = query) }
        queryFlow.value = query
        performSearch(query)
    }

    fun deleteHistoryItem(query: String) {
        viewModelScope.launch {
            historyRepository.deleteSearchQuery(query)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearSearchHistory()
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }

            val songsDeferred = async { musicRepository.search(query) }
            val albumsDeferred = async { musicRepository.searchAlbums(query) }

            val songsResult = songsDeferred.await()
            val albumsResult = albumsDeferred.await()

            val songs: List<SongItem> = songsResult.getOrDefault(emptyList())
            val albums: List<AlbumItem> = albumsResult.getOrDefault(emptyList())

            _uiState.update {
                it.copy(
                    searchResults = songs,
                    albumResults = albums,
                    isSearching = false,
                    errorMessage = if (songs.isEmpty() && albums.isEmpty()) "No se encontraron resultados para '$query'" else null
                )
            }

            if (songs.isNotEmpty() || albums.isNotEmpty()) {
                historyRepository.addSearchQuery(query)
            }
        }
    }

    fun selectAlbum(album: AlbumItem?) {
        if (album == null) {
            _uiState.update { it.copy(selectedAlbum = null, albumTracks = emptyList()) }
            return
        }

        albumFormatOverride = null
        _uiState.update { it.copy(selectedAlbum = album, isLoadingAlbumTracks = true, albumTracks = emptyList(), albumDownloadState = CollectionDownloadState(), albumDownloadMessage = null, albumTrackMessages = emptyMap()) }
        viewModelScope.launch {
            val settings = preferencesRepository.observeSettings().first()
            _uiState.update { it.copy(selectedAlbumFormat = albumFormatOverride ?: settings.defaultFormat) }
            val result = musicRepository.getAlbumTracks(album.id)
            _uiState.update {
                it.copy(
                    albumTracks = result.getOrDefault(emptyList()),
                    isLoadingAlbumTracks = false
                )
            }
            refreshDownloadAvailability()
        }
    }

    fun onAlbumFormatSelected(format: AudioFormat) {
        albumFormatOverride = format
        _uiState.update { it.copy(selectedAlbumFormat = format, albumDownloadState = CollectionDownloadState()) }
        refreshDownloadAvailability()
    }


    fun downloadAlbum(album: AlbumItem, tracks: List<AlbumTrackItem>) {
        if (_uiState.value.isQueueingAlbum || tracks.isEmpty()) return
        val generation = downloadRepository.cancellationGeneration
        val format = _uiState.value.selectedAlbumFormat
        val quality = AudioQuality.BEST_320K
        _uiState.update { it.copy(isQueueingAlbum = true, albumTrackMessages = emptyMap(), albumDownloadMessage = "Buscando coincidencias seguras…") }
        viewModelScope.launch {
            downloadRepository.beginBatchPreparation()
            try {
                val description = "${album.artist} • ${album.genre ?: "Álbum"} • ${album.year ?: ""}"
                var localAlbumId = playlistRepository.observePlaylists().first().firstOrNull {
                    it.isAlbum && it.name == album.title && it.description == description
                }?.id

                var queued = 0
                var preserved = 0
                var unmatched = 0
                for (track in tracks) {
                    downloadRepository.checkDownloadBatch(generation)
                    _uiState.update { it.copy(resolvingAlbumTracks = setOf(track.trackNumber)) }
                    val match = resolveSource(identity(track, album))
                    _uiState.update { it.copy(resolvingAlbumTracks = emptySet()) }
                    val ytMatch = match.song
                    downloadRepository.checkDownloadBatch(generation)
                    if (ytMatch == null) {
                        unmatched++
                        trackMessage(album, track, match.issue!!.message)
                        continue
                    }

                    val targetUrl = ytMatch.url
                    val songId = ytMatch.id

                    val destinationAlbum = localAlbumId ?: playlistRepository.createPlaylist(
                        name = album.title, description = description, isAlbum = true, thumbnailUrl = album.thumbnailUrl
                    ).also { localAlbumId = it }
                    playlistRepository.addSongToPlaylist(destinationAlbum, songId)
                    if (tasks.any { it.songId == songId && it.isActive }) { preserved++; continue }
                    if (libraryRepository.checkDownloadEligibility(songId, format, quality) == DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY) {
                        preserved++
                        trackMessage(album, track, "Ya descargada en este formato")
                        continue
                    }

                    val downloadTask = DownloadTask(
                        id = songId,
                        songId = songId,
                        title = track.title,
                        artist = track.artist,
                        album = album.title,
                        thumbnailUrl = album.thumbnailUrl?.takeIf { it.isNotBlank() } ?: track.thumbnailUrl,
                        artworkFallbackUrl = track.thumbnailUrl,
                        url = targetUrl,
                        format = format,
                        quality = quality
                    )
                    downloadRepository.enqueueDownload(downloadTask, generation)
                    queued++
                    trackMessage(album, track, "Enviada a la cola: ${format.name}")
                }

                _uiState.update {
                    it.copy(
                        albumDownloadMessage = "$queued canciones enviadas a la cola; $preserved ya coinciden con el formato elegido; $unmatched sin resultados descargables. Revisa los avisos de cada pista."
                    )
                }
            } catch (_: com.clashofmusic.app.domain.repository.DownloadBatchCancelled) {
                _uiState.update { it.copy(albumDownloadMessage = "Se canceló la descarga del álbum.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(albumDownloadMessage = "No se pudo completar el álbum: ${e.message}") }
            } finally {
                downloadRepository.endBatchPreparation()
                _uiState.update { it.copy(isQueueingAlbum = false, resolvingAlbumTracks = emptySet()) }
                refreshDownloadAvailability()
            }
        }
    }

    private fun trackMessage(album: AlbumItem, track: AlbumTrackItem, message: String) {
        _uiState.update { state ->
            if (state.selectedAlbum?.id == album.id) state.copy(albumTrackMessages = state.albumTrackMessages + (track.trackNumber to message))
            else state
        }
    }

    fun downloadAlbumTrack(album: AlbumItem, track: AlbumTrackItem) {
        if (_uiState.value.isQueueingAlbum || _uiState.value.resolvingAlbumTracks.isNotEmpty()) return
        val generation = downloadRepository.cancellationGeneration
        val format = _uiState.value.selectedAlbumFormat
        val quality = AudioQuality.BEST_320K
        _uiState.update { it.copy(resolvingAlbumTracks = setOf(track.trackNumber)) }
        viewModelScope.launch {
            try {
                val match = resolveSource(identity(track, album))
                val song = match.song
                if (song == null) {
                    trackMessage(album, track, match.issue!!.message)
                    return@launch
                }
                if (libraryRepository.checkDownloadEligibility(song.id, format, quality) == DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY) {
                    trackMessage(album, track, "Ya descargada en este formato")
                    return@launch
                }
                downloadRepository.enqueueDownload(DownloadTask(
                    id = song.id, songId = song.id, title = track.title, artist = track.artist,
                    album = album.title, thumbnailUrl = album.thumbnailUrl?.takeIf { it.isNotBlank() } ?: track.thumbnailUrl,
                        artworkFallbackUrl = track.thumbnailUrl,
                    url = song.url, format = format, quality = quality
                ), generation)
                trackMessage(album, track, "Enviada a la cola: ${format.name}")
            } catch (_: com.clashofmusic.app.domain.repository.DownloadBatchCancelled) { trackMessage(album, track, "Descarga cancelada") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { trackMessage(album, track, "No se pudo encolar: ${e.message}") }
            finally { _uiState.update { it.copy(resolvingAlbumTracks = emptySet()) } }
        }
    }

    fun extractLink(url: String) {
        linkExtractionJob?.cancel()
        linkExtractionJob = viewModelScope.launch {
            _uiState.update { it.copy(isExtractingLink = true, extractedPlaylist = null, linkErrorMessage = null, linkSuccessMessage = null) }
            val result = musicRepository.extractRemotePlaylist(url)
            ensureActive()
            result.fold(
                onSuccess = { info ->
                    _uiState.update { it.copy(extractedPlaylist = info, isExtractingLink = false, linkDownloadState = CollectionDownloadState()) }
                    refreshDownloadAvailability()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isExtractingLink = false,
                            linkErrorMessage = error.message ?: "No se pudo extraer la playlist/álbum"
                        )
                    }
                }
            )
        }
    }

    fun downloadExtractedPlaylist(playlistInfo: RemotePlaylistInfo) {
        if (_uiState.value.isQueueingLink) return
        val generation = downloadRepository.cancellationGeneration
        _uiState.update { it.copy(isQueueingLink = true, linkErrorMessage = null) }
        val format = _uiState.value.selectedLinkFormat
        val quality = AudioQuality.BEST_320K
        viewModelScope.launch {
            downloadRepository.beginBatchPreparation()
            try {
                _uiState.update { it.copy(linkSuccessMessage = "Creando playlist local y encolando ${playlistInfo.tracks.size} canciones...") }

                val description = "Importado de ${playlistInfo.source.name}: ${playlistInfo.author}"
                var localPlaylistId: String? = playlistRepository.observePlaylists().first().firstOrNull {
                    it.name == playlistInfo.title && it.description == description && it.isAlbum == playlistInfo.isAlbum
                }?.id

                var queued = 0
                var unmatched = 0
                var preserved = 0
                val omittedTitles = mutableListOf<String>()
                // Queue direct YouTube sources unchanged; compare catalog tracks that need a search.
                for (track in playlistInfo.tracks) {
                    downloadRepository.checkDownloadBatch(generation)
                    // If directUrl is missing (Spotify link), search YouTube for best match
                    val targetUrl: String
                    val songId: String

                    if (!track.directUrl.isNullOrEmpty() && track.directUrl.contains("youtube.com")) {
                        targetUrl = track.directUrl
                        songId = ServiceLocator.extractorDataSource.extractVideoId(targetUrl) ?: track.title.hashCode().toString()
                    } else {
                        val match = resolveSource(identity(track))
                        val bestMatch = match.song
                        if (bestMatch == null) {
                            unmatched++
                            omittedTitles.add("${track.artist} — ${track.title}: ${match.issue!!.message}")
                            continue
                        }
                        targetUrl = bestMatch.url
                        songId = bestMatch.id
                    }

                    downloadRepository.checkDownloadBatch(generation)
                    // Add to playlist relation
                    val destination = localPlaylistId ?: playlistRepository.createPlaylist(
                        name = playlistInfo.title,
                        description = description,
                        isAlbum = playlistInfo.isAlbum, thumbnailUrl = playlistInfo.thumbnailUrl
                    ).also { localPlaylistId = it }
                    playlistRepository.addSongToPlaylist(destination, songId)
                    if (tasks.any { it.songId == songId && it.isActive }) { preserved++; continue }
                    if (libraryRepository.checkDownloadEligibility(songId, format, quality) == DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY) {
                        preserved++
                        continue
                    }

                    // Enqueue download task - do not assign playlist name as album
                    val task = DownloadTask(
                        id = songId,
                        songId = songId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        thumbnailUrl = if (playlistInfo.isAlbum) playlistInfo.thumbnailUrl?.takeIf { it.isNotBlank() } ?: track.thumbnailUrl
                            else track.thumbnailUrl?.takeIf { it.isNotBlank() } ?: playlistInfo.thumbnailUrl,
                        artworkFallbackUrl = if (playlistInfo.isAlbum) track.thumbnailUrl else playlistInfo.thumbnailUrl,
                        url = targetUrl,
                        format = format,
                        quality = quality
                    )
                    downloadRepository.enqueueDownload(task, generation)
                    queued++
                }

                _uiState.update {
                    it.copy(
                        linkSuccessMessage = "$queued canciones enviadas a la cola; $preserved ya coinciden; $unmatched sin resultados descargables." +
                            if (omittedTitles.isEmpty()) "" else "\n" + omittedTitles.joinToString("\n")
                    )
                }
            } catch (_: com.clashofmusic.app.domain.repository.DownloadBatchCancelled) {
                _uiState.update { it.copy(linkSuccessMessage = "Se canceló la descarga de la playlist.") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(linkErrorMessage = "No se pudo completar la cola: ${e.message}") } }
            finally { downloadRepository.endBatchPreparation(); _uiState.update { it.copy(isQueueingLink = false) }; refreshDownloadAvailability() }
        }
    }

    fun downloadSong(song: SongItem) {
        _uiState.update { it.copy(downloadingSongIds = it.downloadingSongIds + song.id) }
        viewModelScope.launch {
            val settings = preferencesRepository.observeSettings().first()
            val task = DownloadTask(
                id = song.id,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                url = song.url,
                format = settings.defaultFormat,
                quality = AudioQuality.BEST_320K
            )
            downloadRepository.enqueueDownload(task)
        }
    }

    fun playSongItem(song: SongItem) {
        val downloaded = DownloadedSong(
            id = song.id,
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = null,
            durationSeconds = song.durationSeconds,
            thumbnailUrl = song.thumbnailUrl,
            filePath = "",
            fileUri = song.url,
            format = "WEB",
            quality = "",
            fileSizeBytes = 0L,
            downloadedAt = System.currentTimeMillis()
        )
        playerManager.startRadio(downloaded)
    }
}
