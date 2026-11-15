package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Real HTTP/native checks; fixtures and queue are isolated from the user's catalog. */
class DownloadResumeCheck(private val instrumentation: Instrumentation, private val sourceUrl: String? = null) {
    fun run() {
        val context = instrumentation.targetContext
        val directory = File(context.noBackupFilesDir, "resume_check_${UUID.randomUUID()}")
        val report = JSONObject()
        val checks = JSONArray()
        var success = false
        try {
            check(directory.mkdirs())
            val samples = 30 * 48000 * 2 * 2
            val wav = ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + samples).put("WAVEfmt ".toByteArray())
            wav.putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000)
            wav.putShort(4).putShort(16).put("data".toByteArray()).putInt(samples)
            val random = kotlin.random.Random(42)
            repeat(samples / 2) { wav.putShort(random.nextInt(-8000, 8000).toShort()) }
            val fixture = wav.array()
            Server(fixture).use { server -> runBlocking {
                val output = File(directory, "direct.wav")
                val cancelled = AtomicBoolean(false)
                val pause = runCatching {
                    ParallelChunkDownloader().downloadParallel(server.url, output, cancelled) { progress, _, _, _ ->
                        if (progress >= 15f) cancelled.set(true)
                    }.getOrThrow()
                }
                check(pause.exceptionOrNull() is CancellationException)
                check(!output.exists())
                val saved = File(output.path + ".parts").listFiles().orEmpty()
                    .filter { it.extension == "part" }.sumOf { it.length() }
                check(saved in 1 until fixture.size.toLong())
                checks.put("direct-pause-retains-private-checkpoints")
                server.ranges.clear()
                ParallelChunkDownloader().downloadParallel(server.url, output, AtomicBoolean(false)) { _, _, _, _ -> }.getOrThrow()
                check(output.readBytes().contentEquals(fixture))
                val requested = server.ranges.sumOf { (a, b) -> b - a + 1 }
                check(requested == fixture.size - saved) { "Requested $requested, saved $saved" }
                report.put("sourceBytes", fixture.size).put("reusedBytes", saved).put("requestedOnResume", requested)
                checks.put("new-downloader-requests-only-missing-bytes-with-exact-output")

                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val workspace = DownloadWorkspace(File(directory, "workspaces"))
                val store = FileDownloadTaskStore(File(directory, "queue.json"))
                val id = "isolated-resume"
                var queue: DownloadQueue? = null
                try {
                    queue = DownloadQueue(scope, store, emptyList(), worker = { attempt ->
                        val target = File(workspace.acquire(attempt.task), "source.wav")
                        ParallelChunkDownloader().downloadParallel(server.url, target, attempt.signal.cancelled,
                            onCallCreated = { call -> attempt.signal.onCancel { call.cancel() } }) { progress, _, _, _ ->
                            attempt.update { it.copy(progress = progress * 0.8f) }
                        }.getOrThrow()
                        attempt.update { it.copy(status = DownloadStatus.COMPLETED) }
                    }, discardPartial = workspace::discard)
                    queue.enqueue(DownloadTask(id, id, "Private fixture", "Test", url = server.url))
                    withTimeout(15000) { while (queue.tasks.value.single().progress < 10f) delay(10) }
                    queue.pause(id)
                    scope.coroutineContext[Job]!!.children.toList().joinAll()
                    check(queue.tasks.value.single().status == DownloadStatus.PAUSED)
                    check(workspace.acquire(queue.tasks.value.single()).listFiles()!!.any { it.name.endsWith(".parts") })
                    checks.put("queue-pause-waits-for-writer-cleanup-and-preserves-source")
                    scope.cancel()
                    val restoredScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    try {
                        var ran = false
                        val restored = DownloadQueue(restoredScope, store, store.load(), worker = { attempt ->
                            ran = true
                            val target = File(workspace.acquire(attempt.task), "source.wav")
                            ParallelChunkDownloader().downloadParallel(server.url, target, attempt.signal.cancelled) { _, _, _, _ -> }.getOrThrow()
                            check(target.readBytes().contentEquals(fixture))
                            attempt.update { it.copy(status = DownloadStatus.COMPLETED, progress = 100f) }
                        }, discardPartial = workspace::discard)
                        check(!ran && restored.tasks.value.single().status == DownloadStatus.PAUSED)
                        server.ranges.clear()
                        restored.resume(id)
                        withTimeout(15000) {
                            while (restored.tasks.value.single().status != DownloadStatus.COMPLETED) {
                                check(restored.tasks.value.single().status != DownloadStatus.FAILED)
                                delay(20)
                            }
                            restoredScope.coroutineContext[Job]!!.children.toList().joinAll()
                        }
                        check(server.ranges.sumOf { (a, b) -> b - a + 1 } < fixture.size)
                        check(File(directory, "workspaces").listFiles().orEmpty().isEmpty())
                        checks.put("restored-queue-resumes-explicitly-and-cleans-after-success")
                    } finally { restoredScope.cancel() }
                } finally { scope.cancel() }

                val nativeDirectory = File(directory, "native").apply { mkdirs() }
                val nativeSignal = DownloadSignal()
                val manager = YoutubeDLManager(context)
                val processId = UUID.randomUUID().toString()
                val pausedNative = runCatching {
                    manager.downloadAudio(server.url, "Private resume", "Test", null, directory,
                        processId, nativeSignal, AudioFormat.MP3, AudioQuality.STANDARD_128K, false,
                        transferDirectory = nativeDirectory) { progress, _, _, _ ->
                        if (progress in 10f..70f) { nativeSignal.cancel(); manager.cancel(processId) }
                    }.getOrThrow()
                }
                check(pausedNative.exceptionOrNull() is CancellationException) { pausedNative.exceptionOrNull().toString() }
                val nativePartials = nativeDirectory.walkTopDown().filter { it.name.endsWith(".part") }.toList()
                check(nativePartials.any { it.length() > 0 }) { "Native partial not retained" }
                checks.put("native-downloader-pause-retains-part-file")
                server.ranges.clear()
                val result = withTimeout(60000) {
                    YoutubeDLManager(context).downloadAudio(server.url, "Private resume", "Test", null, directory,
                        UUID.randomUUID().toString(), DownloadSignal(), AudioFormat.MP3, AudioQuality.STANDARD_128K, false,
                        transferDirectory = nativeDirectory) { _, _, _, _ -> }.getOrThrow()
                }
                check(result.probe.codec == "mp3" && result.quality == result.probe.quality)
                check(server.ranges.any { it.first > 0 }) { "Native downloader did not request a nonzero offset" }
                checks.put("native-new-manager-resumes-and-converts-to-valid-mp3")
                report.put("nativeResumeRanges", JSONArray(server.ranges.map { "${it.first}-${it.second}" }))
            } }
            sourceUrl?.let { online -> runBlocking {
                val extractor = com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource()
                val first = extractor.getBestAudioStream(online, AudioFormat.ORIGINAL) ?: error("No audio source")
                val mediaId = extractor.extractVideoId(online)
                fun describe(url: String): JSONObject {
                    val parsed = url.toHttpUrl()
                    return JSONObject().put("host", parsed.host).put("identity", transferIdentity(url, mediaId).first)
                        .put("immutable", transferIdentity(url, mediaId).second)
                        .put("parameterNames", JSONArray(parsed.queryParameterNames.sorted()))
                        .put("versionFields", JSONObject().apply {
                            listOf("id", "itag", "clen", "lmt", "mime", "xtags", "dur").forEach { name ->
                                put(name, parsed.queryParameter(name)?.let { if (name == "id") sha256(it.toByteArray()) else it } ?: JSONObject.NULL)
                            }
                        })
                }
                report.put("onlineFirstSource", describe(first.url))
                val output = File(directory, "online.${first.extension}")
                val signal = DownloadSignal()
                val paused = runCatching {
                    ParallelChunkDownloader().downloadParallel(first.url, output, signal.cancelled,
                        onCallCreated = { call -> signal.onCancel { call.cancel() } }, mediaId = mediaId) { progress, _, _, _ ->
                        if (progress >= 15f) signal.cancel()
                    }.getOrThrow()
                }
                check(paused.exceptionOrNull() is CancellationException) { paused.exceptionOrNull().toString() }
                val saved = File(output.path + ".parts").listFiles().orEmpty()
                    .filter { it.extension == "part" }.sumOf { it.length() }
                check(saved > 0)
                report.put("onlineCheckpointBefore", JSONObject(File(output.path + ".parts/manifest.json").readText()))
                val renewed = extractor.getBestAudioStream(online, AudioFormat.ORIGINAL) ?: error("No renewed source")
                report.put("onlineRenewedSource", describe(renewed.url))
                var reusedProgress = 0f
                withTimeout(90000) {
                    ParallelChunkDownloader().downloadParallel(renewed.url, output, AtomicBoolean(false), mediaId = mediaId) { progress, _, _, text ->
                        if (text.startsWith("Continuando") && reusedProgress == 0f) reusedProgress = progress
                    }.getOrThrow()
                }
                report.put("onlineCheckpointAfter", JSONObject(File(output.path + ".parts/manifest.json").readText()))
                check(reusedProgress > 0) { "Real CDN did not reuse its checkpoint" }
                val audio = NativeAudioProcessor(context).probe(output, DownloadSignal())
                check(audio.durationMs > 0)
                report.put("onlineReusedBytes", saved).put("onlineResumeProgress", reusedProgress.toDouble())
                    .put("onlineCodec", audio.codec).put("onlineDurationMs", audio.durationMs)
                checks.put("real-youtube-source-renewal-resumes-private-audio-and-passes-probe")
            } }
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
            report.put("fixturesRemoved", directory.deleteRecursively())
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "download_resume_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }

