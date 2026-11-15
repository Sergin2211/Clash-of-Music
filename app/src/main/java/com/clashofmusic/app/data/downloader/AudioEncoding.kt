package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.AudioQuality
import org.json.JSONObject
import java.io.File

internal data class AudioProbe(val codec: String, val durationMs: Long, val bitrate: Long, val channels: Int = 2) {
    val quality: String get() = "${(bitrate / 1000.0).toInt()}k"
    companion object {
        fun decode(text: String): AudioProbe {
            val json = JSONObject(text)
            val streams = json.getJSONArray("streams")
            val audio = (0 until streams.length()).map { streams.getJSONObject(it) }
                .firstOrNull { it.optString("codec_type") == "audio" }
                ?: error("El archivo no contiene audio")
            val format = json.getJSONObject("format")
            val duration = audio.optString("duration").toDoubleOrNull()
                ?: format.optString("duration").toDoubleOrNull() ?: 0.0
            val bitrate = audio.optString("bit_rate").toLongOrNull()
                ?: format.optString("bit_rate").toLongOrNull() ?: 0L
            check(duration.isFinite() && duration > 0 && bitrate > 0) { "No se pudo validar duración/bitrate del audio" }
            return AudioProbe(audio.getString("codec_name"), (duration * 1000).toLong(), bitrate, audio.optInt("channels", 2).coerceAtLeast(1))
        }
    }
}

/** Legacy quality parameters remain readable for old queues, but never control new encoding. */
@Suppress("UNUSED_PARAMETER")
internal object AudioEncoding {
    fun canCopy(codec: String?, format: AudioFormat): Boolean = when (format) {
        AudioFormat.MP3 -> codec == "mp3"
        AudioFormat.M4A -> codec == "aac"
        AudioFormat.OPUS -> codec == "opus"
        AudioFormat.ORIGINAL -> true
    }

    fun arguments(input: File, output: File, format: AudioFormat, quality: AudioQuality,
                  metadata: Map<String, String>, sourceCodec: String? = null, sourceChannels: Int = 2): List<String> {
        require(format != AudioFormat.ORIGINAL)
        val codec = when (format) { AudioFormat.MP3 -> "libmp3lame"; AudioFormat.M4A -> "aac"; else -> "libopus" }
        return buildList {
            addAll(listOf("-y", "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror", "-i", input.absolutePath,
                "-map", "0:a:0", "-vn", "-map_metadata", "-1"))
            if (canCopy(sourceCodec, format)) addAll(listOf("-c:a", "copy"))
            else {
                addAll(listOf("-c:a", codec))
                addAll(when (format) {
                    AudioFormat.MP3 -> listOf("-q:a", "0", "-compression_level", "0")
                    AudioFormat.M4A -> listOf("-q:a", "5", "-aac_coder", "twoloop")
                    else -> listOf("-b:a", if (sourceChannels == 1) "256k" else "510k", "-vbr", "on", "-compression_level", "10", "-application", "audio")
                })
            }
            if (format == AudioFormat.M4A) addAll(listOf("-movflags", "+faststart"))
            metadata.forEach { (key, value) -> addAll(listOf("-metadata", "$key=$value")) }
            add(output.absolutePath)
        }
    }
    fun validate(probe: AudioProbe, format: AudioFormat, quality: AudioQuality) {
        if (format == AudioFormat.ORIGINAL) return
        val expected = when (format) { AudioFormat.MP3 -> "mp3"; AudioFormat.M4A -> "aac"; else -> "opus" }
        check(probe.codec == expected) { "El audio no coincide con el formato solicitado" }
        check(probe.durationMs > 0 && probe.bitrate > 0) { "No se pudo verificar el audio generado" }
    }
}

internal data class DownloadedAudio(val file: File, val probe: AudioProbe, val quality: String)
