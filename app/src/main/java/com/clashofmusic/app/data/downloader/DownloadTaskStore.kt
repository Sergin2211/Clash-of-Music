package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

internal interface DownloadTaskStore {
    fun load(): List<DownloadTask>
    fun save(tasks: List<DownloadTask>)
}

/** Atomic queue snapshots are independent of the music catalog. */
internal class FileDownloadTaskStore(private val file: File) : DownloadTaskStore {
    private val previous get() = File(file.path + ".previous")
    override fun load(): List<DownloadTask> {
        val existing = listOf(file, previous).filter { it.exists() }
        if (existing.isEmpty()) return emptyList()
        for (candidate in existing) {
            try { return decode(candidate.readText()) } catch (_: Exception) { }
        }
        throw IOException("No se pudo recuperar la cola de descargas; se conservaron sus respaldos")
    }
    override fun save(tasks: List<DownloadTask>) {
        val text = encode(tasks)
        val old = runCatching { file.readText().also { decode(it) } }.getOrNull()
        if (old != null) replace(previous, old)
        replace(file, text)
    }
    private fun replace(target: File, text: String) {
        val parent = target.absoluteFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs())
        val temp = File.createTempFile(".download-queue-", ".pending", parent)
        try {
            FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            decode(temp.readText())
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun encode(tasks: List<DownloadTask>): String = JSONObject().put("version", 1)
        .put("tasks", JSONArray().apply { tasks.forEach { task -> put(JSONObject().apply {
            put("id", task.id); put("songId", task.songId); put("title", task.title); put("artist", task.artist)
            put("album", task.album ?: JSONObject.NULL); put("thumbnail", task.thumbnailUrl ?: JSONObject.NULL)
            put("artworkFallback", task.artworkFallbackUrl ?: JSONObject.NULL)
            put("url", task.url); put("format", task.format.name); put("quality", task.quality.name)
            put("status", task.status.name); put("progress", task.progress.toDouble())
            put("statusText", task.statusText); put("error", task.errorMessage ?: JSONObject.NULL)
            put("destination", task.destinationPath ?: JSONObject.NULL); put("createdAt", task.createdAt)
        }) } }).toString()

    private fun decode(text: String): List<DownloadTask> {
        val root = JSONObject(text)
        check(root.getInt("version") == 1)
        val rows = root.getJSONArray("tasks")
        val tasks = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun optional(key: String) = if (row.isNull(key)) null else row.getString(key)
            DownloadTask(id = row.getString("id"), songId = row.getString("songId"), title = row.getString("title"),
                artist = row.getString("artist"), album = optional("album"), thumbnailUrl = optional("thumbnail"),
                artworkFallbackUrl = optional("artworkFallback"),
                url = row.getString("url"), format = AudioFormat.valueOf(row.getString("format")),
                quality = AudioQuality.valueOf(row.getString("quality")), status = DownloadStatus.valueOf(row.getString("status")),
                progress = row.getDouble("progress").toFloat().also { check(it.isFinite() && it in 0f..100f) },
                statusText = row.getString("statusText"), errorMessage = optional("error"),
                destinationPath = optional("destination"), createdAt = row.getLong("createdAt"))
        }
        check(tasks.all { it.id.isNotBlank() } && tasks.map { it.id }.distinct().size == tasks.size)
        return tasks
    }
}
