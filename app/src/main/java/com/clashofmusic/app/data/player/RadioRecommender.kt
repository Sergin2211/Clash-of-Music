package com.clashofmusic.app.data.player

import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.text.Normalizer
import java.util.Locale

interface RadioRecommendationSource {
    /** Only the music mix of this seed; no artist/genre searches or mixes of later tracks. */
    suspend fun related(seed: DownloadedSong): List<SongItem>
}

data class RadioMixChoice(val songId: String, val sourceRank: Int, val fetchedAt: Long)
data class RadioMixTrace(
    val seedId: String,
    val mixUrl: String?,
    val fetchedAt: Long,
    val candidateIds: List<String>,
    val selected: List<RadioMixChoice>,
    val message: String? = null,
    val recentChoices: List<RadioMixChoice> = selected
) {
    private fun rows(items: List<RadioMixChoice>) = org.json.JSONArray().apply {
        items.forEach { put(org.json.JSONObject().put("songId", it.songId).put("sourceRank", it.sourceRank).put("fetchedAt", it.fetchedAt)) }
    }
    fun json(): String = org.json.JSONObject().put("source", "YOUTUBE_MUSIC_MIX")
        .put("seedId", seedId).put("mixUrl", mixUrl ?: org.json.JSONObject.NULL).put("fetchedAt", fetchedAt)
        .put("candidateIds", org.json.JSONArray(candidateIds))
        .put("selected", rows(selected)).put("recentChoices", rows(recentChoices))
        .put("message", message ?: org.json.JSONObject.NULL).toString()
}

/** Keep YouTube's music-mix order. Diversity never imports tracks outside the initial seed's mix. */
class RadioRecommender(private val source: RadioRecommendationSource, private val onTrace: (RadioMixTrace) -> Unit = {}) {
    private data class CachedMix(val seedId: String, val fetchedAt: Long, val songs: List<SongItem>)
    private val lock = Any()
    private var cachedMix: CachedMix? = null
    private var epoch = 0L
    private val recent = linkedMapOf<String, RadioMixChoice>()
    @Volatile var lastTrace: RadioMixTrace? = null
        private set
    fun clear() = synchronized(lock) { epoch++; cachedMix = null; lastTrace = null; recent.clear() }

    suspend fun next(seed: DownloadedSong, history: List<DownloadedSong>, count: Int): List<DownloadedSong> {
        if (count <= 0) return emptyList()
        val generation = synchronized(lock) { epoch }
        val url = if (seed.songId.matches(Regex("[A-Za-z0-9_-]{11}")))
            com.clashofmusic.app.data.extractor.YouTubeMusicMix.url(seed.songId) else null
        fun publish(trace: RadioMixTrace) = synchronized(lock) {
            if (generation == epoch) {
                if (lastTrace?.seedId != trace.seedId) recent.clear()
                trace.selected.forEach { recent.remove(it.songId); recent[it.songId] = it }
                while (recent.size > 32) recent.remove(recent.keys.first())
                val complete = trace.copy(recentChoices = recent.values.toList())
                lastTrace = complete
                runCatching { onTrace(complete) }
            }
        }
        if (url == null) {
            publish(RadioMixTrace(seed.songId, null, 0, emptyList(), emptyList(), "Esta canción no tiene un enlace de YouTube válido para generar su mix."))
            return emptyList()
        }
        val usedIds = history.map { it.songId }.toSet() + seed.songId
        val usedNames = history.map(::identity).toSet() + identity(seed)
        fun available(items: List<SongItem>) = items.withIndex().filter { (_, s) -> eligible(s, seed) && s.id !in usedIds && identity(s.title, s.artist) !in usedNames }
        val prior = synchronized(lock) { cachedMix?.takeIf { it.seedId == seed.songId && System.currentTimeMillis() - it.fetchedAt < 10 * 60_000 } }
        var mix = prior
        // Refresh an exhausted generation once; never replace it with a generic search.
        if (prior == null || available(prior.songs).isEmpty()) {
            try {
                val songs = source.related(seed).take(180).distinctBy { it.id }
                coroutineContext.ensureActive()
                mix = CachedMix(seed.songId, System.currentTimeMillis(), songs)
                synchronized(lock) { if (epoch == generation && songs.isNotEmpty()) cachedMix = mix }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                coroutineContext.ensureActive()
                publish(RadioMixTrace(seed.songId, url, System.currentTimeMillis(), emptyList(), emptyList(), "No se pudo cargar el mix musical de YouTube. Comprueba la conexión y reintenta."))
                throw e
            }
        }
        if (synchronized(lock) { epoch != generation }) return emptyList()
        val resolved = requireNotNull(mix)
        val selected = mutableListOf<DownloadedSong>()
        val choices = mutableListOf<RadioMixChoice>()
        val names = mutableSetOf<String>()
        for ((index, item) in available(resolved.songs)) {
            if (!names.add(identity(item.title, item.artist))) continue
            selected.add(item.toRadioSong())
            choices.add(RadioMixChoice(item.id, index + 1, resolved.fetchedAt))
            if (selected.size >= count.coerceAtMost(RadioWindow.AHEAD)) break
        }
        publish(RadioMixTrace(seed.songId, url, resolved.fetchedAt, resolved.songs.map { it.id }, choices,
            if (selected.isEmpty()) "El mix de esta canción no ofrece más pistas nuevas ahora. Puedes reintentar o iniciar otra radio." else null))
        return selected
    }

