package com.clashofmusic.app.data.downloader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ParallelChunkDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val audio = ByteArray(128 * 1024) { ((it * 31 + it / 256) % 251).toByte() }

    private enum class Mode { VALID, IGNORE_RANGES, IGNORE_CHUNKS, WRONG_RANGE, SHORT_CHUNK, LONG_CHUNK, FAIL_FALLBACK }

    @Test fun validRangesProduceExactAudio() = verify(Mode.VALID, expectFallback = false)
    @Test fun serverWithoutRangeSupportUsesFullDownload() = verify(Mode.IGNORE_RANGES, expectFallback = true)
    @Test fun serverIgnoringChunkRangesCannotCorruptOutput() = verify(Mode.IGNORE_CHUNKS, expectFallback = true)
    @Test fun incorrectContentRangeFallsBack() = verify(Mode.WRONG_RANGE, expectFallback = true)
    @Test fun shortChunkCannotPassBecauseFileWasPreallocated() = verify(Mode.SHORT_CHUNK, expectFallback = true)
    @Test fun oversizedChunkCannotOverwriteItsNeighbor() = verify(Mode.LONG_CHUNK, expectFallback = true)

    @Test fun failedChunksAndFailedFallbackLeaveNoPublishedAudio() {
        withServer(Mode.FAIL_FALLBACK) { url, _ ->
            val output = File(temp.root, "failed.mp3")
            val result = runBlocking { ParallelChunkDownloader().downloadParallel(url, output, AtomicBoolean(false)) { _, _, _, _ -> } }
            assertTrue(result.isFailure)
            assertFalse(output.exists())
        }
    }

    private fun verify(mode: Mode, expectFallback: Boolean) {
        withServer(mode) { url, fullRequests ->
            val output = File(temp.root, "audio.mp3")
            val result = runBlocking { ParallelChunkDownloader().downloadParallel(url, output, AtomicBoolean(false)) { _, _, _, _ -> } }
            assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
            assertArrayEquals(audio, output.readBytes())
            assertEquals(expectFallback, fullRequests.get() > 0)
        }
    }

    private fun withServer(mode: Mode, block: (String, AtomicInteger) -> Unit) {
        val executor = Executors.newCachedThreadPool()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val fullRequests = AtomicInteger()
        executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) {
                    if (server.isClosed) break else throw e
                }
                executor.submit {
                    socket.use {
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        reader.readLine() // Request line
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(":").lowercase()] = line.substringAfter(":").trim()
                        }
                        val range = headers["range"]
                        val isProbe = range == "bytes=0-1024"
                        if (range == null) fullRequests.incrementAndGet()
                        var contentRange: String? = null
                        val status: Int
                        val bytes: ByteArray
                        if (range == null && mode == Mode.FAIL_FALLBACK) {
                            status = 503
                            bytes = byteArrayOf()
                        } else if (range == null || mode == Mode.IGNORE_RANGES || (!isProbe && mode == Mode.IGNORE_CHUNKS)) {
                            status = 200
                            bytes = audio
                        } else {
                            val parts = range.removePrefix("bytes=").split("-")
                            val start = parts[0].toInt()
                            val end = parts[1].toInt().coerceAtMost(audio.lastIndex)
                            val corruptRange = !isProbe && (mode == Mode.WRONG_RANGE || mode == Mode.FAIL_FALLBACK)
                            contentRange = "bytes ${if (corruptRange) start + 1 else start}-$end/${audio.size}"
                            status = 206
                            bytes = when {
                                !isProbe && mode == Mode.SHORT_CHUNK -> audio.copyOfRange(start, end)
                                !isProbe && mode == Mode.LONG_CHUNK -> audio.copyOfRange(start, end + 1) + byteArrayOf(1)
                                else -> audio.copyOfRange(start, end + 1)
                            }
                        }
                        val response = buildString {
                            append("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                            if (contentRange != null) append("Content-Range: $contentRange\r\n")
                            append("\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(response.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}/audio", fullRequests)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }
}
