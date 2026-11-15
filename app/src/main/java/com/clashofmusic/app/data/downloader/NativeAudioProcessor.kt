package com.clashofmusic.app.data.downloader

import android.content.Context
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.AudioQuality
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runInterruptible
import java.io.File

/** Executes the FFmpeg/ffprobe binaries already packaged with youtubedl-android 0.18.1. */
internal class NativeAudioProcessor(private val context: Context) {
    internal suspend fun run(binary: String, args: List<String>, signal: DownloadSignal): String {
        signal.check()
        // FFmpeg's shared libraries also depend on the bundled Python package's libc++ runtime.
        YoutubeDLManager.ensureInitialized(context)
        return runInterruptible {
            signal.check()
            FFmpeg.getInstance().init(context)
            val command = listOf(File(context.applicationInfo.nativeLibraryDir, binary).absolutePath) + args
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
            builder.environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c")
                .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
            val process = builder.start()
            val unregister = signal.onCancel { process.destroy() }
            try {
                val output = StringBuilder()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        signal.check()
                        check(!Thread.currentThread().isInterrupted) { "Proceso interrumpido" }
                        output.appendLine(line)
                        if (output.length > 65536) output.delete(0, output.length - 65536)
                    }
                }
                val exit = process.waitFor()
                signal.check()
                check(exit == 0) { "Error de procesamiento de audio: ${output.takeLast(2000)}" }
                output.toString()
            } finally {
                unregister()
                process.destroyForcibly()
                var interrupted = Thread.interrupted()
                while (true) {
                    try { process.waitFor(); break } catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    suspend fun probe(file: File, signal: DownloadSignal): AudioProbe = AudioProbe.decode(run("libffprobe.so",
        listOf("-v", "error", "-show_streams", "-show_format", "-of", "json", file.absolutePath), signal))

    suspend fun process(source: File, format: AudioFormat, quality: AudioQuality, metadata: Map<String, String>,
                        signal: DownloadSignal): DownloadedAudio {
        val output = if (format == AudioFormat.ORIGINAL) source else File(source.parentFile, "encoded.${format.extension}")
        if (format != AudioFormat.ORIGINAL) {
            val input = probe(source, signal)
            run("libffmpeg.so", AudioEncoding.arguments(source, output, format, quality, metadata, input.codec, input.channels), signal)
        }
        signal.check()
        check(output.isFile && output.length() > 0) { "El conversor no generó audio" }
        val info = probe(output, signal)
        AudioEncoding.validate(info, format, quality)
        return DownloadedAudio(output, info, info.quality)
    }
}