    companion object {
        private val presentation = Regex("\\b(official|music video|audio|lyrics?|visualizer|mv|hd|4k|video)\\b", RegexOption.IGNORE_CASE)
        private val alternative = Regex("\\b(live|concert|cover|karaoke|remix|mashup|slowed|sped up|tutorial|nightcore)\\b|ライブ|カバー", RegexOption.IGNORE_CASE)
        private val nonMusic = Regex("\\b(interview|podcast|reacts?|reaction|reacting|reacción|reacciona|tutorial|full album|playlist|compilation)\\b|リアクション", RegexOption.IGNORE_CASE)
        fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun artistKey(value: String) = com.clashofmusic.app.data.extractor.YouTubeTrackIdentity.artistKey(value)
        fun identity(song: DownloadedSong) = identity(song.title, song.artist)
        fun identity(title: String, artist: String): String {
            val artistName = artistKey(artist)
            val name = key(presentation.replace(title, "")).removePrefix("$artistName ")
            return "$artistName:$name"
        }
        fun eligible(item: SongItem, seed: DownloadedSong): Boolean {
            val title = item.sourceTitle ?: item.title
            return item.id.matches(Regex("[A-Za-z0-9_-]{11}")) && item.durationSeconds in 45..1200 &&
                !nonMusic.containsMatchIn("$title ${item.channelName.orEmpty()}") && (!alternative.containsMatchIn(title) ||
                    alternative.findAll(title).all { match -> seed.title.contains(match.value, true) })
        }
    }
}

fun SongItem.toRadioSong() = DownloadedSong(
    id = "radio_$id", songId = id, title = title, artist = artist, album = null,
    durationSeconds = durationSeconds, thumbnailUrl = thumbnailUrl, filePath = "",
    fileUri = "clashradio://track/$id", format = "RADIO", quality = "Original", fileSizeBytes = 0, downloadedAt = 0
)

object RadioWindow {
    const val BEHIND = 5
    const val AHEAD = 10
    const val MAX_TRACKS = BEHIND + 1 + AHEAD
    const val MAX_BYTES = 96L * 1024 * 1024
    fun <T> around(entries: List<T>, currentIndex: Int): List<T> = if (currentIndex !in entries.indices) emptyList()
        else entries.subList((currentIndex - BEHIND).coerceAtLeast(0), (currentIndex + AHEAD + 1).coerceAtMost(entries.size))
}

data class RadioState(val active: Boolean = false, val loading: Boolean = false, val message: String? = null)
