package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import com.clashofmusic.app.data.media.*
import com.clashofmusic.app.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Real image/HTTP/embedding failures, entirely inside private cache; no catalog changes. */
class ArtworkFallbackCheck(private val instrumentation: Instrumentation) {
    fun run() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "artwork_check_${UUID.randomUUID()}")
        val report = JSONObject()
        val checks = JSONArray()
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        var success = false
        try {
            check(directory.mkdirs())
            val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(166, 66, 104))
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            bitmap.recycle()
            thread(isDaemon = true, name = "artwork-fixtures") {
                while (!server.isClosed) try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                        val body = when (path) { "/cover" -> jpeg; "/html" -> "<html>Not an image</html>".toByteArray(); else -> byteArrayOf() }
                        val status = if (path == "/missing") "404 Not Found" else "200 OK"
                        val length = if (path == "/oversized") 10_000_000 else body.size
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                } catch (_: Exception) { }
            }
            val base = "http://127.0.0.1:${server.localPort}"
            val artwork = ArtworkLoader.resolve(listOf("$base/missing", "$base/html", "$base/cover")) ?: error("Fallback failed")
            check(artwork.sourceUrl == "$base/cover")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(artwork.bytes, 0, artwork.bytes.size, bounds)
            check(bounds.outWidth == 500 && bounds.outHeight == 250 && bounds.outMimeType == "image/jpeg")
            checks.put("404-and-invalid-image-fall-back-to-valid-album-art")
            checks.put("large-images-sample-and-normalize-to-bounded-jpeg")
            ArtworkLoader.resolve(listOf("$base/cover"))
            check(counts.getValue("/cover").get() == 1)
            checks.put("repeated-album-cover-reuses-jpeg-without-network-or-bitmap-decode")
            check(ArtworkLoader.resolve(listOf("$base/oversized", "$base/cover")) != null)
            checks.put("oversized-image-response-is-rejected-before-allocation")
            val cancelled = DownloadSignal().apply { cancel() }
            try { ArtworkLoader.resolve(listOf("$base/cover"), cancelled); error("Cancellation was swallowed") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            checks.put("cancelled-download-does-not-fetch-or-reuse-artwork")
            val processor = NativeAudioProcessor(context)
            runBlocking {
                for ((extension, codec) in listOf("mp3" to "libmp3lame", "m4a" to "aac", "opus" to "libopus")) {
                    val file = File(directory, "private.$extension")
                    processor.run("libffmpeg.so", listOf("-v", "error", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
                        "-t", "0.3", "-c:a", codec, file.absolutePath), DownloadSignal())
                    NativeMetadataEditor(context).rewrite(file, null, artwork.bytes, true, DownloadSignal())
                    val retriever = MediaMetadataRetriever()
                    try { retriever.setDataSource(file.absolutePath); check(retriever.embeddedPicture?.isNotEmpty() == true) }
                    finally { retriever.release() }
                    checks.put("resolved-album-cover-is-embedded-in-$extension")
                }
                val album = ServiceLocator.musicRepository.extractRemotePlaylist(
                    "https://music.youtube.com/playlist?list=OLAK5uy_l8fQyUWZfOO25E9HeDeeJCIDm0tsxwguc").getOrThrow()
                check(album.isAlbum && album.tracks.isNotEmpty())
                for (track in album.tracks) {
                    val id = ServiceLocator.extractorDataSource.extractVideoId(track.directUrl.orEmpty()).orEmpty()
                    check(ArtworkLoader.resolve(ArtworkCandidates.forDownload(album.thumbnailUrl, track.thumbnailUrl,
                        null, id, true)) != null)
                }
                report.put("albumTracksRead", album.tracks.size)
                checks.put("previous-real-youtube-music-album-link-resolves-covers-without-downloading-audio")
                val spotify = ServiceLocator.musicRepository.extractRemotePlaylist(
                    "https://open.spotify.com/album/5lzzogUrW0KKsmnthNNIfP").getOrThrow()
                check(spotify.isAlbum && spotify.tracks.isNotEmpty())
                val spotifyCover = ArtworkLoader.resolve(listOfNotNull(spotify.thumbnailUrl)) ?: error("Spotify album cover unavailable")
                for (track in spotify.tracks) {
                    val cover = ArtworkLoader.resolve(ArtworkCandidates.forDownload(spotify.thumbnailUrl,
                        track.thumbnailUrl, null, "", true)) ?: error("Spotify track lost its album cover")
                    check(cover.sourceUrl == spotifyCover.sourceUrl)
                }
                report.put("spotifyAlbum", spotify.title).put("spotifyTracksChecked", spotify.tracks.size)
                checks.put("reported-spotify-album-retains-its-real-cover-for-every-track")

            }
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            server.close()
            report.put("fixturesRemoved", directory.deleteRecursively())
        }
        report.put("success", success).put("checks", checks)
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
