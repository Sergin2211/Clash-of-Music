package com.clashofmusic.app.domain.model

data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val isAlbum: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val songCount: Int = 0
)

data class PlaylistWithSongs(
    val playlist: Playlist,
    val songs: List<DownloadedSong>
)

data class AlbumCollection(val album: Playlist, val songIds: List<String>)

data class RemotePlaylistInfo(
    val id: String,
    val title: String,
    val author: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val trackCount: Int,
    val source: PlaylistSource,
    val isAlbum: Boolean = false,
    val tracks: List<RemoteTrackItem>,
    val readWarning: String? = null
)

data class RemoteTrackItem(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Long = 0L,
    val directUrl: String? = null,
    val thumbnailUrl: String? = null
)

enum class PlaylistSource {
    YOUTUBE,
    SPOTIFY,
    LOCAL
}
