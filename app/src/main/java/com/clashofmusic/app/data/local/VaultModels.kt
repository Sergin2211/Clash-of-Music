package com.clashofmusic.app.data.local

data class VaultSong(
    val id: String,
    val songId: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val genre: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val format: String = "MP3",
    val quality: String = "320k",
    val fileSizeBytes: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis(),
    val genreProfileJson: String? = null
)

data class VaultPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val isAlbum: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val songIds: List<String> = emptyList()
)

data class VaultData(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val songs: List<VaultSong> = emptyList(),
    val playlists: List<VaultPlaylist> = emptyList()
)

