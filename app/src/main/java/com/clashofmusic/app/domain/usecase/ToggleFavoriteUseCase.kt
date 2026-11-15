package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.SongItem
import com.clashofmusic.app.domain.repository.FavoritesRepository

class ToggleFavoriteUseCase(
    private val favoritesRepository: FavoritesRepository
) {
    suspend fun toggle(song: SongItem) {
        favoritesRepository.toggleFavorite(song)
    }

    suspend fun toggleFromDownloaded(song: DownloadedSong) {
        favoritesRepository.toggleFavoriteFromDownloaded(song)
    }
}
