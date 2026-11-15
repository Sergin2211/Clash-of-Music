package com.clashofmusic.app.data.repository

import com.clashofmusic.app.data.database.dao.PlaylistDao
import com.clashofmusic.app.data.database.entity.PlaylistEntity
import com.clashofmusic.app.data.database.entity.PlaylistSongEntity
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.Playlist
import com.clashofmusic.app.domain.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.clashofmusic.app.domain.model.AlbumCollection
import kotlinx.coroutines.withContext
import java.util.UUID

class PlaylistRepositoryImpl(
    private val playlistDao: PlaylistDao,
    private val downloadedSongDao: com.clashofmusic.app.data.database.dao.DownloadedSongDao? = null,
    private val clashVaultHelper: com.clashofmusic.app.data.local.LibraryVault? = null
) : PlaylistRepository {

    override fun observeAlbumCollections(): Flow<List<AlbumCollection>> = combine(
        playlistDao.getAllPlaylists(), playlistDao.observePlaylistSongs()
    ) { playlists, relations ->
        val byPlaylist = relations.groupBy { it.playlistId }
        playlists.filter { it.isAlbum }.map { AlbumCollection(it.toDomain(), byPlaylist[it.id].orEmpty().map { link -> link.songId }) }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T =
        if (clashVaultHelper != null) clashVaultHelper.mutate(block) else block()

    override fun observePlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { entity ->
                entity.toDomain()
            }
        }
    }

    override suspend fun getPlaylistById(id: String): Playlist? = withContext(Dispatchers.IO) {
        playlistDao.getPlaylistById(id)?.toDomain()
    }

    override suspend fun createPlaylist(
        name: String,
        description: String?,
        isAlbum: Boolean,
        thumbnailUrl: String?
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val entity = PlaylistEntity(
            id = id,
            name = name.trim(),
            description = description?.trim()?.ifBlank { null },
            thumbnailUrl = thumbnailUrl,
            isAlbum = isAlbum,
            createdAt = System.currentTimeMillis()
        )
        mutate {
            playlistDao.insertOrUpdatePlaylist(entity)
            id
        }
    }

    override suspend fun updatePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        mutate { playlistDao.insertOrUpdatePlaylist(PlaylistEntity.fromDomain(playlist)) }
    }

    override suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        mutate { playlistDao.deletePlaylistWithSongs(id) }
    }

    override suspend fun addSongToPlaylist(playlistId: String, songId: String) = withContext(Dispatchers.IO) {
        val relation = PlaylistSongEntity(
            playlistId = playlistId,
            songId = songId,
            addedAt = System.currentTimeMillis()
        )
        mutate { playlistDao.addSongToPlaylist(relation) }
    }

    override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) = withContext(Dispatchers.IO) {
        mutate { playlistDao.removeSongFromPlaylist(playlistId, songId) }
    }

    override suspend fun reorderPlaylist(playlistId: String, orderedSongIds: List<String>) = withContext(Dispatchers.IO) {
        mutate { playlistDao.updatePlaylistOrder(playlistId, orderedSongIds) }
    }

    override fun getSongsForPlaylist(playlistId: String): Flow<List<DownloadedSong>> {
        return playlistDao.getSongsForPlaylist(playlistId).map { list ->
            list.map { it.toDomain() }
        }
    }
}
