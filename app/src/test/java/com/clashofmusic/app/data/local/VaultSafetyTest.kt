package com.clashofmusic.app.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class VaultSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private fun catalog(time: Long, title: String = "Canción 日本語") = VaultData(
        updatedAt = time,
        songs = listOf(VaultSong("song", "song", "song.mp3", title, "Artist", null, null, 123, null, downloadedAt = 123)),
        playlists = listOf(VaultPlaylist("playlist", "Mi lista", createdAt = 123, songIds = listOf("song")))
    )

    @Test fun codecPreservesUnicodeNullMetadataAndPlaylistOrder() {
        val original = catalog(100)
        assertEquals(original, VaultCodec.decode(VaultCodec.encode(original)))
    }

    @Test fun malformedPrimaryAndUnrelatedJsonDoNotMaskValidBackup() {
        val primary = temp.newFile("primary.json").apply { writeText("{broken") }
        val unrelated = temp.newFile("unrelated.json").apply { writeText("{}") }
        val backup = temp.newFile("backup.json").apply { writeText(VaultCodec.encode(catalog(100))) }
        assertEquals(catalog(100), VaultFileStore({ listOf(primary, unrelated, backup) }).load())
    }

    @Test fun newestValidatedGenerationWinsOverStalePrimary() {
        val primary = temp.newFile("primary.json").apply { writeText(VaultCodec.encode(catalog(100))) }
        val backup = temp.newFile("backup.json").apply { writeText(VaultCodec.encode(catalog(200, "My edit"))) }
        assertEquals("My edit", VaultFileStore({ listOf(primary, backup) }).load()!!.songs.single().title)
    }

    @Test fun interruptedPublicationKeepsOriginalAndRecoverablePreviousGeneration() {
        val file = temp.newFile("catalog.json").apply { writeText(VaultCodec.encode(catalog(100))) }
        val store = VaultFileStore({ listOf(file) }, move = { from, to ->
            if (to == file) throw IOException("Interrupted publication")
            Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        })
        assertFalse(store.save(catalog(200)))
        assertEquals(catalog(100), VaultCodec.decode(file.readText()))
        assertEquals(catalog(100), store.load())
        assertFalse(temp.root.list()!!.any { it.endsWith(".pending") })
    }

    @Test fun corruptedNewestCatalogFallsBackToPreviousGeneration() {
        val file = temp.newFile("catalog.json").apply { writeText(VaultCodec.encode(catalog(100))) }
        val store = VaultFileStore({ listOf(file) })
        assertTrue(store.save(catalog(200)))
        file.writeText("truncated")
        assertEquals(catalog(100), store.load())
    }

    @Test fun invalidRowsRejectEntireCandidateInsteadOfSilentlyLosingSongs() {
        val file = temp.newFile("catalog.json").apply {
            writeText("""{"version":1,"updatedAt":300,"songs":[{}],"playlists":[]}""")
        }
        val backup = temp.newFile("backup.json").apply { writeText(VaultCodec.encode(catalog(100))) }
        assertEquals(catalog(100), VaultFileStore({ listOf(file, backup) }).load())
    }

    @Test fun uninitializedDatabaseCannotOverwriteRecoverableCatalog() = runBlocking {
        val memory = MemoryVault(catalog(100))
        val coordinator = VaultCoordinator(memory, { VaultData(updatedAt = 1) }, { true })
        assertFalse(coordinator.backupCurrentDatabase())
        assertEquals(catalog(100), memory.data)
        assertEquals(0, memory.writes)
    }

    @Test fun deniedPermissionsAndFailedReconciliationKeepPriorCatalog() = runBlocking {
        val memory = MemoryVault(catalog(100))
        var accessible = true
        val coordinator = VaultCoordinator(memory, { catalog(200) }, { accessible })
        coordinator.reconcile { }
        val before = memory.data
        accessible = false
        assertFalse(coordinator.backupCurrentDatabase())
        assertEquals(before, memory.data)
        accessible = true
        try { coordinator.reconcile<Unit> { throw IOException("Unreadable storage") } }
        catch (_: IOException) { }
        assertFalse(coordinator.backupCurrentDatabase())
        assertEquals(before, memory.data)
    }

    @Test fun snapshotIsRecheckedForPermissionLossBeforePublication() = runBlocking {
        val memory = MemoryVault(catalog(100))
        var accessible = true
        var revokeDuringSnapshot = false
        val coordinator = VaultCoordinator(memory, {
            if (revokeDuringSnapshot) accessible = false
            catalog(200)
        }, { accessible })
        coordinator.reconcile { }
        val before = memory.data
        revokeDuringSnapshot = true
        assertFalse(coordinator.backupCurrentDatabase())
        assertEquals(before, memory.data)
    }

    @Test fun concurrentBackupSnapshotsAreSerializedAndLatestEditSurvives() = runBlocking {
        val memory = MemoryVault(catalog(100))
        var current = catalog(200, "Before")
        var delaySnapshot = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = VaultCoordinator(memory, {
            val captured = current
            if (delaySnapshot) {
                delaySnapshot = false
                entered.complete(Unit)
                release.await()
            }
            captured
        }, { true }, { 1000L })
        coordinator.reconcile { }
        delaySnapshot = true
        val first = async { coordinator.backupCurrentDatabase() }
        entered.await()
        current = catalog(300, "Latest edit")
        val second = async { coordinator.backupCurrentDatabase() }
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals("Latest edit", memory.data!!.songs.single().title)
        assertTrue(memory.data!!.updatedAt > 1000L)
    }

    @Test fun userMutationWaitsForRestorationAndItsNewerEditIsBackedUp() = runBlocking {
        val memory = MemoryVault(catalog(100))
        var current = catalog(200)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = VaultCoordinator(memory, { current }, { true })
        val restoration = async {
            coordinator.reconcile { recovered -> entered.complete(Unit); release.await(); current = recovered!! }
        }
        entered.await()
        val edit = async { coordinator.mutate { current = catalog(300, "Edited after restore") } }
        yield()
        assertFalse(edit.isCompleted)
        release.complete(Unit)
        restoration.await()
        edit.await()
        assertEquals("Edited after restore", memory.data!!.songs.single().title)
    }

    @Test fun failedBackupDoesNotTurnCommittedMutationIntoFailure() = runBlocking {
        var fail = false
        val memory = object : VaultStorage {
            override fun load() = catalog(100)
            override fun save(data: VaultData): Boolean { if (fail) throw IOException("Storage full"); return true }
        }
        val coordinator = VaultCoordinator(memory, { catalog(200) }, { true })
        coordinator.reconcile { }
        fail = true
        assertEquals("committed", coordinator.mutate { "committed" })
    }

    private class MemoryVault(var data: VaultData?) : VaultStorage {
        var writes = 0
        override fun load() = data
        override fun save(data: VaultData): Boolean { this.data = data; writes++; return true }
    }
}
