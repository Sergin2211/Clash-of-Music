package com.clashofmusic.app.data.media

import android.content.Context
import android.net.Uri
import com.clashofmusic.app.data.downloader.DownloadSignal
import com.clashofmusic.app.data.downloader.NativeAudioProcessor
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.ToneSelection
import com.clashofmusic.app.domain.model.ToneTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Owns only private scratch files. Originals are opened read-only. */
class ToneClipService(private val context: Context) {
    private val processor = NativeAudioProcessor(context)

    fun previewUri(song: DownloadedSong): Uri {
        val local = song.filePath.takeIf { it.isNotBlank() }?.let(::File)
        if (local?.isFile == true && local.canRead()) return Uri.fromFile(local)
        val uri = Uri.parse(song.fileUri)
        require(uri.scheme in setOf("content", "file")) { "La canción debe estar descargada en el teléfono." }
        return uri
    }

    private suspend fun <T> withSource(song: DownloadedSong, signal: DownloadSignal, block: suspend (File, File) -> T): T = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "tone_clip_${UUID.randomUUID()}")
        check(directory.mkdirs()) { "No se pudo preparar el fragmento." }
        try {
            signal.check(); coroutineContext.ensureActive()
            val sourceFile = song.filePath.takeIf { it.isNotBlank() }?.let(::File)
            val source = if (sourceFile?.isFile == true && sourceFile.canRead()) sourceFile else {
                val uri = previewUri(song)
                File(directory, "input.audio").also { copy ->
                    (context.contentResolver.openInputStream(uri) ?: error("No se puede leer la canción.")).use { input ->
                        copy.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                signal.check(); coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            block(source, directory)
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    suspend fun duration(song: DownloadedSong, signal: DownloadSignal): Long = withSource(song, signal) { file, _ ->
        processor.probe(file, signal).durationMs.also { require(it > 0) { "No se pudo leer la duración." } }
    }

    suspend fun <T> withClip(song: DownloadedSong, selection: ToneSelection, signal: DownloadSignal,
                             use: suspend (File) -> T): T = withSource(song, signal) { source, directory ->
        selection.validate(processor.probe(source, signal).durationMs)
        val originalSize = source.length()
        val originalModified = source.lastModified()
        val output = File(directory, "fragment.mp3")
        processor.run("libffmpeg.so", listOf("-y", "-nostdin", "-v", "error", "-xerror",
            "-ss", ToneTime.seconds(selection.startMs), "-i", source.absolutePath,
            "-t", ToneTime.seconds(selection.durationMs), "-map", "0:a:0", "-vn", "-sn", "-dn",
            "-map_metadata", "-1", "-c:a", "libmp3lame", "-q:a", "0", "-compression_level", "0",
            "-metadata", "title=${song.title} (${ToneTime.format(selection.startMs)}–${ToneTime.format(selection.endMs)})",
            "-metadata", "artist=${song.artist}", output.absolutePath), signal)
        val probe = processor.probe(output, signal)
        require(probe.codec == "mp3" && output.length() > 0 &&
            abs(probe.durationMs - selection.durationMs) <= maxOf(200L, selection.durationMs / 1000)) { "No se pudo verificar el fragmento." }
        require(source.length() == originalSize && source.lastModified() == originalModified) { "La canción cambió. Vuelve a abrir el editor." }
        signal.check(); coroutineContext.ensureActive()
        use(output)
    }
}
