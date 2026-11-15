package com.clashofmusic.app.data.repository

import android.content.Context
import android.util.Log
import com.clashofmusic.app.data.downloader.DownloadQueue
import com.clashofmusic.app.data.downloader.DownloadWorkspace
import com.clashofmusic.app.data.downloader.DownloadTaskStore
import com.clashofmusic.app.data.downloader.FileDownloadTaskStore
import com.clashofmusic.app.data.downloader.YoutubeDLManager
import com.clashofmusic.app.data.media.MediaStoreHelper
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.DownloadEligibility
import com.clashofmusic.app.domain.model.DownloadStatus
import com.clashofmusic.app.domain.model.DownloadTask
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.repository.DownloadRepository
import com.clashofmusic.app.domain.repository.LibraryRepository
import com.clashofmusic.app.domain.repository.PreferencesRepository
import com.clashofmusic.app.services.DownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.clashofmusic.app.data.extractor.AppleMusicDataSource
import com.clashofmusic.app.data.media.ArtworkLoader
import com.clashofmusic.app.data.media.ArtworkCandidates
import com.clashofmusic.app.data.media.NativeMetadataEditor
import com.clashofmusic.app.data.media.AudioTags

class DownloadRepositoryImpl(
    private val context: Context,
    private val youtubeDLManager: YoutubeDLManager,
    private val mediaStoreHelper: MediaStoreHelper,
    private val libraryRepository: LibraryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val appleMusicDataSource: AppleMusicDataSource = AppleMusicDataSource()
) : DownloadRepository {

    companion object {
        private const val TAG = "DownloadRepo"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val enqueueControl = Mutex()
    private val preparingBatches = kotlinx.coroutines.flow.MutableStateFlow(0)
    override fun observeBatchPreparation(): Flow<Int> = preparingBatches
    override fun beginBatchPreparation() { preparingBatches.update { it + 1 } }
    override fun endBatchPreparation() { preparingBatches.update { (it - 1).coerceAtLeast(0) } }
    private val generation = AtomicLong()
    override val cancellationGeneration: Long get() = generation.get()
    private val workspaces = DownloadWorkspace(File(context.noBackupFilesDir, "download_resume"))
    @Volatile private var queueRef: DownloadQueue? = null
    private val queueReady = scope.async {
        val store = FileDownloadTaskStore(File(context.filesDir, "download_queue.json"))
        val initial = try { store.load() } catch (e: Exception) {
            Log.e(TAG, "No se pudo recuperar la cola", e)
            listOf(DownloadTask("queue-recovery", "queue-recovery", "Recuperación de descargas", "", url = "",
                status = DownloadStatus.FAILED, statusText = "No se pudo leer la cola guardada", errorMessage = e.message))
        }
        initial.filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELLED }.forEach { task ->
            runCatching { workspaces.discard(task) }.onFailure { Log.w(TAG, "No se pudo retirar un avance terminado", it) }
        }
        val writableStore = if (initial.any { it.id == "queue-recovery" }) object : DownloadTaskStore {
            override fun load() = initial
            override fun save(tasks: List<DownloadTask>) { error("La cola guardada no se puede leer; se conservaron sus respaldos") }
        } else store
        DownloadQueue(scope, writableStore, initial,
            worker = ::executeDownload, stopNative = youtubeDLManager::cancel,
            discardPartial = workspaces::discard,
            onError = { Log.e(TAG, "Fallo de descarga", it) }).also { queueRef = it }
    }

    override fun observeTasks(): Flow<List<DownloadTask>> = kotlinx.coroutines.flow.flow {
        emitAll(queueReady.await().tasks)
    }
    override fun isSongDownloading(songId: String) = queueRef?.tasks?.value?.any { it.songId == songId && it.isActive } == true
    override suspend fun enqueueDownload(task: DownloadTask): String = enqueue(task, null)
    override suspend fun enqueueDownload(task: DownloadTask, generation: Long): String = enqueue(task, generation)
    private suspend fun enqueue(task: DownloadTask, expectedGeneration: Long?): String = withContext(Dispatchers.IO) {
        val queue = queueReady.await()
        expectedGeneration?.let(::checkDownloadBatch)
        if (libraryRepository.checkDownloadEligibility(task.songId, task.format, task.quality) == DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY)
            return@withContext task.id
        enqueueControl.withLock {
            expectedGeneration?.let(::checkDownloadBatch)
            try { queue.enqueue(task) } catch (e: Exception) {
                Log.e(TAG, "No se pudo guardar la descarga", e)
                queue.reportFailure(task, e)
                task.id
            }
        }
    }
    override suspend fun cancelAllDownloads(): Unit = withContext(Dispatchers.IO) {
        val queue = queueReady.await()
        enqueueControl.withLock {
            generation.incrementAndGet()
            queue.cancelAll()
        }
    }
    private suspend fun control(id: String, action: (DownloadQueue) -> Unit): Unit = withContext(Dispatchers.IO) {
        val queue = queueReady.await()
        try { action(queue) } catch (e: Exception) {
            Log.e(TAG, "No se pudo actualizar la cola", e)
            queue.tasks.value.firstOrNull { it.id == id }?.let { queue.reportFailure(it, e) }
        }
        Unit
    }
    override suspend fun pauseDownload(taskId: String) = control(taskId) { it.pause(taskId) }
    override suspend fun resumeDownload(taskId: String) = control(taskId) { it.resume(taskId) }
    override suspend fun cancelDownload(taskId: String) = control(taskId) { it.cancel(taskId) }
    override suspend fun retryDownload(taskId: String) = control(taskId) { it.resume(taskId, retry = true) }
    override suspend fun deleteDownloadTask(taskId: String) = control(taskId) { it.delete(taskId) }
    override suspend fun clearFinishedDownloads() = control("") { it.clearFinished() }

    private suspend fun executeDownload(attempt: DownloadQueue.Attempt) {
        val task = attempt.task
        var publishedFile: File? = null
        var committed = false
        var workingDir: File? = null
        try {
            attempt.check()
            DownloadService.startDownload(context, task.id)
            val directory = mediaStoreHelper.acquireDownloadDirectory().also { workingDir = it }
            val settings = preferencesRepository.observeSettings().first()
            attempt.check()
            attempt.update { it.copy(status = DownloadStatus.DOWNLOADING, statusText = "Iniciando descarga...") }
            val audio = youtubeDLManager.downloadAudio(
                url = task.url, title = task.title, artist = task.artist, album = task.album,
                outputDir = directory, processId = attempt.id, signal = attempt.signal,
                format = task.format, quality = task.quality, embedMetadata = settings.embedMetadata,
                transferDirectory = workspaces.acquire(task)
            ) { progress, eta, speed, text ->
                attempt.update { it.copy(status = if (progress >= 80f) DownloadStatus.PROCESSING else DownloadStatus.DOWNLOADING,
                    progress = if (progress == 0f) it.progress.coerceAtMost(79f) else progress.coerceIn(0f, 99f),
                    etaSeconds = eta.coerceAtLeast(0), downloadSpeed = speed, statusText = text) }
            }.getOrThrow()
            attempt.check()
            val tempAudioFile = audio.file
            val appleMetadata = try {
                appleMusicDataSource.fetchTrackMetadata(task.title, task.artist)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            attempt.check()
            val finalAlbum = task.album?.takeIf { it.isNotBlank() } ?: appleMetadata?.album
            val finalGenre = appleMetadata?.genre
            val artwork = ArtworkLoader.resolve(ArtworkCandidates.forDownload(task.thumbnailUrl,
                task.artworkFallbackUrl, appleMetadata?.artworkUrl, task.songId, !task.album.isNullOrBlank()), attempt.signal)
            val finalThumb = artwork?.sourceUrl ?: task.thumbnailUrl ?: appleMetadata?.artworkUrl

            if (task.format != AudioFormat.ORIGINAL && (settings.embedMetadata || settings.embedThumbnails)) {
                val cover = if (settings.embedThumbnails) artwork?.bytes else null
                attempt.check()
                val tags = if (settings.embedMetadata) AudioTags(task.title, task.artist, finalAlbum, null, finalGenre, task.songId) else null
                NativeMetadataEditor(context).rewrite(tempAudioFile, tags, cover, settings.embedThumbnails, attempt.signal)
                attempt.check()
            }

            attempt.commit {
                val existingSong = libraryRepository.getDownloadedSong(task.songId)
                val fileExt = tempAudioFile.extension.lowercase()
                val (finalPath, finalUri) = mediaStoreHelper.exportToPublicMusic(tempAudioFile,
                    task.title, task.artist, finalAlbum, fileExt)
                val finalFile = File(finalPath).also { publishedFile = it }
                val localArtwork = if (artwork != null) mediaStoreHelper.saveLocalArtwork(task.songId,
                    finalFile.nameWithoutExtension, finalThumb, artwork.bytes) else null
                libraryRepository.saveDownloadedSong(DownloadedSong(id = task.songId, songId = task.songId,
                    title = task.title, artist = task.artist, album = finalAlbum, durationSeconds = audio.probe.durationMs / 1000,
                    thumbnailUrl = localArtwork ?: finalThumb, filePath = finalPath, fileUri = finalUri,
                    format = fileExt.uppercase(), quality = audio.quality, fileSizeBytes = finalFile.length(),
                    downloadedAt = System.currentTimeMillis(), genre = finalGenre), task.format)
                committed = true
                val oldRemoved = existingSong == null || existingSong.filePath == finalPath ||
                    mediaStoreHelper.deleteAudioFile(existingSong.filePath, existingSong.fileUri)
                attempt.update { it.copy(status = DownloadStatus.COMPLETED, progress = 100f,
                    statusText = when {
                        !oldRemoved -> "Descargada; no se pudo eliminar la versión anterior"
                        artwork == null -> "Descargada; no se pudo obtener la portada"
                        else -> "Descargada con éxito"
                    },
                    downloadSpeed = null, destinationPath = finalPath) }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                if (!committed) publishedFile?.let { mediaStoreHelper.deleteAudioFile(it.absolutePath, null) }
                workingDir?.let { directory ->
                    val ownDirectory = File(directory, "proc_${attempt.id}")
                    check(ownDirectory.canonicalFile.parentFile == directory.canonicalFile)
                    try { ownDirectory.deleteRecursively() } finally { mediaStoreHelper.releaseDownloadDirectory() }
                }
            }
        }
    }
}
