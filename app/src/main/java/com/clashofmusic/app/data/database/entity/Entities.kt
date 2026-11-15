package com.clashofmusic.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.FavoriteSong
import com.clashofmusic.app.domain.model.Playlist
import com.clashofmusic.app.domain.model.SearchHistoryItem

@Entity(
    tableName = "downloaded_songs",
    indices = [
        Index(value = ["songId"], unique = true),
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["downloadedAt"]),
        Index(value = ["filePath"])
    ]
)
data class DownloadedSongEntity(
    @PrimaryKey
    val id: String, // Equal to songId
    val songId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val filePath: String,
    val fileUri: String,
    val format: String,
    val quality: String = "320k",
    val fileSizeBytes: Long,
    val downloadedAt: Long,
    val genre: String? = null
) {
    fun toDomain(): DownloadedSong = DownloadedSong(
        id = id,
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
        filePath = filePath,
        fileUri = fileUri,
        format = format,
        quality = quality,
        fileSizeBytes = fileSizeBytes,
        downloadedAt = downloadedAt,
        genre = genre
    )

    companion object {
        fun fromDomain(song: DownloadedSong): DownloadedSongEntity = DownloadedSongEntity(
            id = song.songId,
            songId = song.songId,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationSeconds = song.durationSeconds,
            thumbnailUrl = song.thumbnailUrl,
            filePath = song.filePath,
            fileUri = song.fileUri,
            format = song.format,
            quality = song.quality,
            fileSizeBytes = song.fileSizeBytes,
            downloadedAt = song.downloadedAt,
            genre = song.genre
        )
    }
}

@Entity(
    tableName = "search_history",
    indices = [Index(value = ["query"], unique = true)]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val query: String,
    val timestamp: Long
) {
    fun toDomain(): SearchHistoryItem = SearchHistoryItem(
        id = id,
        query = query,
        timestamp = timestamp
    )
}

@Entity(tableName = "favorite_songs")
data class FavoriteSongEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val url: String,
    val savedAt: Long
) {
    fun toDomain(): FavoriteSong = FavoriteSong(
        id = id,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        url = url,
        savedAt = savedAt
    )

    companion object {
        fun fromDomain(favorite: FavoriteSong): FavoriteSongEntity = FavoriteSongEntity(
            id = favorite.id,
            title = favorite.title,
            artist = favorite.artist,
            thumbnailUrl = favorite.thumbnailUrl,
            durationSeconds = favorite.durationSeconds,
            url = favorite.url,
            savedAt = favorite.savedAt
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val isAlbum: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(songCount: Int = 0): Playlist = Playlist(
        id = id,
        name = name,
        description = description,
        thumbnailUrl = thumbnailUrl,
        isAlbum = isAlbum,
        createdAt = createdAt,
        songCount = songCount
    )

    companion object {
        fun fromDomain(playlist: Playlist): PlaylistEntity = PlaylistEntity(
            id = playlist.id,
            name = playlist.name,
            description = playlist.description,
            thumbnailUrl = playlist.thumbnailUrl,
            isAlbum = playlist.isAlbum,
            createdAt = playlist.createdAt
        )
    }
}

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index(value = ["playlistId"]), Index(value = ["songId"])]
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val addedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)
