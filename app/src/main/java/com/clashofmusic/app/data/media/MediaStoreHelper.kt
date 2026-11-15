package com.clashofmusic.app.data.media

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.clashofmusic.app.domain.model.DownloadedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

class MediaStoreHelper(val context: Context) : LibraryStorage {
    private val workspaceLock = Any()
    private var activeDownloads = 0

    override fun readAudioYear(path: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.takeIf { it.matches(Regex("[0-9]{4}")) }
        } finally { retriever.release() }
    }

    override fun scanFile(path: String) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("audio/*"), null)
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MediaStoreHelper"
    }

    /**
     * Primary public music directory: /storage/emulated/0/Music/ClashOfMusic
     */
    fun getMusicDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "ClashOfMusic"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Temporary download working scratchpad in cacheDir
     */
    fun getWorkingDownloadDir(): File {
        val dir = File(context.cacheDir, "temp_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun acquireDownloadDirectory(): File = synchronized(workspaceLock) {
        getWorkingDownloadDir().also { activeDownloads++ }
    }

    fun releaseDownloadDirectory() = synchronized(workspaceLock) {
        check(activeDownloads > 0)
        activeDownloads--
    }

    suspend fun cleanTempDir(): Boolean = withContext(Dispatchers.IO) {
        synchronized(workspaceLock) {
            if (activeDownloads > 0) return@synchronized false
            val tempDir = File(context.cacheDir, "temp_downloads")
            if (!tempDir.exists()) return@synchronized true
            val files = tempDir.listFiles() ?: return@synchronized false
            files.map { file ->
                try { file.deleteRecursively() } catch (_: Exception) { false }
            }.all { it }
        }
    }

    /**
     * Publishes a verified copy. The caller retains the source until the database commit succeeds.
     */
    suspend fun exportToPublicMusic(
        sourceFile: File,
        title: String,
        artist: String,
        album: String?,
        formatExtension: String
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val cleanExt = formatExtension.lowercase().replace(".", "")
        val mimeType = when (cleanExt) {
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "webm" -> "audio/webm"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "mp4" -> "audio/mp4"
            else -> "audio/mpeg"
        }
        val cleanArtist = sanitizeFileName(artist)
        val cleanTitle = sanitizeFileName(title)
        val isArtistValid = cleanArtist.isNotBlank() &&
                !cleanArtist.equals("Desconocido", ignoreCase = true) &&
                !cleanArtist.equals("Unknown", ignoreCase = true)

        val targetBase = if (isArtistValid && cleanTitle.isNotBlank()) {
            "$cleanArtist - $cleanTitle"
        } else if (cleanTitle.isNotBlank()) {
            cleanTitle
        } else {
            sourceFile.nameWithoutExtension
        }

        val musicDir = getMusicDir()
        var candidateFile = File(musicDir, "$targetBase.$cleanExt")
        var counter = 1
        while (candidateFile.exists() && candidateFile.absolutePath != sourceFile.absolutePath) {
            candidateFile = File(musicDir, "$targetBase ($counter).$cleanExt")
            counter++
        }
        val destFile = candidateFile

        val finalFile = SafeAudioFiles.publishCopy(sourceFile, destFile)
        val finalPath = finalFile.absolutePath
        val finalUri = Uri.fromFile(finalFile).toString()

        try {
            MediaScannerConnection.scanFile(context, arrayOf(finalPath), arrayOf(mimeType), null)
        } catch (_: Exception) {}

        Pair(finalPath, finalUri)
    }

    fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override fun canReadLibrary(): Boolean {
        val state = Environment.getExternalStorageState()
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) return true
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun inspectAudioFile(filePath: String, fileUri: String?): AudioFileInspection {
        if (!canReadLibrary()) return AudioFileInspection(AudioFileAvailability.INACCESSIBLE)
        var pathMissing = filePath.isBlank()
        if (filePath.isNotBlank()) {
            try {
                val file = File(filePath)
                if (!file.isAbsolute) return AudioFileInspection(AudioFileAvailability.INACCESSIBLE)
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                if (!attributes.isRegularFile || attributes.size() <= 0L) {
                    return AudioFileInspection(AudioFileAvailability.INACCESSIBLE)
                }
                Files.newInputStream(file.toPath()).use { it.read() }
                return AudioFileInspection(AudioFileAvailability.AVAILABLE, file.absolutePath, Uri.fromFile(file).toString())
            } catch (_: NoSuchFileException) {
                // Android can hide inaccessible paths. Verify an accessible ancestor before confirming absence.
                var parent = File(filePath).absoluteFile.parentFile
                var confirmed = false
                while (parent != null) {
                    try {
                        Files.newDirectoryStream(parent.toPath()).use { }
                        confirmed = true
                        break
                    } catch (_: NoSuchFileException) {
                        parent = parent.parentFile
                    } catch (_: Exception) {
                        break
                    }
                }
                pathMissing = confirmed
            } catch (_: Exception) {
                return AudioFileInspection(AudioFileAvailability.INACCESSIBLE)
            }
        }
        if (!fileUri.isNullOrBlank() && fileUri.startsWith("content://")) {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(fileUri), "r")?.use {
                    return AudioFileInspection(AudioFileAvailability.AVAILABLE, filePath, fileUri)
                }
            } catch (_: Exception) {
                // A provider can report missing for a revoked grant; never infer deletion from that alone.
                return AudioFileInspection(AudioFileAvailability.INACCESSIBLE)
            }
            return AudioFileInspection(AudioFileAvailability.INACCESSIBLE)
        }
        return AudioFileInspection(if (pathMissing && canReadLibrary()) AudioFileAvailability.MISSING else AudioFileAvailability.INACCESSIBLE)
    }

    /** Deletes only the stored file/URI. Failure keeps the database record intact. */
    override fun deleteAudioFile(filePath: String, fileUri: String?): Boolean {
        var physicalDeleted = filePath.isBlank()
        if (filePath.isNotBlank()) {
            physicalDeleted = try {
                SafeAudioFiles.deleteExact(File(filePath))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot delete selected audio: ${e.message}")
                false
            }
        }

        val contentUri = fileUri?.takeIf { it.startsWith("content://") }
        if (contentUri != null) {
            try {
                context.contentResolver.delete(Uri.parse(contentUri), null, null)
                if (filePath.isNotBlank()) {
                    physicalDeleted = !File(filePath).exists()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot delete selected media URI: ${e.message}")
                return false
            }
        } else if (filePath.isBlank()) {
            return false
        }

        if (physicalDeleted && filePath.isNotBlank()) scanFile(filePath)
        return physicalDeleted
    }

    /**
     * Scans physical audio files in the public Music/ClashOfMusic folder with MediaMetadataRetriever
     */
    override suspend fun scanAndImportAudioFiles(): List<DownloadedSong> = withContext(Dispatchers.IO) {
        requireReadableLibrary()
        val importedList = mutableListOf<DownloadedSong>()
        val audioExtensions = listOf(".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".webm", ".wav", ".mp4")

        val directory = getMusicDir()
        // A failed listing is not an empty library. Do this before any database mutations.
        val listedFiles = directory.listFiles() ?: throw LibraryStorageUnavailableException()
        val publicFiles = listedFiles.filter { f ->
            !f.name.startsWith(".") && f.isFile && audioExtensions.any { ext -> f.name.endsWith(ext, ignoreCase = true) } && f.length() > 0
        }

        for (file in publicFiles) {
            val song = createSongFromFile(file)
            if (song != null) {
                importedList.add(song)
            }
        }

        requireReadableLibrary()
        importedList
    }

    fun getArtworkDir(): File {
        val dir = File(getMusicDir(), ".artwork")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveLocalArtwork(songId: String, cleanFileName: String, imageUrl: String?, imageBytes: ByteArray? = null): String? {
        if (imageUrl.isNullOrBlank() && imageBytes == null) return null
        return try {
            val artworkDir = getArtworkDir()
            val destFile1 = File(artworkDir, "$cleanFileName.jpg")
            val destFile2 = File(artworkDir, "$songId.jpg")

            if (imageBytes == null && destFile1.exists() && destFile1.length() > 0) {
                return destFile1.absolutePath
            }

            val bytes = imageBytes ?: ArtworkLoader.fetchArtworkBytes(imageUrl)
            if (bytes != null && bytes.isNotEmpty()) {
                destFile1.writeBytes(bytes)
                try { destFile2.writeBytes(bytes) } catch (_: Exception) {}
                destFile1.absolutePath
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not save local artwork: ${e.message}")
            null
        }
    }

    override fun findLocalArtwork(cleanFileName: String, songId: String?): String? {
        val artworkDir = File(getMusicDir(), ".artwork")
        if (!artworkDir.exists()) return null

        val f1 = File(artworkDir, "$cleanFileName.jpg")
        if (f1.exists() && f1.length() > 0) return f1.absolutePath

        if (!songId.isNullOrBlank()) {
            val f2 = File(artworkDir, "$songId.jpg")
            if (f2.exists() && f2.length() > 0) return f2.absolutePath
        }

        return null
    }

    fun cleanYouTubeNoise(title: String): String {
        return title
            .replace(Regex("(?i)\\[(official\\s*(video|audio|music\\s*video|mv)|lyrics?|4k|hd|remastered|visualizer)\\]"), "")
            .replace(Regex("(?i)\\((official\\s*(video|audio|music\\s*video|mv)|lyrics?|4k|hd|remastered|visualizer)\\)"), "")
            .replace(Regex("(?i)\\s*\\|\\s*(official\\s*(video|audio|music\\s*video|mv)|lyrics?|visualizer)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun createSongFromFile(file: File): DownloadedSong? {
        return try {
            val name = file.nameWithoutExtension
            var parsedArtist = "Desconocido"
            var parsedTitle = name

            if (name.contains(" - ")) {
                parsedArtist = name.substringBefore(" - ").trim()
                parsedTitle = name.substringAfter(" - ").trim()
            }

            var album: String? = null
            var genre: String? = null
            var durationSeconds = 0L
            var hasEmbeddedTitle = false

            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)

                    if (!metaTitle.isNullOrBlank()) {
                        parsedTitle = metaTitle.trim()
                        hasEmbeddedTitle = true
                    }
                    if (!metaArtist.isNullOrBlank()) {
                        parsedArtist = metaArtist.trim()
                    }

                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null }
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.ifBlank { null }
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationSeconds = (durStr?.toLongOrNull() ?: 0L) / 1000L
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {}

            if (!hasEmbeddedTitle) parsedTitle = cleanYouTubeNoise(parsedTitle)
            val localArtwork = findLocalArtwork(name, null)

            val format = when {
                file.extension.equals("m4a", ignoreCase = true) -> "M4A"
                file.extension.equals("opus", ignoreCase = true) -> "OPUS"
                else -> "MP3"
            }

            DownloadedSong(
                id = file.name,
                songId = file.name,
                title = parsedTitle,
                artist = parsedArtist,
                album = album,
                genre = genre,
                durationSeconds = durationSeconds,
                thumbnailUrl = localArtwork,
                filePath = file.absolutePath,
                fileUri = Uri.fromFile(file).toString(),
                format = format,
                quality = "320k",
                fileSizeBytes = file.length(),
                downloadedAt = file.lastModified()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating song from file ${file.name}: ${e.message}")
            null
        }
    }

    fun getStorageSpaceInfo(): com.clashofmusic.app.domain.model.StorageSpaceInfo {
        return try {
            val musicDir = getMusicDir()
            val statFs = android.os.StatFs(musicDir.absolutePath)
            val blockSize = statFs.blockSizeLong
            val totalBytes = statFs.blockCountLong * blockSize
            val freeBytes = statFs.availableBlocksLong * blockSize
            val musicBytes = musicDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            com.clashofmusic.app.domain.model.StorageSpaceInfo(
                totalBytes = totalBytes,
                freeBytes = freeBytes,
                musicBytes = musicBytes
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error obtaining storage stats: ${e.message}")
            com.clashofmusic.app.domain.model.StorageSpaceInfo()
        }
    }
}
