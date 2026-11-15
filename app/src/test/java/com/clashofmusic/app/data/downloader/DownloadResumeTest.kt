package com.clashofmusic.app.data.downloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadResumeTest {
    @get:Rule val temp = TemporaryFolder()
    private val audio = ByteArray(512 * 1024) { ((it * 31 + it / 256) % 251).toByte() }

    private class Server(private val original: ByteArray) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val url = "http://127.0.0.1:${server.localPort}/audio"
        val ranges = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val ifRanges = ConcurrentLinkedQueue<String>()
        @Volatile var bytes = original
        @Volatile var etag: String? = "\"v1\""
        @Volatile var ignoreRanges = false
        @Volatile var failFull = false
        @Volatile var failChunks = false
        init { executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                executor.submit { runCatching { socket.use {
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val range = headers["range"]
                    headers["if-range"]?.let(ifRanges::add)
                    val probe = range == "bytes=0-1024"
                    val content = bytes
                    val pair = range?.removePrefix("bytes=")?.split('-')?.let {
                        it[0].toInt() to it[1].toInt().coerceAtMost(content.lastIndex)
                    }
                    if (!probe && pair != null) ranges.add(pair)
                    val fail = range == null && failFull || !probe && range != null && failChunks
                    val partial = pair != null && !ignoreRanges
                    val payload = if (fail) byteArrayOf() else if (partial) content.copyOfRange(pair!!.first, pair.second + 1) else content
                    val head = buildString {
                        append("HTTP/1.1 ${if (fail) 503 else if (partial) 206 else 200} Test\r\n")
                        append("Content-Length: ${payload.size}\r\nConnection: close\r\n")
                        if (partial) append("Content-Range: bytes ${pair!!.first}-${pair.second}/${content.size}\r\n")
                        etag?.let { append("ETag: $it\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(head.toByteArray())
                        // Give a real pause request time to arrive between bounded progress updates.
                        // An instant localhost transfer can legitimately finish before its next UI tick.
                        for (offset in payload.indices step 8192) {
                            write(payload, offset, minOf(8192, payload.size - offset)); flush()
                            if (!probe) Thread.sleep(10)
                        }
                    }
                } } }
            }
        } }
        override fun close() { server.close(); executor.shutdownNow() }
    }

    private fun pause(server: Server, file: File): Long {
        val cancelled = AtomicBoolean(false)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                ParallelChunkDownloader().downloadParallel(server.url, file, cancelled,
                    onProgress = { progress, _, _, _ -> if (progress >= 12f) cancelled.set(true) })
            }
        }
        assertFalse(file.exists())
        val saved = File(file.path + ".parts").listFiles().orEmpty().filter { it.extension == "part" }.sumOf { it.length() }
        assertTrue("There must be reusable bytes", saved in 1 until audio.size.toLong())
        return saved
    }
    private fun complete(server: Server, file: File) {
        val result = runBlocking { ParallelChunkDownloader().downloadParallel(server.url, file, AtomicBoolean(false)) { _, _, _, _ -> } }
        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        assertArrayEquals(server.bytes, file.readBytes())
    }

    @Test fun newDownloaderResumesOnlyMissingBytesAndProducesIdenticalAudio() = Server(audio).use { server ->
        val output = File(temp.root, "audio.webm")
        val saved = pause(server, output)
        server.ranges.clear(); server.ifRanges.clear()
        complete(server, output)
        assertEquals(audio.size.toLong() - saved, server.ranges.sumOf { (a, b) -> b.toLong() - a + 1 })
        assertTrue(server.ifRanges.all { it == "\"v1\"" })
        assertTrue(server.ranges.any { it.first != 0 && it.first != audio.size / 2 })
    }

    @Test fun changedSameLengthSourceCannotMixOldAndNewBytes() = Server(audio).use { server ->
        val output = File(temp.root, "changed.webm")
        pause(server, output)
        server.bytes = ByteArray(audio.size) { (it % 127).toByte() }; server.etag = "\"v2\""
        server.ranges.clear()
        complete(server, output)
        assertEquals(audio.size.toLong(), server.ranges.sumOf { (a, b) -> b.toLong() - a + 1 })
    }

    @Test fun absentOrWeakValidatorRestartsGenericUrlSafely() {
        for (tag in listOf(null, "W/\"weak\"")) Server(audio).use { server ->
            server.etag = tag
            val output = File(temp.root, "weak-${tag == null}.webm")
            pause(server, output); server.ranges.clear()
            complete(server, output)
            assertEquals(audio.size.toLong(), server.ranges.sumOf { (a, b) -> b.toLong() - a + 1 })
        }
    }

    @Test fun crashTailIsTruncatedToLastVerifiedCheckpoint() = Server(audio).use { server ->
        val output = File(temp.root, "tail.webm")
        val saved = pause(server, output)
        File(output.path + ".parts").listFiles()!!.first { it.extension == "part" }.appendBytes(ByteArray(1234) { 99 })
        server.ranges.clear(); complete(server, output)
        assertEquals(audio.size.toLong() - saved, server.ranges.sumOf { (a, b) -> b.toLong() - a + 1 })
    }

    @Test fun corruptedPartIsDownloadedAgainInsteadOfBeingPublished() = Server(audio).use { server ->
        val output = File(temp.root, "corrupt.webm")
        val saved = pause(server, output)
        val part = File(output.path + ".parts").listFiles()!!.first { it.extension == "part" && it.length() > 0 }
        val corruptedSize = part.length()
        java.io.RandomAccessFile(part, "rw").use { it.write(123) }
        server.ranges.clear(); complete(server, output)
        assertEquals(audio.size.toLong() - saved + corruptedSize, server.ranges.sumOf { (a, b) -> b.toLong() - a + 1 })
    }

    @Test fun serverRemovingRangeSupportRestartsWithoutAppending() = Server(audio).use { server ->
        val output = File(temp.root, "no-range.webm")
        pause(server, output); server.ignoreRanges = true
        complete(server, output)
        assertFalse(File(output.path + ".parts").exists())
    }

    @Test fun temporaryFailureRetainsPartialsForExplicitRetry() = Server(audio).use { server ->
        val output = File(temp.root, "retry.webm")
        val saved = pause(server, output)
        server.failChunks = true; server.failFull = true
        val result = runBlocking { ParallelChunkDownloader().downloadParallel(server.url, output, AtomicBoolean(false)) { _, _, _, _ -> } }
        assertTrue(result.isFailure); assertFalse(output.exists())
        server.failChunks = false; server.failFull = false; server.ranges.clear()
        complete(server, output)
        assertEquals(audio.size.toLong() - saved, server.ranges.sumOf { (a, b) -> b.toLong() - a + 1 })
    }

    @Test fun signedYouTubeUrlsReuseOnlyTheSameMediaVersion() {
        val url = "https://rr1.googlevideo.com/videoplayback?id=abc&itag=251&clen=1234&lmt=999&expire=1&sig=one"
        val changedSignature = url.replace("rr1", "rr2").replace("expire=1", "expire=2").replace("sig=one", "sig=two")
        assertEquals(transferIdentity(url), transferIdentity(changedSignature))
        assertNotEquals(transferIdentity(url), transferIdentity(url.replace("lmt=999", "lmt=1000")))
        assertNotEquals(transferIdentity(url), transferIdentity(url.replace("itag=251", "itag=140")))
        assertFalse(transferIdentity(url.replace("googlevideo.com", "googlevideo.com.example.org")).second)
        assertFalse(transferIdentity(url.replace("&lmt=999", "")).second)
    }

    @Test fun opaqueCdnIdChangesDoNotInvalidateTheSameYouTubeVideoVersion() {
        val url = "https://rr1.googlevideo.com/videoplayback?id=opaque-token-1&itag=251&clen=1234&lmt=999"
        val renewed = url.replace("opaque-token-1", "opaque-token-2")
        assertEquals(transferIdentity(url, "V9_ZpqfqHFI"), transferIdentity(renewed, "V9_ZpqfqHFI"))
        assertNotEquals(transferIdentity(url, "V9_ZpqfqHFI"), transferIdentity(renewed, "1FliVTcX8bQ"))
        assertNotEquals(transferIdentity(url, "V9_ZpqfqHFI"), transferIdentity(renewed.replace("lmt=999", "lmt=1000"), "V9_ZpqfqHFI"))
    }
}
