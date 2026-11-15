package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.DownloadRepository
import com.clashofmusic.app.services.DownloadNotificationUpdates
import com.clashofmusic.app.services.DownloadService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Progress storm + real foreground notification, scrolling and private audio conversion.
 * Replaces only the in-memory download source for this debug instrumentation process.
 * No tasks, songs or metadata are written to the user's repositories.
 */
class DownloadProgressLoadCheck(private val instrumentation: Instrumentation) {
    private fun textNodes(text: String, node: android.view.accessibility.AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow): List<android.view.accessibility.AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return buildList {
            if (node.text?.toString() == text) add(node)
            repeat(node.childCount) { addAll(textNodes(text, node.getChild(it))) }
        }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    fun run() {
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { ServiceLocator.init(context) }
        val directory = File(context.cacheDir, "progress_check_${UUID.randomUUID()}")
        val original = ServiceLocator.downloadRepository
        val field = ServiceLocator::class.java.getDeclaredField("downloadRepository").apply { isAccessible = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val report = JSONObject()
        val checks = JSONArray()
        var activity: Activity? = null
        var swapped = false
        var success = false
        var frames = 0
        var lastFrame = 0L
        var maxFrameNanos = 0L
        var slowFrames = 0
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (lastFrame != 0L) {
                    val delta = time - lastFrame
                    maxFrameNanos = maxOf(maxFrameNanos, delta)
                    if (delta > 50_000_000L) slowFrames++
                }
                lastFrame = time; frames++
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { label }
                Thread.sleep(30)
            }
            checks.put(label)
        }
        try {
            runBlocking { check(original.observeTasks().first().none { it.isActive }) { "User downloads must be idle" } }
            check(directory.mkdirs())
            val samples = 48000 * 20
            val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            bytes.putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000)
            bytes.putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
            val random = kotlin.random.Random(42)
            repeat(samples) { bytes.putShort(random.nextInt(-1000, 1000).toShort()) }
            val source = File(directory, "private.wav").apply { writeBytes(bytes.array()) }
            val progressCount = AtomicInteger()
            val notificationCount = AtomicInteger()
            val synthetic = DownloadTask("progress-fixture", "progress-fixture", "Synthetic active download", "Private test", url = "private")
            val history = (1..100).map { synthetic.copy(id = "history-$it", songId = "history-$it", title = "Private history $it",
                status = DownloadStatus.COMPLETED, progress = 100f, createdAt = it.toLong()) }
            val store = object : DownloadTaskStore {
                override fun load() = emptyList<DownloadTask>()
                override fun save(tasks: List<DownloadTask>) = Unit
            }
            val queue = DownloadQueue(scope, store, history, worker = { attempt ->
                attempt.update { it.copy(status = DownloadStatus.DOWNLOADING, statusText = "Synthetic transfer") }
                repeat(3000) { index ->
                    delay(2)
                    attempt.update { it.copy(progress = (index + 1) / 40f, downloadSpeed = "$index KB/s") }
                }
                attempt.update { it.copy(status = DownloadStatus.PROCESSING, statusText = "Private audio conversion") }
                val result = NativeAudioProcessor(context).process(source, AudioFormat.MP3, AudioQuality.BEST_320K, emptyMap(), attempt.signal)
                check(result.probe.codec == "mp3" && result.probe.durationMs > 19000)
                attempt.update { it.copy(status = DownloadStatus.COMPLETED, progress = 100f) }
            })
            val fake = object : DownloadRepository {
                override fun observeTasks() = queue.tasks
                override fun isSongDownloading(songId: String) = queue.tasks.value.any { it.songId == songId && it.isActive }
                override suspend fun enqueueDownload(task: DownloadTask) = queue.enqueue(task)
                override suspend fun pauseDownload(taskId: String) { queue.pause(taskId) }
                override suspend fun resumeDownload(taskId: String) { queue.resume(taskId) }
                override suspend fun cancelDownload(taskId: String) { queue.cancel(taskId) }
                override suspend fun cancelAllDownloads() { queue.cancelAll() }
                override suspend fun retryDownload(taskId: String) { queue.resume(taskId, true) }
                override suspend fun clearFinishedDownloads() { queue.clearFinished() }
                override suspend fun deleteDownloadTask(taskId: String) { queue.delete(taskId) }
            }
            field.set(null, fake); swapped = true
            val updates = DownloadNotificationUpdates()
            scope.launch {
                queue.tasks.collect { rows ->
                    rows.firstOrNull { it.id == synthetic.id }?.let {
                        if (it.status == DownloadStatus.DOWNLOADING) progressCount.incrementAndGet()
                        if (it.isRunning && updates.shouldNotify(it)) notificationCount.incrementAndGet()
                    }
                }
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("navigation-is-ready") { textNodes("Descargas").isNotEmpty() }
            val start = SystemClock.elapsedRealtime()
            queue.enqueue(synthetic)
            DownloadService.startDownload(context, synthetic.id)
            val manager = context.getSystemService(NotificationManager::class.java)
            await("foreground-notification-visible-during-storm") { manager.activeNotifications.any { it.id == DownloadService.NOTIFICATION_ID } }
            val metrics = context.resources.displayMetrics
            var tab = textNodes("Descargas").lastOrNull()
            var clicked = false
            repeat(6) {
                if (!clicked && tab?.isClickable == true) clicked = tab!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                else if (!clicked) tab = tab?.parent
            }
            check(clicked) { "Downloads navigation is unavailable" }
            await("download-row-is-visible") { textNodes("Synthetic active download").isNotEmpty() }
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(frameCallback) }
            repeat(3) { shell("input swipe ${metrics.widthPixels / 2} ${(metrics.heightPixels * .80).toInt()} ${metrics.widthPixels / 2} ${(metrics.heightPixels * .30).toInt()} 350") }
            repeat(3) { shell("input swipe ${metrics.widthPixels / 2} ${(metrics.heightPixels * .30).toInt()} ${metrics.widthPixels / 2} ${(metrics.heightPixels * .80).toInt()} 350") }
            check(instrumentation.uiAutomation.rootInActiveWindow?.packageName == context.packageName)
            checks.put("downloads-scroll-while-progress-is-arriving")
            runBlocking { withTimeout(30000) { queue.tasks.first { rows -> rows.any { it.id == synthetic.id && !it.isActive } } } }
            val elapsed = SystemClock.elapsedRealtime() - start
            check(queue.tasks.value.first { it.id == synthetic.id }.status == DownloadStatus.COMPLETED)
            check(progressCount.get() <= elapsed / 250 + 5)
            check(notificationCount.get() <= elapsed / 1000 + 6)
            checks.put("3000-callbacks-coalesced-with-immediate-completion").put("private-mp3-conversion-is-valid")
            await("foreground-notification-removed-after-completion") { manager.activeNotifications.none { it.id == DownloadService.NOTIFICATION_ID } }
            instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(frameCallback) }
            check(frames > 20)
            report.put("attemptedProgressCallbacks", 3000).put("visibleProgressSnapshots", progressCount.get())
                .put("notificationPolicyUpdates", notificationCount.get()).put("durationMs", elapsed)
                .put("framesObserved", frames).put("frameGapsOver50ms", slowFrames).put("maxFrameGapMs", maxFrameNanos / 1_000_000.0)
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            context.stopService(Intent(context, DownloadService::class.java))
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            instrumentation.runOnMainSync {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                activity?.finish()
            }
            if (swapped) field.set(null, original)
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            report.put("fixturesRemoved", directory.deleteRecursively())
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "download_progress_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
