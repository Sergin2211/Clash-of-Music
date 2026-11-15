package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.repository.LibraryRepository

class SyncStorageUseCase(
    private val libraryRepository: LibraryRepository
) {
    suspend operator fun invoke(): Int {
        return libraryRepository.syncStorage()
    }

    suspend fun deleteSong(songId: String): Boolean {
        return libraryRepository.deleteSong(songId)
    }

    suspend fun updateMetadata(songId: String, title: String, artist: String, album: String?): Boolean {
        return libraryRepository.updateSongMetadata(songId, title, artist, album)
    }
}
