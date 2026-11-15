package com.clashofmusic.app.domain.model

import java.text.Normalizer
import java.util.Locale

/** Album names are scoped to their tagged artist; punctuation and accents remain significant. */
data class LibraryAlbumKey(val title: String, val artist: String) {
    // Length prefix avoids collisions when either tag contains a separator.
    val stableId: String get() = "${title.length}:$title$artist"

    fun contains(song: DownloadedSong?): Boolean = song != null && from(song) == this

    companion object {
        private val whitespace = Regex("\\s+")

        private fun normalize(value: String): String = Normalizer.normalize(
            value.trim().replace(whitespace, " "), Normalizer.Form.NFC
        ).lowercase(Locale.ROOT)

        fun from(song: DownloadedSong): LibraryAlbumKey? {
            val title = song.album?.takeIf { it.isNotBlank() } ?: return null
            return LibraryAlbumKey(normalize(title), normalize(song.artist))
        }

        fun from(title: String, artist: String) = LibraryAlbumKey(normalize(title), normalize(artist))
    }
}

data class LibraryAlbum(
    val key: LibraryAlbumKey,
    val title: String,
    val artist: String,
    val songs: List<DownloadedSong>,
    val albumThumbnailUrl: String? = null
) {
    fun contains(song: DownloadedSong?): Boolean = song != null && songs.any {
        it.id == song.id || it.songId == song.songId
    }
    fun matchesPlayingQueue(current: DownloadedSong?, baseQueue: List<DownloadedSong>): Boolean =
        contains(current) && baseQueue.map { it.songId }.toSet() == songs.map { it.songId }.toSet()
    val thumbnailUrl: String? get() = albumThumbnailUrl?.takeIf(String::isNotBlank)
        ?: songs.firstNotNullOfOrNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }
    val genre: String? get() = songs.firstNotNullOfOrNull { it.genre?.takeIf(String::isNotBlank) }

    fun matchesQuery(query: String): Boolean = query.isBlank() ||
        title.contains(query, ignoreCase = true) || artist.contains(query, ignoreCase = true)
}

fun groupLibraryAlbums(songs: List<DownloadedSong>, collections: List<AlbumCollection> = emptyList()): List<LibraryAlbum> {
    val byId = songs.flatMap { listOf(it.id to it, it.songId to it) }.toMap()
    val groups = linkedMapOf<LibraryAlbumKey, LibraryAlbum>()
    val managed = mutableSetOf<String>()
    for ((album, ids) in collections) {
        if (!album.isAlbum || album.name.isBlank()) continue
        val tracks = ids.mapNotNull { byId[it] }.distinctBy { it.songId }
        if (tracks.isEmpty()) continue
        val description = album.description.orEmpty()
        val artist = when {
            description.startsWith("Importado de ") -> description.substringAfter(": ", tracks.first().artist)
            " • " in description -> description.substringBefore(" • ")
            else -> tracks.first().artist
        }.trim().ifBlank { tracks.first().artist }
        val key = LibraryAlbumKey.from(album.name, artist)
        val previous = groups[key]
        groups[key] = LibraryAlbum(key, album.name.trim(), artist,
            (previous?.songs.orEmpty() + tracks).distinctBy { it.songId },
            previous?.albumThumbnailUrl?.takeIf(String::isNotBlank) ?: album.thumbnailUrl?.takeIf(String::isNotBlank))
        managed.addAll(tracks.map { it.songId })
    }
    for (song in songs) {
        if (song.songId in managed) continue
        val key = LibraryAlbumKey.from(song) ?: continue
        val previous = groups[key]
        groups[key] = LibraryAlbum(key, previous?.title ?: song.album!!.trim(), previous?.artist ?: song.artist.trim(),
            previous?.songs.orEmpty() + song, previous?.albumThumbnailUrl)
    }
    return groups.values.sortedWith(compareBy({ it.key.title }, { it.key.artist }))
}
