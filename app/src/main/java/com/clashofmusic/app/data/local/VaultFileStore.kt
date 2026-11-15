package com.clashofmusic.app.data.local

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal interface VaultStorage {
    fun load(): VaultData?
    fun save(data: VaultData): Boolean
}

/** All callers serialize through VaultCoordinator; no live catalog is opened with truncation. */
internal class VaultFileStore(
    private val files: () -> List<File>,
    private val readExtra: () -> List<VaultData> = { emptyList() },
    private val writeExtra: (VaultData) -> Boolean = { false },
    private val move: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
) : VaultStorage {
    override fun load(): VaultData? {
        val candidates = files().flatMap { listOf(it, File(it.path + ".previous")) }.mapNotNull { file ->
            try { VaultCodec.decode(file.readText(Charsets.UTF_8)) } catch (_: Exception) { null }
        }
        val extra = try { readExtra() } catch (_: Exception) { emptyList() }
        return (candidates + extra).maxByOrNull { it.updatedAt }
    }

    override fun save(data: VaultData): Boolean {
        val text = VaultCodec.encode(data)
        var saved = false
        files().forEach { file ->
            try {
                val previous = try { file.readText(Charsets.UTF_8).also { VaultCodec.decode(it) } }
                    catch (_: Exception) { null }
                if (previous != null) replace(File(file.path + ".previous"), previous)
                replace(file, text)
                saved = true
            } catch (_: Exception) { /* Keep the old generation and try another location. */ }
        }
        val extraSaved = try { writeExtra(data) } catch (_: Exception) { false }
        return saved || extraSaved
    }

    internal fun replace(file: File, text: String) {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "No se puede crear la carpeta del respaldo" }
        val staged = File.createTempFile(".clashvault-", ".pending", parent)
        try {
            FileOutputStream(staged).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            VaultCodec.decode(staged.readText(Charsets.UTF_8))
            move(staged, file)
        } finally {
            staged.delete()
        }
    }
}
