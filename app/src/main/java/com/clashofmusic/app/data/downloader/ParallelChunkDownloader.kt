package com.clashofmusic.app.data.downloader

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ParallelChunkDownloader {
    companion object {
        private const val DEFAULT_CONCURRENCY = 4
        private const val CHECKPOINT_BYTES = 512 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).followRedirects(true).build()

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        .header("Accept", "*/*").header("Accept-Encoding", "identity")
        .header("Referer", "https://www.youtube.com/")

    suspend fun downloadParallel(
        streamUrl: String, outputFile: File, isCancelled: AtomicBoolean,
        onCallCreated: (okhttp3.Call) -> Unit = {},
        mediaId: String? = null,
        onProgress: (progress: Float, etaInSeconds: Long, speed: String?, statusText: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        fun stopped() { ensureActive(); if (isCancelled.get()) throw CancellationException("Descarga detenida") }
        try {
            stopped()
            onProgress(0f, 0, null, "Comprobando el avance guardado...")
            var total = -1L
            var etag: String? = null
            // A failed/expired probe must not destroy a valid checkpoint.
            client.newCall(request(streamUrl).header("Range", "bytes=0-1024").build())
                .also(onCallCreated).execute().use { response ->
                    stopped()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} al comprobar el audio")
                    val range = parseContentRange(response.header("Content-Range"))
                    if (response.code == 206 && range != null && range.first == 0L &&
                        range.second == minOf(1024L, range.third - 1)) {
                        total = range.third
                        etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                    }
                }
            stopped()
            if (total < 2) {
                File(outputFile.path + ".parts").deleteRecursively()
                return@withContext downloadSingleStream(streamUrl, outputFile, isCancelled, onCallCreated,
                    "El servidor no permite reanudar; descargando desde el inicio...", onProgress)
            }
            val chunks = if (total > 2 * 1024 * 1024) DEFAULT_CONCURRENCY else 2
            val chunkSize = total / chunks
            val (identity, immutable) = transferIdentity(streamUrl, mediaId)
            val hadSavedBytes = File(outputFile.path + ".parts").listFiles().orEmpty()
                .any { it.extension == "part" && it.length() > 0 }
            val checkpoint = TransferCheckpoint(outputFile, identity, total, chunks, etag, immutable)
            val offsets = (0 until chunks).map { index ->
                val start = index * chunkSize
                val end = if (index == chunks - 1) total - 1 else start + chunkSize - 1
                checkpoint.restore(index, end - start + 1)
            }
            val reused = offsets.sum()
            val downloaded = AtomicLong(reused)
            val started = System.nanoTime()
            val progressGate = ProgressUpdateGate(100_000_000L)
            fun progress() {
                if (!progressGate.allow()) return
                val bytes = downloaded.get()
                val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
                val speed = if (elapsed > 0.3) ((bytes - reused) / elapsed).toLong() else 0L
                onProgress((100f * bytes / total).coerceIn(0f, 100f),
                    if (speed > 0) (total - bytes).coerceAtLeast(0) / speed else 0,
                    if (speed > 0) String.format(Locale.ROOT, "%.2f MB/s", speed / (1024.0 * 1024.0)) else null,
                    when {
                        reused > 0 -> "Continuando desde el avance guardado..."
                        hadSavedBytes -> "El avance ya no es compatible; descargando desde el inicio..."
                        else -> "Descargando audio a alta velocidad..."
                    })
            }
            progress()
            var rangeFailure: IOException? = null
            try {
                coroutineScope {
                    (0 until chunks).map { index -> async(Dispatchers.IO) {
                        val initial = index * chunkSize
                        val end = if (index == chunks - 1) total - 1 else initial + chunkSize - 1
                        val start = initial + offsets[index]
                        if (start > end) return@async
                        val builder = request(streamUrl).header("Range", "bytes=$start-$end")
                        etag?.let { builder.header("If-Range", it) }
                        client.newCall(builder.build()).also(onCallCreated).execute().use { response ->
                            stopped()
                            if (response.code != 206 || parseContentRange(response.header("Content-Range")) != Triple(start, end, total) ||
                                etag != null && response.header("ETag") != etag) {
                                throw IOException("El servidor cambio el audio o no respeta los rangos")
                            }
                            val input = response.body?.byteStream() ?: throw IOException("Bloque vacio")
                            RandomAccessFile(checkpoint.part(index), "rw").use { file ->
                                file.setLength(offsets[index])
                                file.seek(offsets[index])
                                var position = start
                                var lastSaved = file.length()
                                try {
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        stopped()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        stopped()
                                        if (read > end + 1 - position) throw IOException("El bloque excede su rango")
                                        file.write(buffer, 0, read)
                                        position += read
                                        downloaded.addAndGet(read.toLong())
                                        if (file.length() - lastSaved >= CHECKPOINT_BYTES) {
                                            checkpoint.save(index, file); lastSaved = file.length()
                                        }
                                        progress()
                                    }
                                    if (position != end + 1) throw IOException("Bloque incompleto")
                                } finally {
                                    // Runs on pause, failure and coroutine cancellation, before the queue releases its lease.
                                    checkpoint.save(index, file)
                                }
                            }
                        }
                    } }.awaitAll()
                }
            } catch (error: IOException) { rangeFailure = error }
            stopped()
            if (rangeFailure != null) {
                // Network failures retain partials. A full retry never appends to them or publishes holes.
                return@withContext downloadSingleStream(streamUrl, outputFile, isCancelled, onCallCreated,
                    "Reintentando la transferencia completa...", onProgress)
            }
            check(downloaded.get() == total) { "Audio incompleto" }
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                for (index in 0 until chunks) checkpoint.part(index).inputStream().use { input ->
                    while (true) {
                        stopped()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                output.fd.sync()
            }
            check(outputFile.length() == total) { "Audio incompleto" }
            stopped()
            onProgress(100f, 0, null, "Descarga completada")
            stopped()
            Result.success(outputFile)
        } catch (error: CancellationException) { outputFile.delete(); throw error }
        catch (error: Exception) {
            outputFile.delete()
            if (isCancelled.get()) throw CancellationException("Descarga detenida", error)
            Result.failure(error)
        }
    }

    private suspend fun downloadSingleStream(
        url: String, output: File, cancelled: AtomicBoolean, onCallCreated: (okhttp3.Call) -> Unit,
        text: String, onProgress: (Float, Long, String?, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            fun stopped() { ensureActive(); if (cancelled.get()) throw CancellationException("Descarga detenida") }
            stopped()
            onProgress(0f, 0, null, text)
            client.newCall(request(url).build()).also(onCallCreated).execute().use { response ->
                if (response.code != 200) throw IOException("HTTP ${response.code} en descarga completa")
                val body = response.body ?: throw IOException("Audio vacio")
                val length = body.contentLength()
                var total = 0L
                val started = System.nanoTime()
                val progressGate = ProgressUpdateGate(100_000_000L)
                body.byteStream().use { input -> FileOutputStream(output).use { file ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        stopped()
                        val read = input.read(buffer)
                        if (read < 0) break
                        stopped()
                        file.write(buffer, 0, read); total += read
                        if (progressGate.allow()) {
                            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
                            val speed = if (elapsed > 0.3) (total / elapsed).toLong() else 0
                            onProgress(if (length > 0) (100f * total / length).coerceIn(0f, 100f) else 0f,
                                if (speed > 0 && length > 0) (length - total).coerceAtLeast(0) / speed else 0,
                                if (speed > 0) String.format(Locale.ROOT, "%.2f MB/s", speed / (1024.0 * 1024.0)) else null, text)
                        }
                    }
                    file.fd.sync()
                } }
                if (length >= 0 && total != length || total == 0L) throw IOException("Audio incompleto")
            }
            stopped()
            onProgress(100f, 0, null, "Descarga completada")
            stopped()
            Result.success(output)
        } catch (error: CancellationException) { output.delete(); throw error }
        catch (error: Exception) {
            output.delete()
            if (cancelled.get()) throw CancellationException("Descarga detenida", error)
            Result.failure(error)
        }
    }

    private fun parseContentRange(header: String?): Triple<Long, Long, Long>? {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return if (start <= end && end < total) Triple(start, end, total) else null
    }
}
