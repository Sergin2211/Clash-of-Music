package com.clashofmusic.app.domain.model

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class GenreOrigin(val label: String) {
    MANUAL("Elegido por ti"), RECORDING("MusicBrainz · canción"), ARTIST("MusicBrainz · artista (referencia)"), LEGACY("Archivo o catálogo previo")
}
data class GenreAssignment(val name: String, val origin: GenreOrigin, val entityId: String? = null, val entityName: String? = null)
data class GenreProfile(val identity: String, val checkedAt: Long, val manual: Boolean = false,
    val entries: List<GenreAssignment> = emptyList()) {
    fun appliesTo(song: DownloadedSong) = manual || identity == GenreNames.identity(song)
}

object GenreNames {
    private val latinMarks = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")
    fun key(text: String): String = Normalizer.normalize(
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(latinMarks, ""), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(separators, " ").trim()
    private val names = mapOf("j pop" to "J-Pop", "jpop" to "J-Pop", "j rock" to "J-Rock", "jrock" to "J-Rock",
        "hip hop" to "Hip-Hop", "hiphop" to "Hip-Hop", "r b" to "R&B", "rhythm and blues" to "R&B",
        "electronic" to "Electrónica", "electronica" to "Electrónica", "alternative rock" to "Rock alternativo",
        "rock alternativo" to "Rock alternativo", "indie rock" to "Indie Rock", "indie pop" to "Indie Pop",
        "pop rock" to "Pop Rock", "heavy metal" to "Heavy Metal", "hard rock" to "Hard Rock",
        "k pop" to "K-Pop", "kpop" to "K-Pop", "edm" to "EDM", "soundtrack" to "Banda sonora", "banda sonora" to "Banda sonora",
        "classical" to "Clásica", "clasica" to "Clásica", "drum and bass" to "Drum & Bass", "drum bass" to "Drum & Bass")
    fun canonical(text: String): String? {
        val value = text.trim().replace(whitespace, " ")
        if (value.isBlank() || value.length > 60 || value.any(Char::isISOControl) || key(value) in
            setOf("unknown", "desconocido", "sin genero", "music", "musica", "album", "single", "ep", "null")) return null
        return names[key(value)] ?: value.lowercase(Locale.ROOT).split(' ').joinToString(" ") {
            if (it == "edm" || it == "r&b") it.uppercase(Locale.ROOT) else it.replaceFirstChar(Char::titlecase)
        }
    }
    fun split(text: String?): List<String> = text.orEmpty().split(',', ';', '\n').flatMap { value -> when (key(value)) {
        "hip hop rap" -> listOf("Hip-Hop", "Rap")
        "r b soul" -> listOf("R&B", "Soul")
        else -> listOf(value)
    } }.mapNotNull(::canonical).distinctBy(::key).take(24)
    fun identity(song: DownloadedSong) = key(song.title) + "|" + key(song.artist) + "|" + song.durationSeconds
}

val DownloadedSong.genreAssignments: List<GenreAssignment> get() {
    val profile = genreProfile?.takeIf { it.appliesTo(this) }
    val entries = profile?.entries.orEmpty()
    val legacy = if (profile?.manual == true) emptyList() else GenreNames.split(genre).map { GenreAssignment(it, GenreOrigin.LEGACY) }
    val priority = mapOf(GenreOrigin.MANUAL to 4, GenreOrigin.RECORDING to 3, GenreOrigin.LEGACY to 2, GenreOrigin.ARTIST to 1)
    return (entries + legacy).mapNotNull { e -> GenreNames.canonical(e.name)?.let { e.copy(name = it) } }
        .groupBy { GenreNames.key(it.name) }.values.map { group -> group.maxBy { priority.getValue(it.origin) } }
}
val DownloadedSong.genreNames: List<String> get() = genreAssignments.map { it.name }

object GenreProfileCodec {
    fun encode(profile: GenreProfile): String = JSONObject().put("version", 1).put("identity", profile.identity)
        .put("checkedAt", profile.checkedAt).put("manual", profile.manual).put("entries", JSONArray().apply {
            profile.entries.forEach { put(JSONObject().put("name", it.name).put("origin", it.origin.name).put("entityId", it.entityId).put("entityName", it.entityName)) }
        }).toString()
    fun decode(text: String): GenreProfile {
        require(text.length <= 16000)
        val root = JSONObject(text); require(root.getInt("version") == 1)
        val identity = root.getString("identity"); require(identity.length <= 2000)
        val at = root.getLong("checkedAt"); require(at >= 0)
        val rows = root.getJSONArray("entries"); require(rows.length() <= 24)
        val entries = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            GenreAssignment(GenreNames.canonical(row.getString("name")) ?: error("Género inválido"),
                GenreOrigin.valueOf(row.getString("origin")), row.optString("entityId").takeIf { it.isNotBlank() && it != "null" },
                row.optString("entityName").takeIf { it.isNotBlank() && it != "null" }?.also { require(it.length <= 300) })
        }
        return GenreProfile(identity, at, root.getBoolean("manual"), entries.distinctBy { GenreNames.key(it.name) })
    }
}
