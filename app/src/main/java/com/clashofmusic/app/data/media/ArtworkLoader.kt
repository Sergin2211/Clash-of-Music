package com.clashofmusic.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.clashofmusic.app.data.downloader.DownloadSignal
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object ArtworkLoader {
    private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 2 * 1024 * 1024
    private val cache = LinkedHashMap<String, LoadedArtwork>(8, 0.75f, true)
    private var cachedBytes = 0

    data class LoadedArtwork(val sourceUrl: String, val bytes: ByteArray)

    /** Called on the download IO worker; retain JPEG bytes only, never decoded bitmaps. */
    fun resolve(candidates: List<String>, signal: DownloadSignal? = null): LoadedArtwork? {
        for (candidate in candidates.map(String::trim).filter(String::isNotEmpty).distinct()) {
            signal?.check()
            synchronized(cache) { cache[candidate] }?.let { return it }
            try {
                val loaded = normalize(read(candidate, signal) ?: continue) ?: continue
                signal?.check()
                val result = LoadedArtwork(candidate, loaded)
                synchronized(cache) {
                    cache.put(candidate, result)?.let { cachedBytes -= it.bytes.size }
                    cachedBytes += loaded.size
                    while (cache.size > 8 || cachedBytes > MAX_CACHE_BYTES) {
                        val oldest = cache.entries.iterator()
                        cachedBytes -= oldest.next().value.bytes.size
                        oldest.remove()
                    }
                }
                return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                signal?.check()
                Log.w("ArtworkLoader", "No se pudo leer una portada; se intentará la siguiente", error)
            }
        }
        return null
    }

    fun fetchArtworkBytes(thumbnailUrl: String?): ByteArray? = resolve(listOfNotNull(thumbnailUrl))?.bytes

    private fun read(location: String, signal: DownloadSignal?): ByteArray? {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            val connection = URL(location).openConnection() as HttpURLConnection
            val unregister = signal?.onCancel { connection.disconnect() }
            try {
                signal?.check()
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 ClashOfMusic")
                if (connection.responseCode !in 200..299 || connection.contentLengthLong > MAX_INPUT_BYTES) return null
                return connection.inputStream.use { readBounded(it, signal) }
            } finally { unregister?.invoke(); connection.disconnect() }
        }
        if (location.startsWith("file://") || location.startsWith("/")) {
            val file = File(location.removePrefix("file://"))
            if (file.length() > MAX_INPUT_BYTES) return null
            return file.inputStream().use { readBounded(it, signal) }
        }
        return null
    }

    private fun readBounded(input: InputStream, signal: DownloadSignal?): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            signal?.check()
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_INPUT_BYTES) { "La portada supera el límite de lectura" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun normalize(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..50000 || bounds.outHeight !in 1..50000) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 500) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var scaled = bitmap
        try {
            val largest = maxOf(bitmap.width, bitmap.height)
            if (largest > 500) scaled = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * 500L / largest).toInt().coerceAtLeast(1),
                (bitmap.height * 500L / largest).toInt().coerceAtLeast(1), true)
            val out = ByteArrayOutputStream()
            return if (scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) out.toByteArray() else null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }
}
