package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetDownloadedSongsUseCase(
    private val libraryRepository: LibraryRepository
) {
    operator fun invoke(): Flow<List<DownloadedSong>> {
        return libraryRepository.observeDownloadedSongs()
    }

    suspend fun getSong(songId: String): DownloadedSong? {
        return libraryRepository.getDownloadedSong(songId)
    }

    suspend fun isDownloaded(songId: String): Boolean {
        return libraryRepository.isSongDownloaded(songId)
    }
}
