package com.clashofmusic.app.data.extractor

import android.util.Log
import com.clashofmusic.app.domain.model.AlbumItem
import com.clashofmusic.app.domain.model.AlbumTrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val genre: String?,
    val year: String?,
    val artworkUrl: String?
)

class AppleMusicDataSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "AppleMusicDataSource"
        private const val BASE_SEARCH_URL = "https://itunes.apple.com/search"
        private const val BASE_LOOKUP_URL = "https://itunes.apple.com/lookup"
    }

    /**
     * Searches for Albums using Deezer (universal international catalog) and Apple Music / iTunes
     */
    suspend fun searchAlbums(query: String, limit: Int = 15): List<AlbumItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<AlbumItem>()

        // 1. Query Deezer API (Instant, no rate limit, full global catalog)
        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val dzUrl = "https://api.deezer.com/search/album?q=$encoded&limit=$limit"
            val req = Request.Builder()
                .url(dzUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) {
                        val json = JSONObject(body)
                        val data = json.optJSONArray("data")
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val item = data.optJSONObject(i) ?: continue
                                val id = item.optLong("id", -1L)
                                val title = item.optString("title", "")
                                val artist = item.optJSONObject("artist")?.optString("name", "") ?: ""
                                if (id <= 0 || title.isBlank()) continue

                                val cover = item.optString("cover_xl").ifBlank {
                                    item.optString("cover_big").ifBlank {
                                        item.optString("cover_medium")
                                    }
                                }.ifBlank { null }
                                val nbTracks = item.optInt("nb_tracks", 0)
                                val recordType = item.optString("record_type", "album")
                                val genre = if (recordType.isNotBlank() && !recordType.equals("album", ignoreCase = true)) {
                                    recordType.uppercase()
                                } else "Álbum"

                                results.add(
                                    AlbumItem(
                                        id = "dz_$id",
                                        title = title,
                                        artist = artist,
                                        genre = genre,
                                        year = null,
                                        trackCount = nbTracks,
                                        thumbnailUrl = cover
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deezer album search warning: ${e.message}")
        }

        // 2. Query Apple Music / iTunes to complement results
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "$BASE_SEARCH_URL?term=$encodedQuery&media=music&entity=album&limit=$limit"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (bodyStr != null) {
                        val json = JSONObject(bodyStr)
                        val itunesResults = json.optJSONArray("results")
                        if (itunesResults != null) {
                            for (i in 0 until itunesResults.length()) {
                                val item = itunesResults.optJSONObject(i) ?: continue
                                val collectionId = item.optLong("collectionId", -1L)
                                val collectionName = item.optString("collectionName", "")
                                val artistName = item.optString("artistName", "")
                                if (collectionId <= 0 || collectionName.isBlank()) continue

                                // Deduplicate if already present from Deezer
                                if (results.any { it.title.equals(collectionName, ignoreCase = true) && it.artist.equals(artistName, ignoreCase = true) }) {
                                    continue
                                }

                                val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                                val releaseDate = item.optString("releaseDate")
                                val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                                val trackCount = item.optInt("trackCount", 0)
                                val rawArtwork = item.optString("artworkUrl100")
                                val highResArtwork = if (rawArtwork.isNotBlank()) {
                                    rawArtwork.replace("100x100bb", "600x600bb")
                                } else null

                                results.add(
                                    AlbumItem(
                                        id = collectionId.toString(),
                                        title = collectionName,
                                        artist = artistName,
                                        genre = genre,
                                        year = year,
                                        trackCount = trackCount,
                                        thumbnailUrl = highResArtwork
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "iTunes album search warning: ${e.message}")
        }

        results
    }

    /**
     * Retrieves track list for an album using Deezer ID or iTunes collectionId
     */
    suspend fun getAlbumTracks(collectionId: String): List<AlbumTrackItem> = withContext(Dispatchers.IO) {
        if (collectionId.startsWith("dz_")) {
            val dzId = collectionId.removePrefix("dz_")
            return@withContext getDeezerAlbumTracks(dzId)
        }

        try {
            val url = "$BASE_LOOKUP_URL?id=$collectionId&entity=song"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()

                val json = JSONObject(bodyStr)
                val results = json.optJSONArray("results") ?: return@withContext emptyList()

                val tracks = mutableListOf<AlbumTrackItem>()
                var albumName: String? = null
                var albumThumb: String? = null

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val wrapperType = item.optString("wrapperType")

                    if (wrapperType == "collection") {
                        albumName = item.optString("collectionName").takeIf { it.isNotBlank() }
                        albumThumb = item.optString("artworkUrl100").replace("100x100bb", "600x600bb").takeIf { it.isNotBlank() }
                    } else if (wrapperType == "track") {
                        val trackName = item.optString("trackName")
                        val artistName = item.optString("artistName")
                        val trackNumber = item.optInt("trackNumber", tracks.size + 1)
                        val durationMs = item.optLong("trackTimeMillis", 0L)
                        val trackArtwork = item.optString("artworkUrl100").replace("100x100bb", "600x600bb").takeIf { it.isNotBlank() } ?: albumThumb

                        if (trackName.isNotBlank()) {
                            tracks.add(
                                AlbumTrackItem(
                                    trackNumber = trackNumber,
                                    title = trackName,
                                    artist = artistName,
                                    album = item.optString("collectionName").takeIf { it.isNotBlank() } ?: albumName,
                                    durationSeconds = durationMs / 1000L,
                                    thumbnailUrl = trackArtwork
                                )
                            )
                        }
                    }
                }
                tracks
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching album tracks: ${e.message}", e)
            emptyList()
        }
    }

    private fun getDeezerAlbumTracks(albumId: String): List<AlbumTrackItem> {
        try {
            var albumName = ""
            var albumCover: String? = null
            var defaultArtist = ""

            val albumUrl = "https://api.deezer.com/album/$albumId"
            val req1 = Request.Builder()
                .url(albumUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req1).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()?.let { b ->
                        val j = JSONObject(b)
                        albumName = j.optString("title", "")
                        albumCover = j.optString("cover_xl").ifBlank {
                            j.optString("cover_big").ifBlank {
                                j.optString("cover_medium")
                            }
                        }.ifBlank { null }
                        defaultArtist = j.optJSONObject("artist")?.optString("name", "") ?: ""
                    }
                }
            }

            val tracksUrl = "https://api.deezer.com/album/$albumId/tracks?limit=100"
            val req2 = Request.Builder()
                .url(tracksUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req2).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val bodyStr = resp.body?.string() ?: return emptyList()
                val j = JSONObject(bodyStr)
                val data = j.optJSONArray("data") ?: return emptyList()
                val list = mutableListOf<AlbumTrackItem>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val title = item.optString("title", "")
                    if (title.isBlank()) continue
                    val artistName = item.optJSONObject("artist")?.optString("name", defaultArtist) ?: defaultArtist
                    val trackPos = item.optInt("track_position", i + 1)
                    val dur = item.optLong("duration", 0L)
                    list.add(
                        AlbumTrackItem(
                            trackNumber = trackPos,
                            title = title,
                            artist = artistName,
                            album = albumName.ifBlank { null },
                            durationSeconds = dur,
                            thumbnailUrl = albumCover
                        )
                    )
                }
                return list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Deezer album tracks: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Identifies album, genre, year and high-res artwork for a song title and artist.
     * Never queries when artist is unknown or generic, verifies candidate matches,
     * and filters out single/EP releases to avoid bogus 1-song albums.
     */
    suspend fun fetchTrackMetadata(title: String, artist: String): TrackMetadata? = withContext(Dispatchers.IO) {
        val trimmedArtist = artist.trim()
        val isArtistUnknown = trimmedArtist.isBlank() ||
                trimmedArtist.equals("Desconocido", ignoreCase = true) ||
                trimmedArtist.equals("Unknown", ignoreCase = true) ||
                trimmedArtist.equals("<unknown>", ignoreCase = true)

        // Strict safeguard: never query Apple Music when artist is unknown to avoid false matches
        if (isArtistUnknown) {
            return@withContext null
        }

        val cleanTitle = cleanSearchTerm(title)
        val cleanArtist = cleanSearchTerm(trimmedArtist)
        val searchTerm = "$cleanTitle $cleanArtist".trim()
        if (searchTerm.isEmpty()) return@withContext null

        try {
            val encodedQuery = URLEncoder.encode(searchTerm, "UTF-8")
            val url = "$BASE_SEARCH_URL?term=$encodedQuery&media=music&entity=song&limit=5"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null

                val json = JSONObject(bodyStr)
                val results = json.optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) return@withContext null

                var bestMatch: JSONObject? = null
                val cleanArtistLower = cleanArtist.lowercase()
                val cleanTitleLower = cleanTitle.lowercase()

                // Find candidate matching artist and title
                for (i in 0 until results.length()) {
                    val candidate = results.optJSONObject(i) ?: continue
                    val candArtist = candidate.optString("artistName", "").lowercase()
                    val candTitle = candidate.optString("trackName", "").lowercase()

                    val artistMatch = candArtist.contains(cleanArtistLower) ||
                            cleanArtistLower.contains(candArtist) ||
                            hasWordOverlap(candArtist, cleanArtistLower)

                    val titleMatch = candTitle.contains(cleanTitleLower) ||
                            cleanTitleLower.contains(candTitle) ||
                            hasWordOverlap(candTitle, cleanTitleLower)

                    if (artistMatch && titleMatch) {
                        bestMatch = candidate
                        break
                    } else if (artistMatch && bestMatch == null) {
                        bestMatch = candidate
                    }
                }

                val item = bestMatch ?: return@withContext null
                val trackName = item.optString("trackName", title)
                val artistName = item.optString("artistName", artist)
                val rawCollection = item.optString("collectionName", "").trim()

                // Filter out Singles, EPs and track-titled collections to prevent fake 1-song albums
                val isSingleOrEp = rawCollection.endsWith(" - Single", ignoreCase = true) ||
                        rawCollection.endsWith("- Single", ignoreCase = true) ||
                        rawCollection.endsWith(" - EP", ignoreCase = true) ||
                        rawCollection.endsWith("- EP", ignoreCase = true) ||
                        rawCollection.contains("(Single)", ignoreCase = true) ||
                        rawCollection.contains("[Single]", ignoreCase = true) ||
                        rawCollection.equals(trackName, ignoreCase = true) ||
                        rawCollection.equals("$trackName - Single", ignoreCase = true)

                val albumName = if (isSingleOrEp) null else rawCollection.takeIf { it.isNotBlank() }
                val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                val releaseDate = item.optString("releaseDate")
                val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                val artwork = item.optString("artworkUrl100")
                    .replace("100x100bb", "600x600bb")
                    .takeIf { it.isNotBlank() }

                TrackMetadata(
                    title = trackName,
                    artist = artistName,
                    album = albumName,
                    genre = genre,
                    year = year,
                    artworkUrl = artwork
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Metadata lookup error: ${e.message}")
            null
        }
    }

    private fun hasWordOverlap(a: String, b: String): Boolean {
        val wordsA = a.split(Regex("[\\s,._\\-]+")).filter { it.length > 2 }.toSet()
        val wordsB = b.split(Regex("[\\s,._\\-]+")).filter { it.length > 2 }.toSet()
        return wordsA.intersect(wordsB).isNotEmpty()
    }

    private fun cleanSearchTerm(term: String): String {
        return term
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("(?i)official\\s*(video|audio|music\\s*video)?"), "")
            .replace(Regex("(?i)video\\s*oficial"), "")
            .replace(Regex("(?i)audio\\s*oficial"), "")
            .replace(Regex("(?i)letra"), "")
            .replace(Regex("(?i)lyrics"), "")
            .replace(Regex("(?i)feat\\.?\\s+.*"), "")
            .replace(Regex("(?i)ft\\.?\\s+.*"), "")
            .trim()
    }
}
