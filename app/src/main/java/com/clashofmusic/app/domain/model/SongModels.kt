package com.clashofmusic.app.domain.model

data class SongItem(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val url: String,
    val sourceTitle: String? = null,
    val channelName: String? = null
)

data class SongDetails(
    val id: String,
    val title: String,
    val artist: String,
    val channelName: String,
    val album: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val url: String,
    val audioStreams: List<AudioStreamInfo>,
    val description: String? = null
)

data class AudioStreamInfo(
    val format: String,
    val bitrate: Int,
    val codec: String,
    val contentSize: Long?,
    val url: String
)

data class AlbumItem(
    val id: String,
    val title: String,
    val artist: String,
    val genre: String?,
    val year: String?,
    val trackCount: Int,
    val thumbnailUrl: String?
)

data class AlbumTrackItem(
    val trackNumber: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?
)

data class DownloadedSong(
    val id: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val filePath: String,
    val fileUri: String,
    val format: String,
    val quality: String = "320k",
    val fileSizeBytes: Long,
    val downloadedAt: Long,
    val genre: String? = null,
    val year: String? = null,
    val genreProfile: GenreProfile? = null
)

enum class AudioFormat(val displayName: String, val extension: String) {
    MP3("MP3 (Universal)", "mp3"),
    M4A("M4A (AAC Rápido)", "m4a"),
    OPUS("OPUS (Alta Eficiencia)", "opus"),
    ORIGINAL("Original (Sin conversión)", "original")
}

enum class AudioQuality(val displayName: String, val bitrate: String, val kbpsValue: Int) {
    BEST_320K("Máxima Calidad (320 kbps)", "320k", 320),
    HIGH_256K("Alta Calidad (256 kbps)", "256k", 256),
    MEDIUM_192K("Media Calidad (192 kbps)", "192k", 192),
    STANDARD_128K("Estándar (128 kbps)", "128k", 128);

    companion object {
        fun fromBitrate(bitrate: String): AudioQuality {
            return entries.firstOrNull { it.bitrate.equals(bitrate, ignoreCase = true) } ?: BEST_320K
        }
    }
}

enum class DownloadEligibility {
    CAN_DOWNLOAD,
    ALREADY_DOWNLOADED_SAME_QUALITY,
    CAN_OVERWRITE_QUALITY
}
