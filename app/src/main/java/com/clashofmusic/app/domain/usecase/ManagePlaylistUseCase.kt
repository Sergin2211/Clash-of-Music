package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.Playlist
import com.clashofmusic.app.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow

class ManagePlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    fun observePlaylists(): Flow<List<Playlist>> {
        return playlistRepository.observePlaylists()
    }

    suspend fun getPlaylistById(id: String): Playlist? {
        return playlistRepository.getPlaylistById(id)
    }

    suspend fun createPlaylist(name: String, description: String? = null, isAlbum: Boolean = false, thumbnailUrl: String? = null): String {
        return playlistRepository.createPlaylist(name, description, isAlbum, thumbnailUrl)
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        playlistRepository.updatePlaylist(playlist)
    }

    suspend fun deletePlaylist(id: String) {
        playlistRepository.deletePlaylist(id)
    }

    suspend fun addSongToPlaylist(playlistId: String, songId: String) {
        playlistRepository.addSongToPlaylist(playlistId, songId)
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
        playlistRepository.removeSongFromPlaylist(playlistId, songId)
    }

    fun getSongsForPlaylist(playlistId: String): Flow<List<DownloadedSong>> {
        return playlistRepository.getSongsForPlaylist(playlistId)
    }
}
