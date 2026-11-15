package com.clashofmusic.app.data.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/** File operations shared by exports; never overwrites or truncates an existing song. */
internal object SafeAudioFiles {
    fun publishCopy(
        source: File,
        destination: File,
        copyContents: (File, File) -> Unit = ::copyAndSync
    ): File {
        if (!source.isFile || source.length() == 0L) throw IOException("El audio de origen está vacío o no existe")
        if (source.canonicalFile == destination.canonicalFile) return source
        if (destination.exists()) throw IOException("El archivo de destino ya existe")
        val expectedSize = source.length()
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Destino inválido")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("No se puede crear la carpeta de música")
        val staged = File.createTempFile(".clash-export-", ".pending", parent)
        try {
            copyContents(source, staged)
            if (staged.length() != expectedSize || source.length() != expectedSize) {
                throw IOException("La copia del audio está incompleta")
            }
            // Same-directory move, without REPLACE_EXISTING: a name collision must fail safely.
            Files.move(staged.toPath(), destination.toPath())
            return destination
        } finally {
            staged.delete()
        }
    }

    fun deleteExact(file: File): Boolean {
        require(file.isAbsolute) { "Se requiere la ruta absoluta del audio" }
        if (file.exists() && !file.isFile) return false
        Files.deleteIfExists(file.toPath())
        return true
    }

    private fun copyAndSync(source: File, staged: File) {
        source.inputStream().use { input ->
            FileOutputStream(staged).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }
}
