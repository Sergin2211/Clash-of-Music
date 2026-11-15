package com.clashofmusic.app.data.local

import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.DownloadedSong
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keeps the requested format separate from the actual container (Original can be M4A). */
class RequestedAudioFormatStore(private val file: File) {
    private var content: JSONObject? = null
    private fun read(): JSONObject = content ?: (try { JSONObject(file.readText(Charsets.UTF_8)) }
        catch (_: Exception) { JSONObject() }).also { content = it }

    @Synchronized
    fun get(song: DownloadedSong): AudioFormat? {
        val row = read().optJSONObject(song.songId) ?: return null
        if (row.optString("path") != song.filePath || row.optLong("size") != song.fileSizeBytes ||
            row.optLong("savedAt") != song.downloadedAt) return null
        return runCatching { AudioFormat.valueOf(row.getString("requested")) }.getOrNull()
    }

    @Synchronized
    fun record(song: DownloadedSong, requested: AudioFormat) {
        val data = read().put(song.songId, JSONObject().put("path", song.filePath)
            .put("size", song.fileSizeBytes).put("savedAt", song.downloadedAt).put("requested", requested.name))
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { it.write(data.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
