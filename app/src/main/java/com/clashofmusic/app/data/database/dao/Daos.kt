package com.clashofmusic.app.data.database.dao

import androidx.room.*
import com.clashofmusic.app.data.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedSongDao {
    @Query("SELECT * FROM downloaded_songs ORDER BY downloadedAt DESC")
    fun getAllSongs(): Flow<List<DownloadedSongEntity>>

    @Query("SELECT * FROM downloaded_songs WHERE id = :id OR songId = :id LIMIT 1")
    suspend fun getSongById(id: String): DownloadedSongEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_songs WHERE id = :id OR songId = :id)")
    suspend fun isSongDownloaded(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(song: DownloadedSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<DownloadedSongEntity>)

    @Update
    suspend fun update(song: DownloadedSongEntity)

    @Query("DELETE FROM downloaded_songs WHERE id = :id OR songId = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM downloaded_songs WHERE id IN (:ids) OR songId IN (:ids)")
    suspend fun deleteAllByIds(ids: List<String>): Int

    @Query("DELETE FROM downloaded_songs WHERE filePath = :path")
    suspend fun deleteByFilePath(path: String): Int

    @Query("SELECT * FROM downloaded_songs")
    suspend fun getAllSongsList(): List<DownloadedSongEntity>
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 30")
    fun getRecentHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteByQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}

@Dao
interface FavoriteSongDao {
    @Query("SELECT * FROM favorite_songs ORDER BY savedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteSongEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_songs WHERE id = :songId)")
    fun isFavorite(songId: String): Flow<Boolean>

    @Query("SELECT * FROM favorite_songs WHERE id = :songId LIMIT 1")
    suspend fun getFavoriteById(songId: String): FavoriteSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteSongEntity)

    @Query("DELETE FROM favorite_songs WHERE id = :songId")
    suspend fun deleteById(songId: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist_songs ORDER BY sortOrder ASC, addedAt ASC")
    fun observePlaylistSongs(): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Transaction
    suspend fun deletePlaylistWithSongs(id: String) {
        removeAllSongsFromPlaylist(id)
        deletePlaylist(id)
    }

    @Transaction
    suspend fun updatePlaylistOrder(id: String, songIds: List<String>) {
        songIds.forEachIndexed { order, songId -> updateSongOrder(id, songId, order) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(relation: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun removeAllSongsFromPlaylist(playlistId: String)

    @Query("UPDATE playlist_songs SET sortOrder = :order WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updateSongOrder(playlistId: String, songId: String, order: Int)

    @Query("""
        SELECT ds.* FROM downloaded_songs ds
        INNER JOIN playlist_songs ps ON ds.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.sortOrder ASC, ps.addedAt ASC
    """)
    fun getSongsForPlaylist(playlistId: String): Flow<List<DownloadedSongEntity>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    fun getSongCountForPlaylist(playlistId: String): Flow<Int>

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    suspend fun removeSongFromAllPlaylists(songId: String)

    @Query("DELETE FROM playlist_songs WHERE songId IN (:songIds)")
    suspend fun removeSongsFromAllPlaylists(songIds: List<String>)

    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylistsList(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs")
    suspend fun getAllPlaylistSongsList(): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPlaylists(playlists: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPlaylistSongs(relations: List<PlaylistSongEntity>)
}
