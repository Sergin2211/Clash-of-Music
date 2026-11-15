package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.AlbumItem
import com.clashofmusic.app.domain.model.RemotePlaylistInfo
import com.clashofmusic.app.domain.model.SongDetails
import com.clashofmusic.app.domain.model.SongItem
import com.clashofmusic.app.domain.repository.MusicRepository

class SearchOnlineSongsUseCase(
    private val musicRepository: MusicRepository
) {
    suspend fun searchSongs(query: String): Result<List<SongItem>> {
        return musicRepository.search(query)
    }

    suspend fun searchAlbums(query: String): Result<List<AlbumItem>> {
        return musicRepository.searchAlbums(query)
    }

    suspend fun getSongDetails(songIdOrUrl: String): Result<SongDetails> {
        return musicRepository.getSongDetails(songIdOrUrl)
    }

    suspend fun extractRemotePlaylist(url: String): Result<RemotePlaylistInfo> {
        return musicRepository.extractRemotePlaylist(url)
    }

    fun isSupportedPlaylistUrl(url: String): Boolean {
        return musicRepository.isSupportedPlaylistUrl(url)
    }
}
