package com.clashofmusic.app.data.media

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Values explicitly edited by the user; null album/year remove the corresponding physical tag. */
data class AudioTags(val title: String, val artist: String, val album: String?, val year: String?,
                     val genre: String? = null, val songId: String? = null) {
    fun validated(): AudioTags {
        require(title.isNotBlank() && artist.isNotBlank()) { "Título y artista son obligatorios" }
        val date = year?.trim()?.takeIf { it.isNotBlank() }
        require(date == null || date.matches(Regex("[0-9]{4}"))) { "El año debe tener cuatro cifras" }
        return copy(title = title.trim(), artist = artist.trim(), album = album?.trim()?.takeIf { it.isNotBlank() }, year = date)
    }
}

interface AudioMetadataEditor {
    suspend fun readYear(file: File): String? = null
    suspend fun edit(file: File, tags: AudioTags, commit: suspend (Long) -> Unit)
}

/** The original survives both preparation failures and a failed database commit. */
internal class MetadataFileTransaction {
    suspend fun replace(original: File, prepare: suspend (File) -> Unit, commit: suspend (Long) -> Unit) {
        require(original.isFile && original.length() > 0) { "El archivo de audio no está disponible" }
        val parent = original.absoluteFile.parentFile!!
        val staged = File.createTempFile(".clash-tags-", ".${original.extension}", parent)
        val backup = File.createTempFile(".clash-tags-backup-", ".${original.extension}", parent)
        var retainBackup = false
        try {
            prepare(staged)
            FileOutputStream(staged, true).use { it.fd.sync() }
            check(staged.length() > 0) { "No se generó el archivo con etiquetas" }
            // FUSE shared storage does not reliably support hard links; keep a verified private-name copy.
            original.inputStream().use { input -> FileOutputStream(backup).use { output -> input.copyTo(output); output.fd.sync() } }
            check(backup.length() == original.length()) { "No se pudo conservar el audio anterior" }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                Files.move(staged.toPath(), original.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                try { commit(original.length()) }
                catch (error: Throwable) {
                    try { Files.move(backup.toPath(), original.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                    catch (rollback: Throwable) { retainBackup = true; error.addSuppressed(rollback) }
                    throw error
                }
            }
        } finally {
            staged.delete()
            if (!retainBackup) backup.delete()
        }
    }
}
