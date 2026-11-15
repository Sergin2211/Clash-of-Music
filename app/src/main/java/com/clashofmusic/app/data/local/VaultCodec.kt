package com.clashofmusic.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Strict envelope validation prevents a damaged or unrelated JSON file becoming an empty backup. */
internal object VaultCodec {
    fun decode(text: String): VaultData {
        val tokenizer = JSONTokener(text)
        val root = tokenizer.nextValue() as? JSONObject ?: error("El archivo no es un catálogo ClashVault")
        require(tokenizer.nextClean() == '\u0000') { "Hay contenido extra después del catálogo" }
        require((root["version"] as? Number)?.toDouble() == 1.0) { "Versión de ClashVault no compatible" }
        val updatedAt = root.number("updatedAt")
        require(updatedAt > 0) { "Fecha de respaldo inválida" }
        val songs = root.array("songs").rows().map { value ->
            val row = value as? JSONObject ?: error("Canción inválida")
            VaultSong(
                id = row.requiredString("id"), songId = row.requiredString("songId"),
                fileName = row.requiredString("fileName"), title = row.requiredString("title"),
                artist = row.string("artist") ?: "Desconocido", album = row.string("album"),
                genre = row.string("genre"), durationSeconds = row.number("durationSeconds"),
                thumbnailUrl = row.string("thumbnailUrl"), format = row.string("format") ?: "MP3",
                quality = row.string("quality") ?: "320k", fileSizeBytes = row.number("fileSizeBytes"),
                downloadedAt = row.number("downloadedAt", updatedAt),
                genreProfileJson = row.string("genreProfile")?.also { com.clashofmusic.app.domain.model.GenreProfileCodec.decode(it) }
            )
        }
        val playlists = root.array("playlists").rows().map { value ->
            val row = value as? JSONObject ?: error("Playlist inválida")
            val ids = row.array("songIds").rows().map { require(it is String && it.isNotBlank()); it }
            require(ids.distinct().size == ids.size) { "Canciones duplicadas en playlist" }
            VaultPlaylist(
                id = row.requiredString("id"), name = row.requiredString("name"),
                description = row.string("description"), thumbnailUrl = row.string("thumbnailUrl"),
                isAlbum = if (!row.has("isAlbum")) false else row["isAlbum"] as? Boolean ?: error("Tipo de colección inválido"),
                createdAt = row.number("createdAt", updatedAt), songIds = ids
            )
        }
        require(songs.distinctBy { it.id }.size == songs.size && songs.distinctBy { it.songId }.size == songs.size)
        require(playlists.distinctBy { it.id }.size == playlists.size)
        return VaultData(updatedAt = updatedAt, songs = songs, playlists = playlists)
    }

    fun encode(data: VaultData): String {
        val root = JSONObject().apply {
            put("version", data.version)
            put("updatedAt", data.updatedAt)
            put("songs", JSONArray().apply {
                data.songs.forEach { s -> put(JSONObject().apply {
                    put("id", s.id); put("songId", s.songId); put("fileName", s.fileName)
                    put("title", s.title); put("artist", s.artist); put("album", s.album)
                    put("genre", s.genre); put("durationSeconds", s.durationSeconds)
                    put("thumbnailUrl", s.thumbnailUrl); put("format", s.format); put("quality", s.quality)
                    put("fileSizeBytes", s.fileSizeBytes); put("downloadedAt", s.downloadedAt)
                    s.genreProfileJson?.let { put("genreProfile", it) }
                }) }
            })
            put("playlists", JSONArray().apply {
                data.playlists.forEach { p -> put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("description", p.description)
                    put("thumbnailUrl", p.thumbnailUrl); put("isAlbum", p.isAlbum); put("createdAt", p.createdAt)
                    put("songIds", JSONArray().apply { p.songIds.forEach { put(it) } })
                }) }
            })
        }
        return root.toString().also { decode(it) }
    }

    private fun JSONArray.rows(): List<Any> = (0 until length()).map { get(it) }
    private fun JSONObject.array(key: String) = this[key] as? JSONArray ?: error("Falta la lista $key")
    private fun JSONObject.string(key: String): String? {
        if (isNull(key)) return null
        return (get(key) as? String ?: error("Campo $key inválido")).takeIf { it.isNotBlank() }
    }
    private fun JSONObject.requiredString(key: String) = string(key) ?: error("Falta $key")
    private fun JSONObject.number(key: String, default: Long = 0L): Long {
        if (!has(key)) return default
        val value = get(key) as? Number ?: error("Campo $key inválido")
        require(value.toDouble().isFinite() && value.toDouble() >= 0 && value.toDouble() == value.toLong().toDouble()) { "Campo $key inválido" }
        return value.toLong()
    }
}
