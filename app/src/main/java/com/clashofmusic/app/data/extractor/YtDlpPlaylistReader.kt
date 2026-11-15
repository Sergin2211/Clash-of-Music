package com.clashofmusic.app.data.extractor

import android.content.Context
import com.clashofmusic.app.data.downloader.YoutubeDLManager
import com.clashofmusic.app.domain.model.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Uses the bundled extractor for playlist metadata only, without fetching audio or thumbnails. */
class YtDlpPlaylistReader(private val context: Context) {
    suspend fun read(url: String): RemotePlaylistInfo = withContext(Dispatchers.IO) {
        YoutubeDLManager.ensureInitialized(context)
        val processId = "playlist-${UUID.randomUUID()}"
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist"); addOption("--dump-single-json"); addOption("--skip-download")
            addOption("--ignore-errors"); addOption("--socket-timeout", "15"); addOption("--retries", "2")
        }
        try {
            val output = runInterruptible { YoutubeDL.getInstance().execute(request, processId).out }
            ensureActive()
            decode(output, url)
        } finally { runCatching { YoutubeDL.getInstance().destroyProcessById(processId) } }
    }

    companion object {
        internal fun decode(text: String, url: String): RemotePlaylistInfo {
            val json = JSONObject(text)
            val album = url.contains("list=OLAK5uy")
            val title = json.optString("title").ifBlank { "Playlist de YouTube" }
            val entries = json.getJSONArray("entries")
            val tracks = (0 until entries.length()).mapNotNull { index ->
                val item = entries.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val name = item.optString("title")
                if (!id.matches(Regex("[a-zA-Z0-9_-]{11}")) || name.isBlank() || name in setOf("[Private video]", "[Deleted video]")) return@mapNotNull null
                val thumbs = item.optJSONArray("thumbnails")
                val thumbnail = thumbs?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .maxByOrNull { it.optLong("width") * it.optLong("height") }?.optString("url") }
                RemoteTrackItem(name, item.optString("artist").ifBlank { item.optString("channel").ifBlank { item.optString("uploader") } },
                    if (album) title else null, item.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0,
                    "https://www.youtube.com/watch?v=$id", thumbnail)
            }
            require(tracks.isNotEmpty()) { "No se encontraron canciones disponibles en la playlist." }
            val missing = (maxOf(json.optInt("playlist_count"), entries.length()) - tracks.size).coerceAtLeast(0)
            return RemotePlaylistInfo(json.optString("id"), title,
                json.optString("uploader").ifBlank { json.optString("channel").ifBlank { "YouTube" } },
                json.optString("description").takeIf { it.isNotBlank() }, tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                tracks.size, PlaylistSource.YOUTUBE, album, tracks,
                if (missing > 0) unavailablePlaylistWarning(missing.toLong(), tracks.size) else null)
        }
    }
}

internal fun unavailablePlaylistWarning(missing: Long, available: Int): String =
    (if (missing == 1L) "1 entrada no está disponible o no se pudo leer."
    else "$missing entradas no están disponibles o no se pudieron leer.") +
        " Puedes descargar las $available canciones disponibles."