    private class Server(private val bytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        val url = "http://127.0.0.1:${server.localPort}/fixture.wav"
        val ranges = ConcurrentLinkedQueue<Pair<Long, Long>>()
        init { executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                executor.submit { runCatching { socket.use {
                    val reader = socket.getInputStream().bufferedReader()
                    val method = reader.readLine().substringBefore(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val range = headers["range"]?.removePrefix("bytes=")?.split('-')?.let {
                        it[0].toLong() to (it.getOrNull(1)?.toLongOrNull() ?: bytes.lastIndex.toLong())
                    }
                    if (range != null && range != (0L to 1024L)) ranges.add(range)
                    val start = range?.first?.toInt() ?: 0
                    val end = range?.second?.toInt()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                    val header = buildString {
                        append("HTTP/1.1 ${if (range == null) 200 else 206} OK\r\n")
                        append("Content-Type: audio/wav\r\nContent-Length: ${end - start + 1}\r\n")
                        append("ETag: \"fixture-v1\"\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                        if (range != null) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                        append("\r\n")
                    }
                    val out = socket.getOutputStream()
                    out.write(header.toByteArray())
                    if (method != "HEAD") {
                        var position = start
                        while (position <= end) {
                            val count = minOf(8192, end - position + 1)
                            out.write(bytes, position, count); out.flush(); position += count
                            if (end - start > 1024) Thread.sleep(5)
                        }
                    }
                } } }
            }
        } }
        override fun close() { server.close(); executor.shutdownNow() }
    }
}
