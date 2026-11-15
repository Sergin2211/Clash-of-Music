package com.clashofmusic.app.data.media

import com.clashofmusic.app.domain.model.DownloadedSong
import java.io.IOException

enum class AudioFileAvailability { AVAILABLE, MISSING, INACCESSIBLE }

data class AudioFileInspection(
    val availability: AudioFileAvailability,
    val path: String? = null,
    val uri: String? = null
)

class LibraryStorageUnavailableException : IOException(
    "No se pudo leer la biblioteca completa. Revisa los permisos y el almacenamiento; no se eliminó ningún registro."
)

interface LibraryStorage {
    fun canReadLibrary(): Boolean
    fun requireReadableLibrary() {
        if (!canReadLibrary()) throw LibraryStorageUnavailableException()
    }
    fun inspectAudioFile(filePath: String, fileUri: String?): AudioFileInspection
    suspend fun scanAndImportAudioFiles(): List<DownloadedSong>
    fun findLocalArtwork(cleanFileName: String, songId: String?): String?
    fun deleteAudioFile(filePath: String, fileUri: String?): Boolean
    fun readAudioYear(path: String): String? = null
    fun scanFile(path: String)
}
