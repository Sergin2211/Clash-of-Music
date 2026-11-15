package com.clashofmusic.app.data.downloader

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest

internal fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it) }

internal fun transferFileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun atomicTransferJson(file: File, json: JSONObject) {
    val temp = File.createTempFile("checkpoint-", ".pending", file.parentFile!!)
    try {
        FileOutputStream(temp).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { temp.delete() }
}

/** Signed CDN URLs change on extraction. Only immutable media/version fields may identify them. */
internal fun transferIdentity(url: String, mediaId: String? = null): Pair<String, Boolean> {
    val parsed = url.toHttpUrl()
    val stableVideoId = mediaId?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{11}")) }
    val versionFields = listOf("itag", "clen", "lmt")
    val immutable = parsed.isHttps && parsed.host.endsWith(".googlevideo.com") &&
        versionFields.all { !parsed.queryParameter(it).isNullOrBlank() } &&
        (stableVideoId != null || !parsed.queryParameter("id").isNullOrBlank())
    // CDN `id` can be an opaque token renewed on every extraction, unlike the actual video ID.
    val identity = if (immutable) listOf("media" to (stableVideoId ?: parsed.queryParameter("id").orEmpty()))
        .plus((versionFields + listOf("mime", "xtags", "dur")).map { it to parsed.queryParameter(it).orEmpty() })
        .joinToString("") { (name, value) -> "$name:${value.length}:$value;" }
    else url
    return sha256(identity.toByteArray()) to immutable
}

/** Data is synced before committing its length/hash. Uncommitted tails after a crash are discarded. */
internal class TransferCheckpoint(
    output: File, identity: String, total: Long, val chunks: Int,
    val etag: String?, immutableSource: Boolean
) {
    val directory = File(output.path + ".parts")
    init {
        val manifest = File(directory, "manifest.json")
        val expected = JSONObject().put("version", 1).put("identity", identity).put("total", total)
            .put("chunks", chunks).put("etag", etag ?: JSONObject.NULL)
        val matches = runCatching {
            val old = JSONObject(manifest.readText())
            old.getInt("version") == 1 && old.getString("identity") == identity &&
                old.getLong("total") == total && old.getInt("chunks") == chunks &&
                old.optString("etag", "") == expected.optString("etag", "")
        }.getOrDefault(false)
        if (!matches || etag == null && !immutableSource) {
            check(!directory.exists() || directory.deleteRecursively()) { "No se pudo reiniciar el avance incompatible" }
        }
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo guardar el avance" }
        atomicTransferJson(manifest, expected)
    }

    fun part(index: Int) = File(directory, "$index.part")
    private fun record(index: Int) = File(directory, "$index.json")

    fun restore(index: Int, maximum: Long): Long {
        val data = part(index)
        val length = runCatching {
            val row = JSONObject(record(index).readText())
            val size = row.getLong("size")
            check(size in 0..maximum && data.isFile && data.length() >= size)
            RandomAccessFile(data, "rw").use { it.setLength(size) }
            check(transferFileHash(data) == row.getString("sha256"))
            size
        }.getOrElse { data.delete(); record(index).delete(); 0L }
        return length
    }

    fun save(index: Int, file: RandomAccessFile) {
        file.fd.sync()
        atomicTransferJson(record(index), JSONObject().put("size", file.length()).put("sha256", transferFileHash(part(index))))
    }

}
