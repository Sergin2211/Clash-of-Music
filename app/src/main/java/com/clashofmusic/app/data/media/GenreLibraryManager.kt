package com.clashofmusic.app.data.media

import com.clashofmusic.app.data.extractor.GenreLookup
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

data class GenreAnalysisState(val running: Boolean = false, val total: Int = 0, val done: Int = 0,
    val found: Int = 0, val errors: Int = 0, val current: String = "", val message: String? = null)

class GenreLibraryManager(private val library: LibraryRepository, private val lookup: GenreLookup,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val analysis = MutableStateFlow(GenreAnalysisState())
    val state = analysis.asStateFlow()
    private val work = Mutex()
    private var job: Job? = null
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            analysis.value = GenreAnalysisState(running = true)
            try {
                val songs = library.observeDownloadedSongs().first()
                val pending = songs.filter { s -> s.genreProfile?.let { it.appliesTo(s) && (it.manual || System.currentTimeMillis() - it.checkedAt < 7L * 86400000) } != true }
                analysis.value = analysis.value.copy(total = pending.size)
                var consecutiveFailures = 0
                for (song in pending) {
                    ensureActive()
                    analysis.update { it.copy(current = song.title) }
                    try {
                        val count = work.withLock { enrich(song) }
                        consecutiveFailures = 0
                        analysis.update { it.copy(done = it.done + 1, found = it.found + if (count > 0) 1 else 0) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        analysis.update { it.copy(done = it.done + 1, errors = it.errors + 1) }
                        // Don't send hundreds of requests to an unavailable service.
                        if (++consecutiveFailures >= 3) break
                    }
                }
                analysis.update { it.copy(message = if (pending.isEmpty()) "Los géneros ya están revisados. Puedes editar cualquiera manualmente."
                    else if (it.done < it.total) "El servicio no está disponible. Se conservaron ${it.done} revisiones; las demás quedan pendientes para otro intento."
                    else "${it.found} canciones con información nueva; ${it.done - it.found - it.errors} sin coincidencia; ${it.errors} consultas fallidas.") }
            } catch (cancelled: CancellationException) { analysis.update { it.copy(message = "Análisis detenido. Se conserva lo ya revisado.") }; throw cancelled }
            catch (_: Exception) { analysis.update { it.copy(message = "No se pudo analizar la biblioteca. Tus datos siguen guardados.") } }
            finally { analysis.update { it.copy(running = false, current = "") } }
        }
    }
    private suspend fun enrich(song: DownloadedSong): Int {
        val entries = lookup.lookup(song).mapNotNull { e -> GenreNames.canonical(e.name)?.let { e.copy(name = it) } }.distinctBy { GenreNames.key(it.name) }.take(24)
        coroutineContext.ensureActive()
        library.updateGenreProfile(song.songId, GenreProfile(GenreNames.identity(song), System.currentTimeMillis(), entries = entries))
        return entries.size
    }
    fun analyzeNew(song: DownloadedSong) {
        scope.launch {
            try { work.withLock {
                val current = library.getDownloadedSong(song.songId) ?: return@withLock
                if (current.genreProfile?.let { it.appliesTo(current) && (it.manual || System.currentTimeMillis() - it.checkedAt < 7L * 86400000) } != true) enrich(current)
            } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Network failures never fail an audio download. */ }
        }
    }
    fun cancel() { job?.cancel() }
    suspend fun saveManual(song: DownloadedSong, input: String) {
        library.updateGenreProfile(song.songId, GenreProfile(GenreNames.identity(song), System.currentTimeMillis(), true,
            GenreNames.split(input).map { GenreAssignment(it, GenreOrigin.MANUAL) }))
    }
    suspend fun reset(song: DownloadedSong) { library.updateGenreProfile(song.songId, null) }
}
