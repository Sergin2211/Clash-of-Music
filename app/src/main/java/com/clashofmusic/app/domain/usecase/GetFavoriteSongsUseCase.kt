package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.FavoriteSong
import com.clashofmusic.app.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow

class GetFavoriteSongsUseCase(
    private val favoritesRepository: FavoritesRepository
) {
    operator fun invoke(): Flow<List<FavoriteSong>> {
        return favoritesRepository.observeFavorites()
    }

    fun isFavorite(songId: String): Flow<Boolean> {
        return favoritesRepository.isFavorite(songId)
    }
}
