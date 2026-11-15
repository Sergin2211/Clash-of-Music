package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.AudioQuality
import com.clashofmusic.app.domain.model.DownloadEligibility
import com.clashofmusic.app.domain.model.DownloadTask
import com.clashofmusic.app.domain.repository.DownloadRepository
import com.clashofmusic.app.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow

class DownloadSongUseCase(
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository
) {
    fun observeTasks(): Flow<List<DownloadTask>> {
        return downloadRepository.observeTasks()
    }

    fun isDownloading(songId: String): Boolean {
        return downloadRepository.isSongDownloading(songId)
    }

    suspend fun checkEligibility(
        songId: String,
        format: AudioFormat,
        quality: AudioQuality
    ): DownloadEligibility {
        return libraryRepository.checkDownloadEligibility(songId, format, quality)
    }

    suspend fun enqueue(task: DownloadTask): String {
        return downloadRepository.enqueueDownload(task)
    }

    suspend fun cancel(taskId: String) {
        downloadRepository.cancelDownload(taskId)
    }

    suspend fun retry(taskId: String) {
        downloadRepository.retryDownload(taskId)
    }

    suspend fun clearFinished() {
        downloadRepository.clearFinishedDownloads()
    }

    suspend fun deleteTask(taskId: String) {
        downloadRepository.deleteDownloadTask(taskId)
    }
}
