package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.presentation.downloads.*
import com.clashofmusic.app.presentation.search.SearchViewModel
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real button, queue and batch producer; all repositories, files and work are private fixtures. */
class CancelAllDownloadsCheck(private val instrumentation: Instrumentation) {
    private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name.substringBefore('-')) } as T
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow()
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        repeat(node.childCount) { find(text, node.getChild(it))?.let { return it } }; return null
    }
    fun run() {
        val report = JSONObject(); val checks = JSONArray()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "cancel_all_${UUID.randomUUID()}")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var host: MainActivity? = null; var downloads: DownloadsViewModel? = null; var search: SearchViewModel? = null
        var success = false
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15000
            while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timeout: $label" }; Thread.sleep(60) }
            checks.put(label)
        }
        try {
            check(directory.mkdirs())
            val finishedFile = File(directory, "already-completed.mp3").apply { writeText("Private completed fixture: must survive") }
            val originalBytes = finishedFile.readBytes()
            val completed = DownloadTask("kept", "kept", "Private completed song", "Test", url = "https://example.org/kept",
                status = DownloadStatus.COMPLETED, progress = 100f, destinationPath = finishedFile.absolutePath)
            val paused = DownloadTask("paused", "paused", "Private paused song", "Test", url = "https://example.org/paused", status = DownloadStatus.PAUSED)
            var snapshot = listOf(completed, paused)
            val writes = AtomicInteger()
            val started = AtomicInteger()
            val stopped = AtomicInteger()
            val queue = DownloadQueue(scope, object : DownloadTaskStore {
                override fun load() = snapshot
                override fun save(tasks: List<DownloadTask>) { snapshot = tasks.toList(); writes.incrementAndGet() }
            }, snapshot, worker = { attempt ->
                started.incrementAndGet()
                attempt.update { it.copy(status = DownloadStatus.DOWNLOADING, statusText = "Private test transfer") }
                try { awaitCancellation() } finally { stopped.incrementAndGet() }
            })
            val generation = AtomicLong()
            val enqueued = AtomicInteger()
            val releaseBatch = CompletableDeferred<Unit>()
            val preparation = AtomicInteger()
            val repository = object : DownloadRepository {
                override fun beginBatchPreparation() { preparation.incrementAndGet() }
                override fun endBatchPreparation() { preparation.decrementAndGet() }
                override val cancellationGeneration: Long get() = generation.get()
                override fun observeTasks() = queue.tasks
                override fun isSongDownloading(songId: String) = queue.tasks.value.any { it.songId == songId && it.isActive }
                override suspend fun enqueueDownload(task: DownloadTask): String {
                    val id = queue.enqueue(task)
                    if (enqueued.incrementAndGet() == 50) releaseBatch.await()
                    return id
                }
                override suspend fun cancelAllDownloads() { generation.incrementAndGet(); queue.cancelAll() }
                override suspend fun pauseDownload(taskId: String) { queue.pause(taskId) }
                override suspend fun resumeDownload(taskId: String) { queue.resume(taskId) }
                override suspend fun cancelDownload(taskId: String) { queue.cancel(taskId) }
                override suspend fun retryDownload(taskId: String) { queue.resume(taskId, true) }
                override suspend fun clearFinishedDownloads() { queue.clearFinished() }
                override suspend fun deleteDownloadTask(taskId: String) { queue.delete(taskId) }
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            host = activity
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val model = main { DownloadsViewModel(repository) }; downloads = model
            val producer = main { SearchViewModel(downloadRepository = repository,
                historyRepository = fake { if (it == "observeRecentHistory") flowOf(emptyList<SearchHistoryItem>()) else Unit },
                libraryRepository = fake { when (it) {
                    "observeDownloadedSongs" -> flowOf(emptyList<DownloadedSong>())
                    "checkDownloadEligibility" -> DownloadEligibility.CAN_DOWNLOAD
                    else -> error(it)
                } },
                playlistRepository = fake { when (it) {
                    "observePlaylists" -> flowOf(emptyList<Playlist>())
                    "createPlaylist" -> "private-list"
                    "addSongToPlaylist" -> Unit
                    else -> error(it)
                } },
                preferencesRepository = fake { if (it == "observeSettings") flowOf(AppSettings()) else error(it) }) }; search = producer
            main { activity.setContent { ClashOfMusicTheme { DownloadsScreen({}, model) } } }
            val playlist = RemotePlaylistInfo("private", "Private 401-track playlist", "Test", trackCount = 401,
                source = PlaylistSource.YOUTUBE, tracks = List(401) { RemoteTrackItem("Private track $it", "Test",
                    directUrl = "https://www.youtube.com/watch?v=${it.toString().padStart(11, '0')}") })
            main { producer.downloadExtractedPlaylist(playlist) }
            await("playlist-batch-has-50-pending-and-running-downloads") { enqueued.get() == 50 && started.get() == 1 }
            await("cancel-all-button-visible") { find("Cancelar todas") != null }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "cancel_all_check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            var node: AccessibilityNodeInfo? = find("Cancelar todas")
            repeat(6) { if (node?.isClickable != true) node = node?.parent }
            val before = writes.get()
            check(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("button-cancels-running-pending-and-paused-downloads") { queue.tasks.value.none { it.isActive } && stopped.get() == 1 }
            check(writes.get() == before + 1); checks.put("bulk-cancellation-persists-once")
            releaseBatch.complete(Unit)
            await("remaining-351-tracks-are-not-added-after-cancellation") { !producer.uiState.value.isQueueingLink }
            check(preparation.get() == 0); checks.put("cancellation-releases-batch-preparation-state")
            check(enqueued.get() == 50 && started.get() == 1)
            check(producer.uiState.value.linkSuccessMessage?.contains("canceló") == true)
            check(queue.tasks.value.first { it.id == "kept" } == completed && finishedFile.readBytes().contentEquals(originalBytes))
            checks.put("completed-song-and-file-unchanged")
            await("cancel-all-button-disappears-with-no-active-tasks") { find("Cancelar todas") == null }
            main { producer.downloadExtractedPlaylist(playlist.copy(tracks = playlist.tracks.take(1), trackCount = 1)) }
            await("new-explicit-downloads-still-work") { enqueued.get() == 51 && started.get() == 2 }
            main { model.cancelAllTasks() }
            await("new-download-can-also-be-cancelled") { queue.tasks.value.none { it.isActive } }
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            main { host?.setContent { }; downloads?.viewModelScope?.cancel(); search?.viewModelScope?.cancel(); host?.finish() }
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile); directory.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "cancel_all_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }
}
