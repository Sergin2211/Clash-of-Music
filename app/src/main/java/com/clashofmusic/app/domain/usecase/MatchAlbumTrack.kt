package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.SongItem
import com.clashofmusic.app.domain.repository.MusicRepository
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrackMatchRequest(val title: String, val artist: String, val durationSeconds: Long)
enum class TrackMatchIssue(val message: String) {
    NO_RESULTS("No se encontraron resultados descargables; vuelve a intentarlo"),
    SEARCH_FAILED("No se pudo consultar la fuente; vuelve a intentarlo")
}
data class TrackMatchResult(val song: SongItem? = null, val issue: TrackMatchIssue? = null)

/** Rank available results. Metadata differences lower priority but never block a usable result. */
object AlbumTrackMatcher {
    private val latinMarks = Regex("(?<=\\p{IsLatin})\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")
    private val channelSuffix = Regex("\\s+(?:topic|official|official channel|official music|music channel)$")
    private val artistSuffix = Regex("(?:vevo|official)$")
    private val latinOrDigit = Regex("[\\p{IsLatin}\\p{N}]")
    private val latin = Regex("[\\p{IsLatin}]")
    private val brackets = Regex("[\\[【(（]([^\\]】)）]*)[\\]】)）]")
    private val neutralLabel = Regex("^(?:official|audio|lyrics?|lyric video|music video|visualizer|mv|hd|hq|4k)(?: |$)")
    private val fromLabel = Regex("(?:^| )from(?: |$)")
    private val featuredCredit = Regex("\\b(?:ft|featuring)\\b")
    private val videoSuffix = Regex("\\s+(?:official (?:music video|video|audio|mv|visualizer)|lyrics?|lyric video|music video|visualizer|mv)(?: hd| 4k)?$")
    private val officialAudio = Regex("(?i)official audio")
    private val longMix = Regex("(?i)full album|complete album|1 hour|10 hours")

    private fun normalize(value: String) = Normalizer.normalize(
        Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            .replace(latinMarks, ""), Normalizer.Form.NFC)
        .replace(separators, " ").trim().replace(whitespace, " ")

    private val versionPatterns = listOf(
        "cover" to "\\b(?:cover|covered|カバー|歌ってみた|version francaise|french version|english version|spanish version|espanol)\\b",
        "live" to "\\b(?:live|livestream|en vivo|en directo|concerts?|concertos?|conciertos?|festival|tour|the first take)\\b|ライブ|ライヴ|生演奏|演奏会|公演",
        "remix" to "\\b(?:remix|mix|rework|mashup)\\b",
        "instrumental" to "\\b(?:instrumental|karaoke|off vocal|backing track|piano cover)\\b",
        "acoustic" to "\\b(?:acoustic|acustico|unplugged)\\b",
        "speed" to "\\b(?:sped up|speed up|slowed|nightcore|reverb|8d|bass boosted)\\b",
        "short" to "\\b(?:short|shorts|tv size|anime size|edit|radio edit)\\b",
        "remaster" to "\\b(?:remaster|remastered)\\b",
        "reaction" to "\\b(?:reaction|reactions|reacts|reacting|reaccion|reacciona|reaccionando|about songwriting)\\b"
    ).map { (name, pattern) -> name to Regex(pattern) }
    private val reactionPattern = versionPatterns.first { it.first == "reaction" }.second
    private fun versions(value: String): Set<String> {
        val normalized = normalize(value)
        return versionPatterns.filter { it.second.containsMatchIn(normalized) }.map { it.first }.toSet()
    }

    private fun artist(value: String): String = normalize(value)
        .replace(channelSuffix, "")
        .replace(artistSuffix, "").trim()

    private fun sameArtist(expected: String, actual: String): Boolean {
        val a = artist(expected)
        val b = artist(actual)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        fun bilingualName(short: String, long: String): Boolean {
            val match = Regex("(?:^| )${Regex.escape(short)}(?: |$)").find(long) ?: return false
            val extra = long.removeRange(match.range).trim()
            // Accept an accompanying localized name, not "ZUTOMAYO fans" or "Ado covers".
            return extra.any { it.isLetter() } && !latinOrDigit.containsMatchIn(extra)
        }
        return bilingualName(a, b) || bilingualName(b, a)
    }

    private fun sourceVersions(song: SongItem): Set<String> = versions(song.sourceTitle ?: song.title) +
        if (reactionPattern.containsMatchIn(normalize(song.channelName.orEmpty()))) setOf("reaction") else emptySet()

    private fun baseTitle(value: String, artistName: String): String = title(
        value.replace(brackets) { part ->
            if (versions(part.groupValues[1]).isNotEmpty()) " " else part.value
        }, artistName)

    private fun title(value: String, artistName: String, stripSuffix: Boolean = true): String {
        val wantedArtist = artist(artistName)
        var text = value.replace(brackets) { part ->
            val content = normalize(part.groupValues[1])
            val neutral = content == wantedArtist ||
                neutralLabel.containsMatchIn(content) ||
                fromLabel.containsMatchIn(content)
            if (neutral && versions(content).isEmpty()) " " else part.value
        }
        text = normalize(text)
        if (wantedArtist.isNotBlank() && text.startsWith("$wantedArtist ")) text = text.removePrefix("$wantedArtist ")
        // Featured performers identify a different recording unless the requested title
        // contains the same credit. Never discard them as decorative video metadata.
        text = text.replace(featuredCredit, "feat")
        if (stripSuffix) text = text.replace(videoSuffix, "")
        return text.trim()
    }

