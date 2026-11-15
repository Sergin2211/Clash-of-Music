package com.clashofmusic.app.data.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.clashofmusic.app.data.downloader.DownloadSignal
import com.clashofmusic.app.data.downloader.NativeAudioProcessor
import java.io.File

/** Stream-copy remuxing changes metadata without re-encoding the audio. */
class NativeMetadataEditor(context: Context) : AudioMetadataEditor {
    private val processor = NativeAudioProcessor(context)
    private val transaction = MetadataFileTransaction()

    override suspend fun readYear(file: File): String? = readTags(file, DownloadSignal())["date"]?.take(4)?.takeIf { it.matches(Regex("[0-9]{4}")) }

    override suspend fun edit(file: File, tags: AudioTags, commit: suspend (Long) -> Unit) {
        rewrite(file, tags.validated(), null, true, DownloadSignal(), commit)
    }

    internal suspend fun readTags(file: File, signal: DownloadSignal): Map<String, String> {
        val root = org.json.JSONObject(processor.run("libffprobe.so", listOf("-v", "error", "-show_format", "-show_streams", "-of", "json", file.absolutePath), signal))
        val result = linkedMapOf<String, String>()
        fun collect(tags: org.json.JSONObject?) { tags?.keys()?.forEach { result[it.lowercase()] = tags.getString(it) } }
        collect(root.optJSONObject("format")?.optJSONObject("tags"))
        val streams = root.getJSONArray("streams")
        for (index in 0 until streams.length()) {
            val stream = streams.getJSONObject(index)
            if (stream.optString("codec_type") == "audio") collect(stream.optJSONObject("tags"))
        }
        if (result["date"].isNullOrEmpty() && !result["year"].isNullOrEmpty()) result["date"] = result.getValue("year")
        return result
    }

    internal suspend fun rewrite(file: File, tags: AudioTags?, artwork: ByteArray?, preserveArtwork: Boolean,
                                 signal: DownloadSignal, commit: suspend (Long) -> Unit = {}) {
        require(file.extension.lowercase() in setOf("mp3", "m4a", "opus")) {
            "No se admite la edición de etiquetas de este formato"
        }
        val original = processor.probe(file, signal)
        val bytes = artwork ?: if (preserveArtwork && file.extension.lowercase() in setOf("opus", "ogg")) {
            val reader = MediaMetadataRetriever()
            try { reader.setDataSource(file.absolutePath); reader.embeddedPicture } finally { reader.release() }
        } else null
        var picture: File? = null
        var cover: File? = null
        try {
        if (bytes != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png") && bounds.outWidth > 0) { "Carátula no válida" }
            if (file.extension.lowercase() in setOf("opus", "ogg")) {
                val fields = readTags(file, signal).toMutableMap()
                fields["METADATA_BLOCK_PICTURE"] = MetadataCommand.pictureBlock(bytes, bounds.outMimeType, bounds.outWidth, bounds.outHeight)
                picture = File.createTempFile(".clash-picture-", ".ffmeta", file.absoluteFile.parentFile)
                picture.writeText(MetadataCommand.metadataFile(fields))
            } else {
                cover = File.createTempFile(".clash-cover-", ".image", file.absoluteFile.parentFile)
                cover.writeBytes(bytes)
            }
        }
            transaction.replace(file, prepare = { staged ->
                signal.check()
                processor.run("libffmpeg.so", MetadataCommand.arguments(file, staged, tags, cover, picture, preserveArtwork), signal)
                val verified = processor.probe(staged, signal)
                check(verified.codec == original.codec && kotlin.math.abs(verified.durationMs - original.durationMs) < 1000) {
                    "La edición alteró el audio; se conservó el archivo anterior"
                }
                if (tags != null) {
                    val saved = readTags(staged, signal)
                    check(saved["title"].orEmpty() == tags.title && saved["artist"].orEmpty() == tags.artist &&
                        saved["album"].orEmpty() == tags.album.orEmpty() && saved["date"].orEmpty().take(4) == tags.year.orEmpty()) {
                        "No se pudieron verificar las etiquetas; se conservó el archivo anterior"
                    }
                }
                signal.check()
            }, commit = commit)
        } finally { cover?.delete(); picture?.delete() }
    }
}
