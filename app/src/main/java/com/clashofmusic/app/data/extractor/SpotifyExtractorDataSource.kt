package com.clashofmusic.app.data.extractor

import android.util.Log
import com.clashofmusic.app.domain.model.PlaylistSource
import com.clashofmusic.app.domain.model.RemotePlaylistInfo
import com.clashofmusic.app.domain.model.RemoteTrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SpotifyExtractorDataSource {

    companion object {
        private const val TAG = "SpotifyExtractor"
        private val NEXT_DATA_REGEX = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
        private val SPOTIFY_URL_REGEX = Pattern.compile("https?://open\\.spotify\\.com/(?:intl-[a-zA-Z]{2,5}/)?(playlist|album)/([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE)
        private val SPOTIFY_URI_REGEX = Pattern.compile("spotify:(playlist|album):([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isSpotifyUrl(url: String): Boolean {
        val trimmed = url.trim()
        return (trimmed.contains("spotify.com") && (trimmed.contains("/playlist") || trimmed.contains("/album"))) ||
                trimmed.startsWith("spotify:playlist:") ||
                trimmed.startsWith("spotify:album:") ||
                trimmed.contains("spotify.link")
    }

    private fun resolveRedirectIfShortened(url: String): String {
        if (!url.contains("spotify.link")) return url
        return try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (_: Exception) {
            url
        }
    }

    suspend fun extractPlaylist(url: String): Result<RemotePlaylistInfo> = withContext(Dispatchers.IO) {
        try {
            val resolvedUrl = resolveRedirectIfShortened(url.trim())
            val (type, id) = parseTypeAndId(resolvedUrl)
                ?: return@withContext Result.failure(IllegalArgumentException("Enlace de Spotify no válido. Debe ser una playlist o álbum."))

            val embedUrl = "https://open.spotify.com/embed/$type/$id?utm_source=generator"
            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Error al conectar con Spotify (HTTP ${response.code})"))
                }

                val html = response.body?.string().orEmpty()
                val matcher = NEXT_DATA_REGEX.matcher(html)

                if (!matcher.find()) {
                    return@withContext Result.failure(Exception("No se pudieron extraer los metadatos de la playlist/álbum de Spotify."))
                }

                val jsonStr = matcher.group(1) ?: return@withContext Result.failure(Exception("Datos vacíos en Spotify."))
                val rootJson = JSONObject(jsonStr)

                val stateData = rootJson.optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?: return@withContext Result.failure(Exception("Estructura de datos no encontrada en Spotify."))

                val entity = stateData.optJSONObject("entity")
                    ?: return@withContext Result.failure(Exception("Información de la entidad no disponible."))

                val title = entity.optString("title", if (type == "album") "Álbum de Spotify" else "Playlist de Spotify")
                val author = entity.optString("subtitle", entity.optString("subtitle_name", "Spotify"))
                val isAlbum = type == "album" || entity.optString("type").equals("album", ignoreCase = true)

                val coverUrl = SpotifyArtwork.bestUrl(entity)

                val trackListArray = entity.optJSONArray("trackList") ?: JSONArray()
                val tracks = mutableListOf<RemoteTrackItem>()

                for (i in 0 until trackListArray.length()) {
                    val item = trackListArray.getJSONObject(i)
                    val trackTitle = item.optString("title")
                    val trackArtist = item.optString("subtitle", author)
                    val durationMs = item.optLong("duration", 0L)
                    val uri = item.optString("uri")
                    val trackWebUrl = if (uri.isNotEmpty()) "https://open.spotify.com/track/${uri.substringAfterLast(":")}" else null

                    tracks.add(
                        RemoteTrackItem(
                            title = trackTitle,
                            artist = trackArtist,
                            album = if (isAlbum) title else null,
                            durationSeconds = durationMs / 1000L,
                            directUrl = trackWebUrl,
                            thumbnailUrl = coverUrl
                        )
                    )
                }

                Result.success(
                    RemotePlaylistInfo(
                        id = id,
                        title = title,
                        author = author,
                        description = "Importado de Spotify: $title ($author)",
                        thumbnailUrl = coverUrl,
                        trackCount = tracks.size,
                        source = PlaylistSource.SPOTIFY,
                        isAlbum = isAlbum,
                        tracks = tracks
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al extraer de Spotify: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseTypeAndId(url: String): Pair<String, String>? {
        val urlMatcher = SPOTIFY_URL_REGEX.matcher(url)
        if (urlMatcher.find()) {
            return Pair(urlMatcher.group(1) ?: "", urlMatcher.group(2) ?: "")
        }

        val uriMatcher = SPOTIFY_URI_REGEX.matcher(url)
        if (uriMatcher.find()) {
            return Pair(uriMatcher.group(1) ?: "", uriMatcher.group(2) ?: "")
        }

        return null
    }
}
