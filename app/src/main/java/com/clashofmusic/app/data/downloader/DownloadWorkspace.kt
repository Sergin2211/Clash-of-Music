package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.DownloadTask
import org.json.JSONObject
import java.io.File

/** Private durable source data, independent of cache clearing and the music library. */
internal class DownloadWorkspace(private val root: File) {
    private fun directory(task: DownloadTask): File {
        val directory = File(root, sha256(task.id.toByteArray()))
        check(directory.canonicalFile.parentFile == root.canonicalFile)
        return directory
    }

    fun acquire(task: DownloadTask): File {
        check(root.isDirectory || root.mkdirs())
        val directory = directory(task)
        val binding = sha256(listOf(task.songId, task.url, task.format.name, task.quality.name)
            .joinToString("") { "${it.length}:$it" }.toByteArray())
        val manifest = File(directory, "task.json")
        if (runCatching { JSONObject(manifest.readText()).getString("binding") }.getOrNull() != binding) {
            discard(task)
        }
        check(directory.isDirectory || directory.mkdirs())
        atomicTransferJson(manifest, JSONObject().put("binding", binding))
        return directory
    }

    fun discard(task: DownloadTask) {
        val directory = directory(task)
        check(!directory.exists() || directory.deleteRecursively()) { "No se pudo retirar el avance temporal" }
    }
}
