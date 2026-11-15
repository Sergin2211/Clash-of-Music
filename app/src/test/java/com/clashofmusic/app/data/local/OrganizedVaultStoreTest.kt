package com.clashofmusic.app.data.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class OrganizedVaultStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val oldDir get() = File(temp.root, "Download/ClashOfMusic").apply { mkdirs() }
    private val primary get() = File(temp.root, "Documents/ClashOfMusic/Respaldos/clash_catalog.json")
    private val mirror get() = File(temp.root, "private/clash_vault_backup.json")
    private fun data(time: Long) = VaultData(updatedAt = time, playlists = listOf(
        VaultPlaylist("list", "My list $time", createdAt = 1)
    ))
    private fun write(file: File, time: Long): File = file.apply {
        parentFile!!.mkdirs(); writeText(VaultCodec.encode(data(time)))
    }
    private fun store(allowed: Boolean = true) = OrganizedVaultStore(
        { listOf(primary, mirror) }, { OrganizedVaultStore.legacyFiles(listOf(oldDir)) }, { allowed }
    )
    private fun archives() = File(primary.parentFile, "Historial").listFiles().orEmpty().filter { it.extension == "zip" }

    @Test fun migratesNumberedCopiesWithoutLosingAnyHistoricalPlaylist() {
        val files = (1..31).map { write(File(oldDir, "clash_catalog ($it).json"), it.toLong()) }
        val originals = files.map { it.readBytes() }
        assertEquals(data(31), store().load())
        assertEquals(data(31), VaultCodec.decode(primary.readText()))
        assertEquals(data(30), VaultCodec.decode(File(primary.path + ".previous").readText()))
        assertTrue(files.none { it.exists() })
        ZipFile(archives().single()).use { zip ->
            val contents = zip.entries().asSequence().map { zip.getInputStream(it).use { it.readBytes() } }.toList()
            assertEquals(31, contents.size)
            originals.forEach { original -> assertTrue(contents.any { it.contentEquals(original) }) }
        }
    }

    @Test fun latestPrivateCatalogWinsAndOldVersionsCannotResurrectDeletedCollections() {
        write(File(oldDir, "clash_catalog.json"), 10)
        write(mirror, 500)
        assertEquals(data(500), store().load())
        assertEquals(data(500), VaultCodec.decode(primary.readText()))
    }

    @Test fun onlyKnownValidAutomaticCatalogsAreRetired() {
        val manual = write(File(oldDir, "clashvault_backup.json"), 900)
        val arbitrary = write(File(oldDir, "personal.json"), 999)
        val broken = File(oldDir, "clash_catalog (2).json").apply { writeText("broken") }
        val audio = File(oldDir, "My song.opus").apply { writeText("audio") }
        write(File(oldDir, "clash_catalog_123.json"), 100)
        assertEquals(data(100), store().load())
        listOf(manual, arbitrary, broken, audio).forEach { assertTrue(it.exists()) }
    }

    @Test fun publicationFailurePreservesEverySourceAndRemainsReadable() {
        val source = write(File(oldDir, "clash_catalog.json"), 10)
        val failing = OrganizedVaultStore({ listOf(primary) }, { listOf(source) }, { true },
            publish = { _, _ -> throw IOException("Full disk") })
        assertEquals(data(10), failing.load())
        assertTrue(source.exists())
        assertFalse(primary.exists())
    }

    @Test fun archiveFailureNeverDeletesSourceOrPublishesIncompleteMigration() {
        val source = write(File(oldDir, "clash_catalog.json"), 10)
        primary.parentFile!!.mkdirs()
        File(primary.parentFile, "Historial").writeText("Blocked")
        assertEquals(data(10), store().load())
        assertTrue(source.exists())
        assertFalse(primary.exists())
    }

    @Test fun sourceChangedAfterArchivingIsNotDeleted() {
        val source = write(File(oldDir, "clash_catalog.json"), 10)
        val racing = OrganizedVaultStore({ listOf(primary) }, { listOf(source) }, { true },
            publish = { file, snapshot ->
                VaultFileStore({ emptyList() }).replace(file, VaultCodec.encode(snapshot))
                write(source, 20)
            })
        racing.load()
        assertEquals(data(20), VaultCodec.decode(source.readText()))
        assertEquals(data(20), store().load())
    }

    @Test fun subsequentSavesRotateTwoFilesAndNeverCreateMoreDownloadCatalogs() {
        write(File(oldDir, "clash_catalog (1).json"), 10)
        val storage = store()
        storage.load()
        repeat(50) { assertTrue(storage.save(data(100L + it))) }
        assertEquals(data(149), storage.load())
        assertEquals(data(148), VaultCodec.decode(File(primary.path + ".previous").readText()))
        assertTrue(oldDir.listFiles()!!.isEmpty())
        assertEquals(1, archives().size)
        assertEquals(setOf("Historial", "clash_catalog.json", "clash_catalog.json.previous"), primary.parentFile!!.list()!!.toSet())
        primary.writeText("truncated")
        mirror.writeText("truncated")
        assertEquals(data(148), storage.load())
    }

    @Test fun migrationIsIdempotentAndDoesNotRewriteAnUnchangedCatalog() {
        write(File(oldDir, "clash_catalog.json"), 10)
        val storage = store()
        storage.load()
        val original = primary.readBytes()
        val archive = archives().single()
        repeat(3) { assertEquals(data(10), storage.load()) }
        assertArrayEquals(original, primary.readBytes())
        assertEquals(listOf(archive), archives())
    }

    @Test fun deniedBroadAccessDoesNotReadOrRemoveOldFiles() {
        val source = write(File(oldDir, "clash_catalog.json"), 10)
        write(mirror, 20)
        assertEquals(data(20), store(allowed = false).load())
        assertTrue(source.exists())
        assertFalse(primary.exists())
    }
}
