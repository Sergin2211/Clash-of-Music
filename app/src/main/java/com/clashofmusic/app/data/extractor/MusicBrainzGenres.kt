package com.clashofmusic.app.data.extractor

import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface GenreLookup { suspend fun lookup(song: DownloadedSong): List<GenreAssignment> }
private class GenreHttpError(val status: Int, val waitMs: Long) : java.io.IOException("MusicBrainz no disponible ($status). Inténtalo más tarde.")

class MusicBrainzGenres(private val cache: File,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()) : GenreLookup {
    private val requests = Mutex()
    private var nextRequest = 0L
    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://musicbrainz.org/ws/2/$path".toHttpUrl().newBuilder().addQueryParameter("fmt", "json").apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cache, "$hash.json")
        requests.withLock {
            if (file.isFile && System.currentTimeMillis() - file.lastModified() < 30L * 86400000) {
                runCatching { JSONObject(file.readText()) }.getOrNull()?.let { return@withLock it }
            }
            var json: JSONObject? = null
            var attempt = 0
            while (json == null) {
                val wait = nextRequest - System.nanoTime() / 1000000
                if (wait > 0) delay(wait)
                nextRequest = System.nanoTime() / 1000000 + 1100
                try { json = fetch(url) }
                catch (busy: GenreHttpError) {
                    if (busy.status !in setOf(429, 503) || ++attempt >= 3 || busy.waitMs > 120000) throw busy
                    delay(maxOf(busy.waitMs, 4000L * attempt))
                }
            }
            check(cache.isDirectory || cache.mkdirs())
            // Cache is disposable; incomplete writes are ignored and fetched again.
            file.writeText(json.toString())
            json
        }
    }
    private suspend fun fetch(url: HttpUrl): JSONObject = JSONObject(suspendCancellableCoroutine<String> { continuation ->
                val call = client.newCall(Request.Builder().url(url)
                    .header("User-Agent", "ClashOfMusic/${com.clashofmusic.app.BuildConfig.VERSION_NAME} (Android local-library genre lookup)")
                    .header("Accept", "application/json").build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
                    override fun onResponse(call: Call, response: Response) {
                        try { response.use {
                            if (!it.isSuccessful) throw GenreHttpError(it.code, (it.header("Retry-After")?.toLongOrNull() ?: 0L).coerceAtLeast(0).coerceAtMost(3600) * 1000)
                            val body = it.body?.string() ?: error("Respuesta vacía")
                            if (continuation.isActive) continuation.resume(body)
                        } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                })
            })
    private fun rows(json: JSONObject, key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    private suspend fun artist(id: String): JSONObject {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return get("artist/$id", mapOf("inc" to "genres+aliases"))
    }
    private fun artistMatches(json: JSONObject, name: String): Boolean {
        val names = listOf(json.optString("name"), json.optString("sort-name")) + rows(json, "aliases").map { it.optString("name") }
        return names.any { GenreNames.key(it) == GenreNames.key(name) }
    }
    private fun genres(json: JSONObject, origin: GenreOrigin): List<GenreAssignment> = rows(json, "genres")
        .filter { it.optInt("count", 1) > 0 }.sortedByDescending { it.optInt("count", 1) }
        .mapNotNull { row -> GenreNames.canonical(row.optString("name"))?.let { GenreAssignment(it, origin, json.optString("id"),
            (if (origin == GenreOrigin.ARTIST) json.optString("name") else json.optString("title")).take(300)) } }.take(12)

    override suspend fun lookup(song: DownloadedSong): List<GenreAssignment> {
        val originalArtist = song.artist.replace(Regex("(?i)(\\s*-\\s*Topic|VEVO)$"), "").trim()
        val artistName = GenreIdentity.lookupArtist(song.title, originalArtist)
        if (artistName.isBlank() || GenreNames.key(artistName) in setOf("unknown", "desconocido", "various artists", "youtube")) return emptyList()
        val title = GenreIdentity.cleanTitle(song.title, artistName)
        val query = "recording:\"${title.replace('"', ' ')}\" AND artist:\"${artistName.replace('"', ' ')}\""
        val recordings = rows(get("recording/", mapOf("query" to query, "limit" to "5")), "recordings")
        var matchedArtist: JSONObject? = null
        for (recording in recordings) {
            if (!GenreIdentity.sameTitle(title, recording.optString("title"))) continue
            val credits = rows(recording, "artist-credit").mapNotNull { it.optJSONObject("artist") }
            val credited = credits.firstOrNull { artistMatches(it, artistName) } ?: continue
            matchedArtist = artist(credited.getString("id"))
            if (GenreIdentity.recordingMatches(song, title, recording)) {
                val details = get("recording/${recording.getString("id")}", mapOf("inc" to "genres"))
                val specific = genres(details, GenreOrigin.RECORDING)
                if (specific.isNotEmpty()) return specific
            }
            break
        }
        if (matchedArtist == null) {
            // An inferred title prefix alone can be reversed (song - artist). Require track evidence,
            // except explicit collaborations, which still remain only artist references.
            if (GenreNames.key(artistName) != GenreNames.key(originalArtist) && !GenreIdentity.explicitCollaboration(song.title)) return emptyList()
            val candidates = rows(get("artist/", mapOf("query" to "artist:\"${artistName.replace('"', ' ')}\"", "limit" to "5")), "artists")
                .filter { it.optInt("score") >= 95 && artistMatches(it, artistName) }
            // Homonymous artists are not interchangeable. Leave it unclassified if identity is ambiguous.
            if (candidates.size != 1) return emptyList()
            matchedArtist = artist(candidates.single().getString("id"))
        }
        return genres(matchedArtist, GenreOrigin.ARTIST)
    }
}

internal object GenreIdentity {
    private val separator = Regex("^(.{2,100}?)(?:\\s+[-–—]\\s+|-(?![0-9]))\\s*(.{2,})$")
    private val collaboration = Regex("(?i)\\s+(?:ft\\.?|feat\\.?|featuring|and|&|x)\\s+")
    private fun parts(title: String) = separator.matchEntire(java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).trim())
        ?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }
    fun explicitCollaboration(title: String) = parts(title)?.first?.let { collaboration.containsMatchIn(it) } == true
    fun lookupArtist(title: String, original: String): String {
        val right = parts(stripAnnotations(title))?.second
        if (right != null && GenreNames.key(right) == GenreNames.key(original)) return original
        return artistFromTitle(title) ?: original
    }
    fun artistFromTitle(title: String): String? {
        val normalized = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).trim()
        val bracket = Regex("^【([^】]{2,70})】").find(normalized)?.groupValues?.get(1)
        val dash = parts(normalized)?.first?.split(collaboration)?.firstOrNull()
        return (bracket ?: dash)?.trim()?.takeUnless { candidate ->
            Regex("(?i)\\b(live|performance|lyrics?|official|video|cover|audio|mv)\\b").containsMatchIn(candidate)
        }
    }
    private fun stripAnnotations(title: String): String = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC)
        .replace(Regex("【[^】]*】|\\[(?i:official|lyrics?|audio|video|mv)[^]]*]"), " ")
        .replace(Regex("(?i)\\((official|lyrics?|audio|video|mv|subtitulado|letra|ウタ|uta from|from one piece)[^)]*\\)"), " ")
        .replace(Regex("(?i)\\b(official music video|official video|official audio|lyrics|mv)\\b"), " ")
        .trim()
    fun cleanTitle(title: String, artist: String): String {
        val value = stripAnnotations(title)
        val pair = parts(value)
        val cleaned = when {
            pair == null -> value
            GenreNames.key(pair.second) == GenreNames.key(artist) -> pair.first
            GenreNames.key(pair.first.split(collaboration).first()) == GenreNames.key(artist) -> pair.second
            else -> value
        }
        return cleaned.replace(Regex("(?i)\\s+(ft\\.?|feat\\.?|featuring)\\s+.*$"), "").trim()
    }
    fun sameTitle(title: String, candidate: String): Boolean {
        val a = GenreNames.key(title); val b = GenreNames.key(candidate)
        if (a.isEmpty() || b.isEmpty() || a.length > 300 || b.length > 300) return false
        val distance = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, c -> var diagonal = distance[0]; distance[0] = i + 1
            b.forEachIndexed { j, d -> val old = distance[j + 1]; distance[j + 1] = minOf(distance[j + 1] + 1, distance[j] + 1, diagonal + if (c == d) 0 else 1); diagonal = old } }
        return 1.0 - distance.last().toDouble() / maxOf(a.length, b.length) >= .85
    }
    fun recordingMatches(song: DownloadedSong, title: String, candidate: JSONObject): Boolean {
        if (!sameTitle(title, candidate.optString("title"))) return false
        val marker = Regex("(?i)\\b(live|concert|remix|instrumental|karaoke|cover)\\b")
        val requestedVersion = marker.findAll(song.title).map { it.value.lowercase() }.toSet()
        val foundVersion = marker.findAll(candidate.optString("title") + " " + candidate.optString("disambiguation")).map { it.value.lowercase() }.toSet()
        if (requestedVersion != foundVersion) return false
        val seconds = candidate.optLong("length") / 1000
        return song.durationSeconds <= 0 || seconds <= 0 || kotlin.math.abs(song.durationSeconds - seconds) <= maxOf(8L, song.durationSeconds / 12)
    }
}
