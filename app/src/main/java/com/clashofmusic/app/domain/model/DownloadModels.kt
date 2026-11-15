package com.clashofmusic.app.domain.model

data class DownloadTask(
    val id: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val url: String,
    val format: AudioFormat = AudioFormat.MP3,
    val quality: AudioQuality = AudioQuality.BEST_320K,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val downloadSpeed: String? = null,
    val etaSeconds: Long = 0L,
    val statusText: String = "Preparando...",
    val errorMessage: String? = null,
    val destinationPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val artworkFallbackUrl: String? = null
) {
    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED ||
                status == DownloadStatus.PREPARING ||
                status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.PROCESSING ||
                status == DownloadStatus.SAVING ||
                status == DownloadStatus.PAUSED

    val isRunning: Boolean
        get() = status == DownloadStatus.PREPARING ||
                status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.PROCESSING ||
                status == DownloadStatus.SAVING
}

enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PROCESSING,
    SAVING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class StorageSpaceInfo(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val musicBytes: Long = 0L
)
