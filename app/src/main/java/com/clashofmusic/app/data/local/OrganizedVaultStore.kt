package com.clashofmusic.app.data.local

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Called under VaultCoordinator's mutex, on IO. Never changes audio or the database. */
internal class OrganizedVaultStore(
    private val targets: () -> List<File>,
    private val legacyFiles: () -> List<File>,
    private val canMigrate: () -> Boolean,
    readExtra: () -> List<VaultData> = { emptyList() },
    private val onRetired: (List<File>) -> Unit = {},
    private val publish: (File, VaultData) -> Unit = { file, data ->
        VaultFileStore({ emptyList() }).replace(file, VaultCodec.encode(data))
    }
) : VaultStorage {
    private val store = VaultFileStore(targets, readExtra)
    private data class Legacy(val file: File, val bytes: ByteArray, val data: VaultData)

    override fun load(): VaultData? {
        val current = store.load()
        if (!canMigrate()) return current
        val old = legacyFiles().distinctBy { it.absolutePath }.mapNotNull { file ->
            try {
                if (!file.isFile || file.length() > VaultTransfer.MAX_BYTES) return@mapNotNull null
                val bytes = file.readBytes()
                Legacy(file, bytes, VaultCodec.decode(bytes.toString(Charsets.UTF_8)))
            } catch (_: Exception) { null } // Unknown/corrupt files are never removed.
        }
        val generations = (listOfNotNull(current) + targets().flatMap { file ->
            listOf(file, File(file.path + ".previous")).mapNotNull { candidate ->
                try { VaultCodec.decode(candidate.readText(Charsets.UTF_8)) } catch (_: Exception) { null }
            }
        } + old.map { it.data }).distinct().sortedByDescending { it.updatedAt }
        val latest = generations.firstOrNull() ?: return null
        try {
            val primary = targets().first()
            // Archive every old generation byte-for-byte, including differing historical playlists.
            val archive = if (old.isNotEmpty()) archive(primary.parentFile!!, old) else null
            generations.getOrNull(1)?.let { previous ->
                val previousFile = File(primary.path + ".previous")
                if (read(previousFile) != previous) publish(previousFile, previous)
                check(read(previousFile) == previous)
            }
            if (read(primary) != latest) publish(primary, latest)
            check(read(primary) == latest)
            if (archive != null) {
                verifyArchive(archive, old)
                val retired = old.filter { entry ->
                    // A changed source must remain in place for the next migration attempt.
                    entry.file.readBytes().contentEquals(entry.bytes) && entry.file.delete()
                }.map { it.file }
                onRetired(retired)
            }
        } catch (_: Exception) {
            // Storage full, revoked access or interrupted publication: keep originals and retry later.
        }
        return latest
    }

    override fun save(data: VaultData): Boolean = store.save(data)

    private fun read(file: File): VaultData? = try {
        VaultCodec.decode(file.readText(Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun archive(directory: File, entries: List<Legacy>): File {
        val history = File(directory, "Historial")
        check(history.isDirectory || history.mkdirs())
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            digest.update(entry.file.absolutePath.toByteArray(Charsets.UTF_8))
            digest.update(MessageDigest.getInstance("SHA-256").digest(entry.bytes))
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        val destination = File(history, "catalogos-anteriores-$id.zip")
        if (destination.isFile && runCatching { verifyArchive(destination, entries) }.isSuccess) return destination
        val staged = File.createTempFile(".migration-", ".pending", history)
        try {
            FileOutputStream(staged).use { file ->
                ZipOutputStream(file).use { zip ->
                    entries.forEachIndexed { index, entry ->
                        zip.putNextEntry(ZipEntry("$index/${entry.file.parentFile!!.name}/${entry.file.name}"))
                        zip.write(entry.bytes)
                        zip.closeEntry()
                    }
                    zip.finish()
                    zip.flush()
                    file.fd.sync()
                }
            }
            verifyArchive(staged, entries)
            Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            verifyArchive(destination, entries)
            return destination
        } finally { staged.delete() }
    }

    private fun verifyArchive(archive: File, entries: List<Legacy>) {
        ZipFile(archive).use { zip ->
            check(zip.size() == entries.size)
            entries.forEachIndexed { index, entry ->
                val saved = requireNotNull(zip.getEntry("$index/${entry.file.parentFile!!.name}/${entry.file.name}"))
                zip.getInputStream(saved).use { check(it.readBytes().contentEquals(entry.bytes)) }
            }
        }
    }

    companion object {
        private val autoName = Regex("clash_catalog(?: \\(\\d+\\)|_\\d+)?\\.json(?:\\.previous)?")
        fun legacyFiles(directories: List<File>): List<File> = directories.flatMap { directory ->
            directory.listFiles()?.filter { file ->
                file.isFile && (autoName.matches(file.name) || file.name in setOf(
                    ".clashvault.json", ".clashvault.json.previous", ".clash_vault.json", ".clash_vault.json.previous"
                ))
            }.orEmpty()
        }.sortedBy { it.absolutePath }
    }
}