    private fun similarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        val a = left.split(" ").toSet()
        val b = right.split(" ").toSet()
        return 2.0 * a.intersect(b).size / (a.size + b.size)
    }

    private fun containsTitle(full: String, wanted: String): Boolean = wanted.isNotBlank() &&
        Regex("(?:^| )${Regex.escape(wanted)}(?: |$)").containsMatchIn(full) &&
        ("feat" !in full.split(" ") || "feat" in wanted.split(" "))

    fun isStrongMatch(request: TrackMatchRequest, song: SongItem): Boolean {
        val expected = title(request.title, request.artist, stripSuffix = false)
        val exactTitle = title(song.title, request.artist) == expected || title(song.title, request.artist, false) == expected ||
            containsTitle(title(song.title, request.artist), expected)
        val durationMatches = request.durationSeconds > 0 && song.durationSeconds > 0 &&
            abs(song.durationSeconds - request.durationSeconds) <= (request.durationSeconds * 0.04).toLong().coerceIn(4L, 12L)
        return sameArtist(request.artist, song.channelName ?: song.artist) &&
            exactTitle && sourceVersions(song) == versions(request.title) && durationMatches
    }

    fun match(request: TrackMatchRequest, candidates: List<SongItem>): TrackMatchResult {
        val targetArtist = artist(request.artist)
        val targetTitle = title(request.title, request.artist, stripSuffix = false)
        val expectedVersions = versions(request.title)
        val targetBaseTitle = baseTitle(request.title, request.artist)
        data class Ranked(val song: SongItem, val plausible: Boolean, val versionDistance: Int, val score: Double)
        val ranked = candidates.mapIndexedNotNull { index, song ->
            if (song.id.isBlank() || song.url.isBlank()) return@mapIndexedNotNull null
            val actualArtist = artist(song.channelName ?: song.artist)
            val matchingArtist = sameArtist(request.artist, song.channelName ?: song.artist)
            val actualTitle = title(song.title, request.artist)
            val rawTitle = title(song.title, request.artist, stripSuffix = false)
            var score = maxOf(similarity(targetTitle, actualTitle), similarity(targetTitle, rawTitle)) * 90
            score += if (matchingArtist) 100.0
                else if (actualArtist.isNotBlank() && targetArtist.isNotBlank()) -35.0 else 0.0
            if (request.durationSeconds > 0 && song.durationSeconds > 0) {
                val delta = abs(song.durationSeconds - request.durationSeconds).toDouble()
                score += 40 * (1 - (delta / maxOf(10.0, request.durationSeconds * 0.3)).coerceAtMost(1.0))
                if (song.durationSeconds.toDouble() / request.durationSeconds !in 0.55..1.8) score -= 80
            }
            val actualVersions = sourceVersions(song)
            val versionDistance = (actualVersions - expectedVersions).size + (expectedVersions - actualVersions).size
            score += if (actualVersions == expectedVersions) 15.0
                else -25.0 * versionDistance
            if ("feat" in actualTitle.split(" ") && "feat" !in targetTitle.split(" ")) score -= 75
            if ((song.channelName ?: song.artist).endsWith(" - Topic", true) || officialAudio.containsMatchIn(song.sourceTitle ?: song.title)) score += 3
            if (longMix.containsMatchIn(song.title)) score -= 100
            // Stable search relevance resolves equally plausible candidates automatically.
            score += (12 - index * 0.25).coerceAtLeast(0.0)
            val baseSimilarity = maxOf(similarity(targetBaseTitle, baseTitle(song.title, request.artist)),
                similarity(targetTitle, actualTitle))
            val durationPlausible = request.durationSeconds <= 0 || song.durationSeconds <= 0 ||
                song.durationSeconds.toDouble() / request.durationSeconds in 0.65..1.4
            val differentScripts = latin.containsMatchIn(targetTitle) !=
                latin.containsMatchIn(actualTitle)
            // Reject only a demonstrably unrelated recording: artist, title AND duration disagree.
            // Translations, unknown durations and alternate uploaders remain automatic fallbacks.
            if (!matchingArtist && !differentScripts && baseSimilarity == 0.0 && !durationPlausible)
                return@mapIndexedNotNull null
            val plausible = durationPlausible && (baseSimilarity >= 0.6 ||
                matchingArtist && (differentScripts || containsTitle(actualTitle, targetTitle)))
            Ranked(song, plausible, if (plausible) versionDistance else 0, score)
        }.sortedWith(compareByDescending<Ranked> { it.plausible }
            .thenBy { it.versionDistance }.thenByDescending { it.score }).distinctBy { it.song.id }
        return ranked.firstOrNull()?.let { TrackMatchResult(song = it.song) }
            ?: TrackMatchResult(issue = TrackMatchIssue.NO_RESULTS)
    }

}

class ResolveAlbumTrack(
    private val music: MusicRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    suspend fun resolve(request: TrackMatchRequest, excludedIds: Set<String> = emptySet()): TrackMatchResult = withContext(computationDispatcher) {
        val candidates = mutableListOf<SongItem>()
        var searchesSucceeded = 0
        for (query in listOf("${request.artist} - ${request.title}", "${request.artist} ${request.title} official audio")) {
            currentCoroutineContext().ensureActive()
            val result = try { music.search(query) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            result.getOrNull()?.let { searchesSucceeded++; candidates.addAll(it.filterNot { song -> song.id in excludedIds }.take(30)) }
            currentCoroutineContext().ensureActive()
            val match = AlbumTrackMatcher.match(request, candidates)
            if (match.song != null && AlbumTrackMatcher.isStrongMatch(request, match.song)) return@withContext match
        }
        if (searchesSucceeded == 0) TrackMatchResult(issue = TrackMatchIssue.SEARCH_FAILED)
            else AlbumTrackMatcher.match(request, candidates)
    }
}
