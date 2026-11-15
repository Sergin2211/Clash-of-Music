package com.clashofmusic.app.data.downloader

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** yt-dlp owns its transfer protocol; reuse only a verified source version and unchanged local files. */
internal class NativeResumeGuard(private val directory: File, private val identity: String?) {
    private val marker get() = File(directory, "resume.json")
    private fun sources() = directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("source.") }.sortedBy { it.name }
    val hadSavedBytes = sources().any { it.length() > 0 }
    val reusable: Boolean
    init {
        reusable = identity != null && runCatching {
            val saved = JSONObject(marker.readText())
            check(saved.getString("identity") == identity)
            val files = sources()
            val rows = saved.getJSONArray("files")
            check(files.size == rows.length())
            files.forEachIndexed { index, file ->
                val row = rows.getJSONObject(index)
                check(file.name == row.getString("name") && file.length() == row.getLong("size") &&
                    transferFileHash(file) == row.getString("sha256"))
            }
            true
        }.getOrDefault(false)
        if (!reusable) check(!directory.exists() || directory.deleteRecursively()) { "No se pudo retirar la descarga incompatible" }
        check(directory.isDirectory || directory.mkdirs())
    }

    /** Called only after the native process has exited, never while it is still writing. */
    fun save() {
        if (identity == null) return
        atomicTransferJson(marker, JSONObject().put("identity", identity).put("files", JSONArray().apply {
            sources().forEach { file -> put(JSONObject().put("name", file.name).put("size", file.length())
                .put("sha256", transferFileHash(file))) }
        }))
    }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build()

        fun identity(info: JSONObject, signal: DownloadSignal): Pair<String?, String?> {
            try {
                signal.check()
                val url = info.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: return null to null
                val mediaId = info.optString("id").takeIf { info.optString("extractor_key").equals("Youtube", ignoreCase = true) }
                val (key, immutable) = transferIdentity(url, mediaId)
                val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
                val headers = info.optJSONObject("http_headers")
                headers?.keys()?.forEach { name -> builder.header(name, headers.getString(name)) }
                builder.header("Range", "bytes=0-0")
                val call = client.newCall(builder.build())
                val unregister = signal.onCancel { call.cancel() }
                try { call.execute().use { response ->
                    signal.check()
                    val range = Regex("bytes 0-0/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    if (response.code != 206 || range == null) return null to null
                    val etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                    if (!immutable && etag == null) return null to null
                    return sha256("$key:${range.groupValues[1]}:${etag.orEmpty()}:${info.optString("format_id")}".toByteArray()) to etag
                } } finally { unregister() }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { signal.check(); return null to null }
        }
    }
}
