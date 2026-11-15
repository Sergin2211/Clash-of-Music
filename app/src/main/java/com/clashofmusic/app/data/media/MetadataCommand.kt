package com.clashofmusic.app.data.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64

internal object MetadataCommand {
    fun arguments(input: File, output: File, tags: AudioTags?, cover: File?, pictureMetadata: File?, preserveArtwork: Boolean): List<String> = buildList {
        val ext = input.extension.lowercase()
        addAll(listOf("-y", "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror", "-i", input.absolutePath))
        if (cover != null && ext != "opus" && ext != "ogg") addAll(listOf("-i", cover.absolutePath))
        if (pictureMetadata != null) addAll(listOf("-f", "ffmetadata", "-i", pictureMetadata.absolutePath))
        addAll(listOf("-map", "0:a", "-c:a", "copy", "-map_metadata", if (pictureMetadata != null) "1" else "0"))
        if (pictureMetadata != null) addAll(listOf("-map_metadata:s:a", "1:g"))
        if (ext == "opus" || ext == "ogg") {
            if (!preserveArtwork && pictureMetadata == null) addAll(listOf("-metadata", "METADATA_BLOCK_PICTURE=", "-metadata:s:a", "METADATA_BLOCK_PICTURE="))
        } else if (cover != null) {
            addAll(listOf("-map", "1:v:0", "-c:v", "mjpeg", "-disposition:v:0", "attached_pic"))
        } else if (preserveArtwork && ext in setOf("mp3", "m4a", "mp4")) {
            addAll(listOf("-map", "0:v?", "-c:v", "copy"))
        }
        if (tags != null) {
            val values = linkedMapOf("title" to tags.title, "artist" to tags.artist, "album" to tags.album.orEmpty(),
                "date" to tags.year.orEmpty(), "year" to "")
            tags.genre?.let { values["genre"] = it }
            tags.songId?.let { values["CLASH_SONG_ID"] = it }
            values.forEach { (key, value) ->
                addAll(listOf("-metadata", "$key=$value", "-metadata:s:a", "$key=$value"))
            }
        }
        if (ext == "mp3") addAll(listOf("-id3v2_version", "3"))
        if (ext in setOf("m4a", "mp4")) addAll(listOf("-movflags", "+faststart"))
        add(output.absolutePath)
    }

    fun metadataFile(tags: Map<String, String>): String {
        fun escape(value: String) = value.replace("\\", "\\\\").replace("=", "\\=").replace(";", "\\;").replace("#", "\\#").replace("\n", "\\\n").replace("\r", "")
        return ";FFMETADATA1\n" + tags.entries.joinToString("\n") { "${escape(it.key)}=${escape(it.value)}" } + "\n"
    }

    /** Vorbis/Opus cover art is a base64 FLAC picture block, not an extra video stream. */
    fun pictureBlock(bytes: ByteArray, mime: String, width: Int, height: Int): String {
        require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024 && width > 0 && height > 0)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            val media = mime.toByteArray(Charsets.US_ASCII)
            it.writeInt(3); it.writeInt(media.size); it.write(media); it.writeInt(0)
            it.writeInt(width); it.writeInt(height); it.writeInt(24); it.writeInt(0)
            it.writeInt(bytes.size); it.write(bytes)
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
