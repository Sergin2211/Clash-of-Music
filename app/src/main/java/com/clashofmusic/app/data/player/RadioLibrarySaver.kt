package com.clashofmusic.app.data.player

import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

data class RadioSaveState(val saved: Boolean = false, val busy: Boolean = true, val error: String? = null) {
    val label get() = when { saved -> "En biblioteca"; busy -> "Descargando…"; error != null -> "Reintentar descarga"; else -> "Descargar a biblioteca" }
}

/** Explicit permanent download: independent of the temporary player and its session lifetime. */
class RadioLibrarySaver(
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val preferences: PreferencesRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val requests = ConcurrentHashMap.newKeySet<String>()
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    fun observe(songId: String): Flow<RadioSaveState> = combine(library.observeDownloadedSongs(), downloads.observeTasks(), pending, errors) { songs, tasks, requested, messages ->
        val task = tasks.filter { it.songId == songId }.maxByOrNull { it.createdAt }
        RadioSaveState(songs.any { it.songId == songId }, songId in requested || tasks.any { it.songId == songId && it.isActive },
            messages[songId] ?: task?.takeIf { it.status == DownloadStatus.FAILED }?.errorMessage)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun save(song: DownloadedSong): Job? {
        if (!song.songId.matches(Regex("[A-Za-z0-9_-]{11}")) || !requests.add(song.songId)) return null
        pending.update { it + song.songId }; errors.update { it - song.songId }
        return scope.launch {
            try {
                if (!library.isSongDownloaded(song.songId) && !downloads.isSongDownloading(song.songId)) {
                    val settings = preferences.observeSettings().first()
                    downloads.enqueueDownload(DownloadTask(id = song.songId, songId = song.songId, title = song.title,
                        artist = song.artist, album = song.album, thumbnailUrl = song.thumbnailUrl,
                        url = "https://www.youtube.com/watch?v=${song.songId}", format = settings.defaultFormat, quality = AudioQuality.BEST_320K))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.update { (it + (song.songId to "No se pudo iniciar la descarga. Reintenta.")).entries.toList().takeLast(20).associate { row -> row.toPair() } } }
            finally { requests.remove(song.songId); pending.update { it - song.songId } }
        }
    }
}
