package com.clashofmusic.app.data.repository

import com.clashofmusic.app.data.database.dao.DownloadedSongDao
import com.clashofmusic.app.data.database.dao.PlaylistDao
import com.clashofmusic.app.data.database.entity.*
import com.clashofmusic.app.data.local.*
import com.clashofmusic.app.data.media.*
import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class LibrarySyncSafetyTest {
    @Test fun existingAlbumSongAllowsFormatReplacementButSkipsExactMatch() = runBlocking {
        val db = FakeDatabase(listOf(song("one")))
        val repo = repository(db, FakeStorage())
        assertEquals(DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY,
            repo.checkDownloadEligibility("one", AudioFormat.MP3, AudioQuality.MEDIUM_192K))
        assertEquals(DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY,
            repo.checkDownloadEligibility("one", AudioFormat.MP3, AudioQuality.BEST_320K))
        assertEquals(DownloadEligibility.CAN_OVERWRITE_QUALITY,
            repo.checkDownloadEligibility("one", AudioFormat.OPUS, AudioQuality.MEDIUM_192K))
        assertEquals(DownloadEligibility.CAN_OVERWRITE_QUALITY,
            repo.checkDownloadEligibility("one", AudioFormat.M4A, AudioQuality.STANDARD_128K))
        assertEquals(1, db.songs.size)
    }
    private fun song(id: String, album: String? = "Real Album - Single", title: String = "Natural") = DownloadedSongEntity(
        id, id, title, "My artist", album, 180, null, "/Music/ClashOfMusic/$id.mp3",
        "file:///Music/ClashOfMusic/$id.mp3", "MP3", "192k", 5000, 123, "My genre"
    )

    @Test fun deniedPermissionsDoNotDeleteSongsRelationsOrBackUpEmptyDatabase() = runBlocking {
        val db = FakeDatabase(listOf(song("one")))
        db.relations.add(PlaylistSongEntity("mine", "one"))
        val storage = FakeStorage().apply { readable = false }
        val vault = FakeVault(null)
        val repo = repository(db, storage, vault)
        assertThrows(LibraryStorageUnavailableException::class.java) { runBlocking { repo.syncStorage() } }
        assertEquals(1, db.songs.size)
        assertEquals(1, db.relations.size)
        assertEquals(0, db.mutations)
        assertEquals(0, vault.reconciliations)
    }

    @Test fun unreadableListingDoesNotBecomeEmptyLibrary() = runBlocking {
        val db = FakeDatabase(listOf(song("one")))
        val storage = FakeStorage().apply { listingFailure = true }
        assertThrows(IOException::class.java) { runBlocking { repository(db, storage).syncStorage() } }
        assertEquals(0, db.mutations)
    }

    @Test fun anyInaccessibleFilePreventsPartialPurgeOfOtherRows() = runBlocking {
        val db = FakeDatabase(listOf(song("missing"), song("denied")))
        val storage = FakeStorage().apply {
            states["/Music/ClashOfMusic/missing.mp3"] = AudioFileAvailability.MISSING
            states["/Music/ClashOfMusic/denied.mp3"] = AudioFileAvailability.INACCESSIBLE
        }
        assertThrows(LibraryStorageUnavailableException::class.java) { runBlocking { repository(db, storage).syncStorage() } }
        assertEquals(2, db.songs.size)
        assertEquals(0, db.mutations)
    }

    @Test fun confirmedMissingFileIsPurgedAfterAuthorizedCompleteScan() = runBlocking {
        val missing = song("missing")
        val db = FakeDatabase(listOf(missing))
        db.relations.add(PlaylistSongEntity("mine", "missing"))
        val storage = FakeStorage().apply { states[missing.filePath] = AudioFileAvailability.MISSING }
        assertEquals(1, repository(db, storage).syncStorage())
        assertTrue(db.songs.isEmpty())
        assertTrue(db.relations.isEmpty())
    }

    @Test fun availabilityAndEligibilityChecksNeverDeleteCatalogRows() = runBlocking {
        val original = song("one")
        val db = FakeDatabase(listOf(original))
        val storage = FakeStorage().apply { states[original.filePath] = AudioFileAvailability.MISSING }
        val repo = repository(db, storage)
        assertFalse(repo.isSongDownloaded("one"))
        assertEquals(DownloadEligibility.CAN_DOWNLOAD, repo.checkDownloadEligibility("one", AudioFormat.MP3, AudioQuality.MEDIUM_192K))
        storage.states[original.filePath] = AudioFileAvailability.INACCESSIBLE
        assertFalse(repo.isSongDownloaded("one"))
        repo.checkDownloadEligibility("one", AudioFormat.MP3, AudioQuality.MEDIUM_192K)
        assertEquals(original, db.songs["one"])
        assertEquals(0, db.mutations)
    }

    @Test fun syncPreservesUserEditsAndLegitimateSingleAlbumNames() = runBlocking {
        val original = song("one")
        val db = FakeDatabase(listOf(original))
        val storage = FakeStorage().apply { scanned = listOf(original.toDomain().copy(title = "Wrong title", album = "Wrong album")) }
        repository(db, storage, FakeVault(VaultData(songs = listOf(vaultSong(original).copy(album = "Stale backup"))))).syncStorage()
        assertEquals(original, db.songs["one"])
        assertTrue(db.playlists.isEmpty())
    }

    @Test fun importedFilesUseExactVaultIdentityAndPreserveIntentionallyEmptyMetadata() = runBlocking {
        val original = song("original", album = null).copy(genre = null)
        val scanned = original.toDomain().copy(id = "original.mp3", songId = "original.mp3", title = "Filename guess", album = "Inferred album")
        val db = FakeDatabase()
        val storage = FakeStorage().apply { this.scanned = listOf(scanned) }
        repository(db, storage, FakeVault(VaultData(songs = listOf(vaultSong(original))))).syncStorage()
        assertEquals(original.title, db.songs[original.id]!!.title)
        assertNull(db.songs[original.id]!!.album)
        assertNull(db.songs[original.id]!!.genre)
        assertEquals("192k", db.songs[original.id]!!.quality)
    }

    @Test fun filenamePrefixAndSongTitleDoNotMatchUnrelatedVaultSong() = runBlocking {
        val scanned = song("Natural").toDomain()
        val unrelated = vaultSong(song("Natural Live", title = "Natural")).copy(artist = "Unrelated artist")
        val db = FakeDatabase()
        val storage = FakeStorage().apply { this.scanned = listOf(scanned) }
        repository(db, storage, FakeVault(VaultData(songs = listOf(unrelated)))).syncStorage()
        assertEquals("My artist", db.songs["Natural"]!!.artist)
        assertEquals("Real Album - Single", db.songs["Natural"]!!.album)
    }

    @Test fun restoresMissingPlaylistsAlongsideExistingOnesWithoutOverwritingEditsOrOrder() = runBlocking {
        val db = FakeDatabase(listOf(song("one"), song("two")))
        db.playlists["existing"] = PlaylistEntity("existing", "My renamed playlist")
        db.relations.addAll(listOf(PlaylistSongEntity("existing", "two", sortOrder = 0), PlaylistSongEntity("existing", "one", sortOrder = 1)))
        val backup = VaultData(playlists = listOf(
            VaultPlaylist("existing", "Old name", songIds = listOf("one", "two")),
            VaultPlaylist("restore", "Recovered", songIds = listOf("two", "one", "unavailable"))
        ))
        val repo = repository(db, FakeStorage(), FakeVault(backup))
        assertEquals(1, repo.syncStorage())
        assertEquals("My renamed playlist", db.playlists["existing"]!!.name)
        assertEquals(listOf("two", "one"), db.relations.filter { it.playlistId == "existing" }.sortedBy { it.sortOrder }.map { it.songId })
        assertEquals(listOf("two", "one"), db.relations.filter { it.playlistId == "restore" }.sortedBy { it.sortOrder }.map { it.songId })
        assertEquals(0, repo.syncStorage())
        assertEquals(2, db.playlists.size)
        assertEquals(4, db.relations.size)
    }

    @Test fun permissionsLostAfterListingAbortBeforeAnyDatabaseChanges() = runBlocking {
        val db = FakeDatabase(listOf(song("one")))
        val storage = FakeStorage().apply { revokeAfterListing = true }
        assertThrows(LibraryStorageUnavailableException::class.java) { runBlocking { repository(db, storage).syncStorage() } }
        assertEquals(0, db.mutations)
    }

    @Test fun canonicalDownloadReplacesScanAliasWithoutLosingPlaylistMembership() = runBlocking {
        val canonical = song("youtube-id")
        val scanned = canonical.copy(id = "filename.mp3", songId = "filename.mp3")
        val db = FakeDatabase(listOf(scanned))
        db.relations.add(PlaylistSongEntity("mine", scanned.id, sortOrder = 5))
        repository(db, FakeStorage()).saveDownloadedSong(canonical.toDomain())
        assertEquals(listOf(canonical.id), db.songs.keys.toList())
        assertEquals(canonical.id, db.relations.single().songId)
        assertEquals(5, db.relations.single().sortOrder)
    }

    @Test fun metadataEditWritesPhysicalFieldsBeforeUpdatingDatabaseAndReadsYearFromFile() = runBlocking {
        val original = song("one")
        val db = FakeDatabase(listOf(original))
        var physical: AudioTags? = null
        val editor = object : AudioMetadataEditor {
            override suspend fun readYear(file: java.io.File) = physical?.year
            override suspend fun edit(file: java.io.File, tags: AudioTags, commit: suspend (Long) -> Unit) {
                assertEquals(original, db.songs["one"])
                physical = tags
                commit(9999)
            }
        }
        val repo = repository(db, FakeStorage(), editor = editor)
        assertTrue(repo.updateSongMetadata("one", " New title ", " New artist ", " ", "2024"))
        assertEquals("New title", db.songs.getValue("one").title)
        assertNull(db.songs.getValue("one").album)
        assertEquals(9999L, db.songs.getValue("one").fileSizeBytes)
        assertEquals("2024", repo.getEditableSong("one")!!.year)
        assertEquals(original.filePath, db.songs.getValue("one").filePath)
    }

    @Test fun failedPhysicalMetadataWriteDoesNotChangeDatabase() = runBlocking {
        val original = song("one"); val db = FakeDatabase(listOf(original))
        val editor = object : AudioMetadataEditor {
            override suspend fun edit(file: java.io.File, tags: AudioTags, commit: suspend (Long) -> Unit) { throw IOException("disk full") }
        }
        assertThrows(IOException::class.java) { runBlocking { repository(db, FakeStorage(), editor = editor).updateSongMetadata("one", "New", "Artist", null, "2024") } }
        assertEquals(original, db.songs["one"]); assertEquals(0, db.mutations)
    }

    @Test fun editWithoutPhysicalWriterDoesNotPretendToSaveTags() = runBlocking {
        val db = FakeDatabase(listOf(song("one")))
        assertFalse(repository(db, FakeStorage()).updateSongMetadata("one", "New", "Artist", null, null))
        assertEquals(0, db.mutations)
    }

    @Test fun metadataYearValidationHappensBeforeAnyMutation() = runBlocking {
        val db = FakeDatabase(listOf(song("one")))
        val editor = object : AudioMetadataEditor {
            override suspend fun edit(file: java.io.File, tags: AudioTags, commit: suspend (Long) -> Unit) { error("must not write") }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository(db, FakeStorage(), editor = editor).updateSongMetadata("one", "New", "Artist", null, "24") } }
        assertEquals(0, db.mutations)
    }

    @Test fun bulkDeleteUsesOneBackupAndPreservesFailedSongAndUnselectedFiles() = runBlocking {
        val all = (0 until 25).map { song("bulk-$it") }
        val db = FakeDatabase(all)
        db.relations.addAll(all.map { PlaylistSongEntity("album", it.id) })
        val storage = FakeStorage().apply { failDeletePath = all[7].filePath }
        val vault = FakeVault(null)
        val failed = repository(db, storage, vault).deleteSongs(all.take(24).map { it.toDomain() })
        assertEquals(setOf("bulk-7"), failed)
        assertEquals(setOf("bulk-7", "bulk-24"), db.songs.keys)
        assertEquals(setOf("bulk-7", "bulk-24"), db.relations.map { it.songId }.toSet())
        assertEquals(1, vault.backups)
    }

    private fun repository(db: FakeDatabase, storage: FakeStorage, vault: FakeVault? = null, editor: AudioMetadataEditor? = null) =
        LibraryRepositoryImpl(db.songDao, db.playlistDao, storage, vault, metadataEditor = editor)

    private fun vaultSong(s: DownloadedSongEntity) = VaultSong(s.id, s.songId, java.io.File(s.filePath).name,
        s.title, s.artist, s.album, s.genre, s.durationSeconds, s.thumbnailUrl, s.format, s.quality, s.fileSizeBytes, s.downloadedAt)

    private class FakeVault(private val data: VaultData?) : LibraryVault {
        var reconciliations = 0
        var backups = 0
        override suspend fun backupCurrentDatabase(): Boolean { backups++; return true }
        override suspend fun <T> reconcile(block: suspend (VaultData?) -> T): T {
            reconciliations++
            return block(data)
        }
    }

    private class FakeStorage : LibraryStorage {
        var failDeletePath: String? = null
        var readable = true
        var listingFailure = false
        var revokeAfterListing = false
        var scanned: List<DownloadedSong> = emptyList()
        val states = mutableMapOf<String, AudioFileAvailability>()
        override fun canReadLibrary() = readable
        override fun inspectAudioFile(filePath: String, fileUri: String?) = AudioFileInspection(
            if (!readable) AudioFileAvailability.INACCESSIBLE else states[filePath] ?: AudioFileAvailability.AVAILABLE,
            filePath, fileUri
        )
        override suspend fun scanAndImportAudioFiles(): List<DownloadedSong> {
            if (listingFailure) throw IOException("Storage cannot be listed")
            if (revokeAfterListing) readable = false
            return scanned
        }
        override fun findLocalArtwork(cleanFileName: String, songId: String?): String? = null
        override fun deleteAudioFile(filePath: String, fileUri: String?) = filePath != failDeletePath
        override fun scanFile(path: String) = Unit
    }

    /** In-memory DAO implementations through the real interfaces; no Android runtime or mocks required. */
    private class FakeDatabase(initial: List<DownloadedSongEntity> = emptyList()) {
        val songs = initial.associateBy { it.id }.toMutableMap()
        val playlists = mutableMapOf<String, PlaylistEntity>()
        val relations = mutableListOf<PlaylistSongEntity>()
        var mutations = 0
        val songDao = dao<DownloadedSongDao> { name, args ->
            when (name) {
                "getAllSongsList" -> songs.values.toList()
                "getAllSongs" -> flowOf(songs.values.toList())
                "getSongById" -> songs.values.firstOrNull { it.id == args[0] || it.songId == args[0] }
                "isSongDownloaded" -> songs.values.any { it.id == args[0] || it.songId == args[0] }
                "insertAll" -> { @Suppress("UNCHECKED_CAST") val rows = args[0] as List<DownloadedSongEntity>; rows.forEach { songs[it.id] = it }; mutations++; Unit }
                "insertOrUpdate", "update" -> { val row = args[0] as DownloadedSongEntity; songs[row.id] = row; mutations++; Unit }
                "deleteAllByIds" -> { val ids = args[0] as List<*>; val keys = songs.values.filter { it.id in ids || it.songId in ids }.map { it.id }; keys.forEach { songs.remove(it) }; mutations++; keys.size }
                "deleteById" -> { val keys = songs.values.filter { it.id == args[0] || it.songId == args[0] }.map { it.id }; keys.forEach { songs.remove(it) }; mutations++; keys.size }
                else -> error("Unexpected song DAO method: $name")
            }
        }
        val playlistDao = dao<PlaylistDao> { name, args ->
            when (name) {
                "getAllPlaylistsList" -> playlists.values.toList()
                "getAllPlaylistSongsList" -> relations.toList()
                "insertMissingPlaylist" -> { val p = args[0] as PlaylistEntity; if (p.id in playlists) -1L else { playlists[p.id] = p; mutations++; 1L } }
                "addSongToPlaylist" -> { val r = args[0] as PlaylistSongEntity; relations.removeAll { it.playlistId == r.playlistId && it.songId == r.songId }; relations.add(r); mutations++; Unit }
                "removeSongsFromAllPlaylists" -> { val ids = args[0] as List<*>; relations.removeAll { it.songId in ids }; mutations++; Unit }
                "removeSongFromAllPlaylists" -> { relations.removeAll { it.songId == args[0] }; mutations++; Unit }
                else -> error("Unexpected playlist DAO method: $name")
            }
        }
        companion object {
            private inline fun <reified T> dao(crossinline call: (String, Array<out Any?>) -> Any?): T =
                Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T
        }
    }
}
