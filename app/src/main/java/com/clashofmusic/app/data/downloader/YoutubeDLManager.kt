package com.clashofmusic.app.data.downloader

import android.content.Context
import android.util.Log
import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.AudioQuality
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class YoutubeDLManager(
    private val context: Context,
    private val extractorDataSource: YouTubeExtractorDataSource? = null
) {
    private val activeProcesses = ConcurrentHashMap<String, DownloadSignal>()
    private val parallelChunkDownloader = ParallelChunkDownloader()
    private val processor = NativeAudioProcessor(context)

    companion object {
        private const val TAG = "YoutubeDLManager"
        @Volatile private var initialized = false
        suspend fun ensureInitialized(context: Context) = withContext(Dispatchers.IO) {
            synchronized(this@Companion) {
                if (!initialized) {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    internal suspend fun downloadAudio(
        url: String, title: String, artist: String, album: String?, outputDir: File,
        processId: String, signal: DownloadSignal, format: AudioFormat, quality: AudioQuality,
        embedMetadata: Boolean,
        transferDirectory: File? = null,
        onProgress: (Float, Long, String?, String) -> Unit
    ): Result<DownloadedAudio> = withContext(Dispatchers.IO) {
        require(processId.matches(Regex("[a-zA-Z0-9-]+")))
        val processDir = File(outputDir, "proc_$processId")
        check(processDir.isDirectory || processDir.mkdirs())
        val sourceDirectory = transferDirectory ?: processDir
        check(sourceDirectory.isDirectory || sourceDirectory.mkdirs())
        check(activeProcesses.putIfAbsent(processId, signal) == null)
        var nativeGuard: NativeResumeGuard? = null
        try {
            signal.check()
            var source: File? = null
            if (extractorDataSource != null && (extractorDataSource.isYouTubeUrl(url) || !url.startsWith("http"))) {
                try {
                    onProgress(0f, 0, null, "Extrayendo audio...")
                    val stream = extractorDataSource.getBestAudioStream(url, format)
                    signal.check()
                    if (stream != null && stream.url.isNotBlank()) {
                        val ext = stream.extension.lowercase().takeIf { it in setOf("m4a", "opus", "webm", "aac", "mp3", "ogg") }
                            ?: error("Formato de fuente desconocido")
                        val target = File(sourceDirectory, "source.$ext")
                        parallelChunkDownloader.downloadParallel(stream.url, target, signal.cancelled,
                            onCallCreated = { call -> signal.onCancel { call.cancel() } },
                            mediaId = extractorDataSource.extractVideoId(url),
                            onProgress = { progress, eta, speed, text -> onProgress(progress * 0.8f, eta, speed, text) }).getOrThrow()
                        source = target
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    signal.check()
                    Log.w(TAG, "Descarga directa falló; se usará yt-dlp: ${e.message}")
                }
            }
            signal.check()
            if (source == null) {
                ensureInitialized(context)
                signal.check()
                val fallbackDir = File(sourceDirectory, "fallback")
                // Resolve the selected source again before trusting a previous native partial.
                val infoRequest = YoutubeDLRequest(url).apply {
                    addOption("--dump-single-json"); addOption("--skip-download"); addOption("--no-playlist")
                    addOption("-f", "ba/b"); addOption("--socket-timeout", "15")
                }
                val info = JSONObject(runInterruptible {
                    signal.check()
                    YoutubeDL.getInstance().execute(infoRequest, processId).out
                })
                signal.check()
                val (identity, etag) = NativeResumeGuard.identity(info, signal)
                nativeGuard = NativeResumeGuard(fallbackDir, identity)
                val selectedFormat = info.optString("format_id").takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) }
                val request = YoutubeDLRequest(url).apply {
                    addOption("-o", File(fallbackDir, "source.%(ext)s").absolutePath)
                    addOption("--no-mtime"); addOption("--no-playlist")
                    addOption("--retries", "2"); addOption("--fragment-retries", "2")
                    addOption("--socket-timeout", "15"); addOption("-f", selectedFormat ?: "ba/b")
                    addOption("--continue"); addOption("--part")
                    etag?.let { addOption("--add-headers", "If-Range:$it") }
                    addOption("--concurrent-fragments", "4")
                }
                val progressCallback = SafeDownloadProgress(signal, when {
                    nativeGuard.reusable && nativeGuard.hadSavedBytes -> "Continuando desde el avance guardado..."
                    nativeGuard.hadSavedBytes -> "El avance ya no es compatible; descargando desde el inicio..."
                    else -> "Descargando audio..."
                }, onProgress)
                runInterruptible {
                    signal.check()
                    YoutubeDL.getInstance().execute(request, processId) { progress, eta, _ ->
                        progressCallback.emit(progress, eta)
                    }
                }
                progressCallback.throwIfFailed()
                signal.check()
                source = fallbackDir.listFiles().orEmpty().singleOrNull {
                    it.isFile && it.name.startsWith("source.") && it.length() > 0 && it.extension.lowercase() in
                        setOf("mp3", "m4a", "opus", "webm", "aac", "ogg", "wav", "flac", "mp4")
                } ?: error("El descargador no generó un único archivo de audio válido")
            }
            signal.check()
            onProgress(80f, 0, null, if (format == AudioFormat.ORIGINAL) "Validando audio original..."
                else "Preparando ${format.extension.uppercase()} con calidad automática...")
            val metadata = if (embedMetadata) buildMap {
                put("title", title); put("artist", artist)
                album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            } else emptyMap()
            val audio = processor.process(source, format, quality, metadata, signal)
            signal.check()
            onProgress(99f, 0, null, "Audio verificado; preparando archivo...")
            Result.success(audio)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { signal.check(); Result.failure(e) }
        finally {
            runCatching { nativeGuard?.save() }.onFailure { Log.w(TAG, "No se pudo conservar el avance nativo", it) }
            activeProcesses.remove(processId, signal)
        }
    }

    fun cancel(processId: String) {
        activeProcesses[processId]?.cancel()
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}
