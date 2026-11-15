package com.clashofmusic.app.data.repository

import com.clashofmusic.app.data.database.dao.FavoriteSongDao
import com.clashofmusic.app.data.database.dao.SearchHistoryDao
import com.clashofmusic.app.data.database.entity.FavoriteSongEntity
import com.clashofmusic.app.data.database.entity.SearchHistoryEntity
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.FavoriteSong
import com.clashofmusic.app.domain.model.SearchHistoryItem
import com.clashofmusic.app.domain.model.SongItem
import com.clashofmusic.app.domain.repository.FavoritesRepository
import com.clashofmusic.app.domain.repository.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class FavoritesRepositoryImpl(
    private val favoriteSongDao: FavoriteSongDao
) : FavoritesRepository {

    override fun observeFavorites(): Flow<List<FavoriteSong>> {
        return favoriteSongDao.getAllFavorites().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun isFavorite(songId: String): Flow<Boolean> {
        return favoriteSongDao.isFavorite(songId)
    }

    override suspend fun toggleFavorite(song: SongItem) = withContext(Dispatchers.IO) {
        val isFav = favoriteSongDao.isFavorite(song.id).first()
        if (isFav) {
            favoriteSongDao.deleteById(song.id)
        } else {
            val entity = FavoriteSongEntity(
                id = song.id,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationSeconds = song.durationSeconds,
                url = song.url,
                savedAt = System.currentTimeMillis()
            )
            favoriteSongDao.insert(entity)
        }
    }

    override suspend fun toggleFavoriteFromDownloaded(song: DownloadedSong) = withContext(Dispatchers.IO) {
        val isFav = favoriteSongDao.isFavorite(song.songId).first()
        if (isFav) {
            favoriteSongDao.deleteById(song.songId)
            favoriteSongDao.deleteById(song.id)
        } else {
            val entity = FavoriteSongEntity(
                id = song.songId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationSeconds = song.durationSeconds,
                url = "https://www.youtube.com/watch?v=${song.songId}",
                savedAt = System.currentTimeMillis()
            )
            favoriteSongDao.insert(entity)
        }
    }
}

class HistoryRepositoryImpl(
    private val searchHistoryDao: SearchHistoryDao
) : HistoryRepository {

    override fun observeRecentHistory(): Flow<List<SearchHistoryItem>> {
        return searchHistoryDao.getRecentHistory().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun addSearchQuery(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            searchHistoryDao.deleteByQuery(trimmed)
            searchHistoryDao.insert(
                SearchHistoryEntity(
                    query = trimmed,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun deleteSearchQuery(query: String) = withContext(Dispatchers.IO) {
        searchHistoryDao.deleteByQuery(query.trim())
    }

    override suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchHistoryDao.clearAll()
    }
}
