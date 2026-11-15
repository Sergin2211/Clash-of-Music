package com.clashofmusic.app.data.local

import com.clashofmusic.app.data.database.entity.*
import com.clashofmusic.app.domain.model.DownloadedSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class VaultTransferTest {
    private fun saved(id: String = "saved", name: String = "song.opus") = VaultSong(id, id, name,
        "Canción 日本語", "Artista", "Álbum", null, 120, null, "OPUS", "192k", 5000, 10)
    private fun file(id: String = "scanned", path: String = "/Music/song.opus") = DownloadedSong(id, id,
        "Scanned", "Unknown", null, 120, null, path, "file://$path", "OPUS", "192k", 5000, 10)
    private fun data(song: VaultSong = saved()) = VaultData(updatedAt = 100, songs = listOf(song),
        playlists = listOf(VaultPlaylist("list", "Lista", songIds = listOf(song.id))))
    private fun plan(data: VaultData = data(), songs: List<DownloadedSongEntity> = emptyList(),
                     files: List<DownloadedSong> = listOf(file()), lists: List<PlaylistEntity> = emptyList(),
                     links: List<PlaylistSongEntity> = emptyList()) = VaultImportPlanner.plan(data, songs, files, lists, links)

    @Test fun roundTripAcceptsUtf8AndBom() {
        assertEquals(data(), VaultTransfer.read(("\uFEFF" + VaultCodec.encode(data())).byteInputStream()))
    }
    @Test fun rejectsUnrelatedTruncatedAndTrailingJson() {
        listOf("{}", "{", VaultCodec.encode(data()) + " garbage").forEach {
            assertThrows(Exception::class.java) { VaultTransfer.read(it.byteInputStream()) }
        }
    }
    @Test fun rejectsUnknownAndFractionalVersionsAndInvalidTypes() {
        val json = VaultCodec.encode(data())
        listOf(json.replace("\"version\":1", "\"version\":2"),
            json.replace("\"version\":1", "\"version\":1.5"),
            json.replace("\"isAlbum\":false", "\"isAlbum\":\"false\""),
            json.replace("\"durationSeconds\":120", "\"durationSeconds\":-1")).forEach {
            assertThrows(Exception::class.java) { VaultTransfer.read(it.byteInputStream()) }
        }
    }
    @Test fun rejectsInvalidUtf8OversizedAndReadFailures() {
        assertThrows(Exception::class.java) { VaultTransfer.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { VaultTransfer.read(ByteArrayInputStream(ByteArray(VaultTransfer.MAX_BYTES + 1))) }
        assertThrows(IOException::class.java) { VaultTransfer.read(object : java.io.InputStream() { override fun read(): Int = throw IOException("Disconnected") }) }
    }
    @Test fun rejectsPathsAndAmbiguousAliases() {
        listOf("../song.opus", "folder\\song.opus", "..", "\u0000").forEach {
            assertThrows(IllegalArgumentException::class.java) { VaultTransfer.validate(data(saved(name = it))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultTransfer.validate(data().copy(songs = listOf(saved(), saved("two").copy(songId = "saved"))))
        }
    }
    @Test fun missingAudioDoesNotCreateSongRowsButPreservesEmptyCollection() {
        val result = plan(files = emptyList())
        assertTrue(result.songs.isEmpty()); assertTrue(result.links.isEmpty())
        assertEquals(1, result.unavailable); assertEquals("list", result.playlists.single().id)
    }
    @Test fun restoresMetadataForExactFileAndRemapsPlaylistIdentity() {
        val result = plan()
        assertEquals("saved", result.songs.single().id)
        assertEquals("Canción 日本語", result.songs.single().title)
        assertEquals("/Music/song.opus", result.songs.single().filePath)
        assertEquals("saved", result.links.single().songId)
    }
    @Test fun preservesDistinctIdAndSongIdOfLegacyBackups() {
        val result = plan(data = data(saved().copy(songId = "remote")))
        assertEquals("saved", result.songs.single().id)
        assertEquals("remote", result.songs.single().songId)
        assertEquals("saved", result.links.single().songId)
    }
    @Test fun existingMetadataAndPlaylistOrderAreNeverReplaced() {
        val existing = DownloadedSongEntity.fromDomain(file("local").copy(title = "My newer edit"))
        val result = plan(songs = listOf(existing), lists = listOf(PlaylistEntity("list", "My newer list")),
            links = listOf(PlaylistSongEntity("list", "other", sortOrder = 3)))
        assertTrue(result.songs.isEmpty()); assertTrue(result.playlists.isEmpty())
        assertEquals("local", result.links.single().songId); assertEquals(4, result.links.single().sortOrder)
        assertEquals("My newer edit", existing.title)
    }
    @Test fun repeatImportAddsNothingAndNeverDuplicatesRelations() {
        val first = plan()
        val second = plan(songs = first.songs, lists = first.playlists, links = first.links)
        assertEquals(VaultImportResult(0, 0, 0, 0), second.result)
    }
    @Test fun duplicateFilenamesDoNotGuessBetweenDifferentFiles() {
        val result = plan(files = listOf(file(path = "/A/song.opus"), file("other", "/B/song.opus")))
        assertEquals(1, result.unavailable); assertTrue(result.songs.isEmpty())
    }
    @Test fun duplicateFilenamesInsideBackupCannotAssignArbitraryMetadata() {
        val result = plan(data = data().copy(songs = listOf(saved(), saved("other"))))
        assertEquals(2, result.unavailable); assertTrue(result.songs.isEmpty()); assertTrue(result.links.isEmpty())
    }
    @Test fun twoExistingIdentityMatchesCannotBeMergedByFilename() {
        val first = DownloadedSongEntity.fromDomain(file("saved"))
        val second = DownloadedSongEntity.fromDomain(file("remote", "/other.mp3"))
        val result = plan(data = data(saved().copy(songId = "remote")), songs = listOf(first, second))
        assertEquals(1, result.unavailable); assertTrue(result.songs.isEmpty()); assertTrue(result.links.isEmpty())
    }
    @Test fun currentMissingSongIsNotDeletedOrLinkedToAnUnrelatedFile() {
        val current = DownloadedSongEntity.fromDomain(file("saved", "/old/song.opus"))
        val result = plan(songs = listOf(current))
        assertEquals(1, result.unavailable); assertTrue(result.songs.isEmpty()); assertTrue(result.links.isEmpty())
    }
    @Test fun exportReadsCurrentSnapshotWithoutReplacingAutomaticBackup() = runBlocking {
        var live = data()
        var stored: VaultData? = live
        val storage = object : VaultStorage {
            override fun load() = stored
            override fun save(data: VaultData): Boolean { stored = data; return true }
        }
        val coordinator = VaultCoordinator(storage, { live }, { true })
        assertThrows(IllegalStateException::class.java) { runBlocking { coordinator.exportSnapshot() } }
        coordinator.reconcile { }
        val before = stored
        live = live.copy(songs = listOf(saved().copy(title = "Latest")))
        assertEquals("Latest", coordinator.exportSnapshot().songs.single().title)
        assertEquals(before, stored)
    }
}
