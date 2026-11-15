package com.clashofmusic.app

import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.data.local.*
import com.clashofmusic.app.data.extractor.*
import com.clashofmusic.app.data.media.GenreLibraryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class GenreManagementTest {
    private fun song() = DownloadedSong("one", "one", "Song", "Artist", null, 180, null, "/private/audio.mp3", "", "MP3", "", 1, 1, genre = "Pop")
    private fun profile() = GenreProfile(GenreNames.identity(song()), 1, entries = listOf(GenreAssignment("Indie Pop", GenreOrigin.RECORDING, "id")))
    @Test fun normalizesAliasesWithoutCollapsingRapAndHipHop() {
        assertEquals(listOf("J-Pop", "Hip-Hop", "Electrónica", "Rap"), GenreNames.split("Jpop, j-pop; Hip Hop;Electronic;Electrónica;Rap"))
        assertTrue(GenreNames.split("Unknown; Single; Álbum; Music").isEmpty())
        assertNotEquals(GenreNames.key("か"), GenreNames.key("が"))
        assertEquals(listOf("Hip-Hop", "Rap", "R&B", "Soul"), GenreNames.split("Hip-Hop/Rap; R&B/Soul"))
    }
    @Test fun combinesSpecificAndLegacyGenresWithoutDuplicateCounts() {
        val song = song().copy(genre = "Indie Pop; Pop", genreProfile = profile())
        assertEquals(listOf("Indie Pop", "Pop"), song.genreNames)
        assertEquals(GenreOrigin.RECORDING, song.genreAssignments.first().origin)
    }
    @Test fun recordingEvidenceOutranksCatalogAndCatalogOutranksArtistReferences() {
        val song = song().copy(genreProfile = profile().copy(entries = listOf(GenreAssignment("Pop", GenreOrigin.ARTIST))))
        assertEquals(GenreOrigin.LEGACY, song.genreAssignments.single().origin)
    }
    @Test fun manualEditsAndIntentionalEmptySelectionOverrideAutomaticAndLegacyData() {
        val empty = song().copy(genreProfile = profile().copy(manual = true, entries = emptyList()))
        assertTrue(empty.genreNames.isEmpty())
        val edited = song().copy(genreProfile = profile().copy(manual = true, entries = listOf(GenreAssignment("j-rock", GenreOrigin.MANUAL))))
        assertEquals(listOf("J-Rock"), edited.genreNames)
        assertEquals(listOf("J-Rock"), edited.copy(artist = "Corrected artist").genreNames)
    }
    @Test fun metadataIdentityChangeInvalidatesOnlyAutomaticClassification() {
        assertEquals(listOf("Pop"), song().copy(artist = "Someone else", genreProfile = profile()).genreNames)
    }
    @Test fun codecAndVaultRoundTripPreserveOriginsAndManualChoice() {
        val p = profile(); assertEquals(p, GenreProfileCodec.decode(GenreProfileCodec.encode(p)))
        val s = VaultSong("id", "song", "audio.mp3", "Song", "Artist", null, "Pop", 180, null, genreProfileJson = GenreProfileCodec.encode(p))
        val data = VaultData(updatedAt = 1, songs = listOf(s))
        assertEquals(data, VaultCodec.decode(VaultCodec.encode(data)))
    }
    @Test fun invalidProfileIsRejectedBeforeImport() {
        assertThrows(Exception::class.java) { GenreProfileCodec.decode("{}") }
        assertThrows(Exception::class.java) { GenreProfileCodec.decode(GenreProfileCodec.encode(profile()).replace("RECORDING", "UNRECOGNIZED")) }
    }
    @Test fun durableStoreSurvivesRestartAndRecoversFromDamagedPrimaryWithoutLosingBackup() {
        val dir = Files.createTempDirectory("genre-test").toFile()
        try {
            val file = java.io.File(dir, "profiles.json")
            val store = GenreProfileStore(file); store.load(); store.put("one", profile()); store.put("two", profile())
            file.writeText("damaged")
            val recovered = GenreProfileStore(file); recovered.load(); assertEquals(profile(), recovered.get("one"))
            recovered.put("three", profile())
            val reopened = GenreProfileStore(file); reopened.load(); assertEquals(profile(), reopened.get("three")); assertEquals(profile(), reopened.get("one"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptStoreDoesNotBecomeAnEmptyWritableCatalog() {
        val dir = Files.createTempDirectory("genre-test").toFile()
        try {
            val file = java.io.File(dir, "profiles.json").apply { writeText("damaged") }
            val store = GenreProfileStore(file)
            assertThrows(Exception::class.java) { store.put("one", profile()) }
            assertEquals("damaged", file.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidProfileCannotReplaceAReadableStore() {
        val dir = Files.createTempDirectory("genre-test").toFile()
        try {
            val file = java.io.File(dir, "profiles.json"); val store = GenreProfileStore(file)
            store.put("one", profile()); val original = file.readBytes()
            assertThrows(Exception::class.java) { store.put("two", profile().copy(checkedAt = -1)) }
            assertArrayEquals(original, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun recordingMatchRejectsDifferentVersionAndLengthAndCleansYouTubeAlbumText() {
        assertEquals("Tot Musica", GenreIdentity.cleanTitle("【Ado】Tot Musica（ウタ from ONE PIECE FILM RED）", "Ado"))
        val candidate = JSONObject().put("title", "Song").put("length", 180000)
        assertTrue(GenreIdentity.recordingMatches(song(), "Song", candidate))
        assertFalse(GenreIdentity.recordingMatches(song(), "Song", candidate.put("disambiguation", "live")))
        assertFalse(GenreIdentity.recordingMatches(song(), "Song", candidate.put("disambiguation", "").put("length", 300000)))
    }
    @Test fun titleArtistCanIdentifyMusicWhenTheStoredArtistIsALyricsChannel() {
        assertEquals("Eminem", GenreIdentity.artistFromTitle("Eminem - Without Me (Lyrics)"))
        assertEquals("Ado", GenreIdentity.artistFromTitle("【Ado】Tot Musica"))
        assertEquals("Oliver Tree", GenreIdentity.artistFromTitle("Oliver Tree - Life Goes On (Lyrics)"))
        assertNull(GenreIdentity.artistFromTitle("【Live Performance】Song"))
        assertNull(GenreIdentity.artistFromTitle("【MV】Song"))
        assertEquals("lovely", GenreIdentity.cleanTitle("Billie Eilish - lovely (Lyrics) ft. Khalid", "Billie Eilish"))
    }
    @Test fun channelsNamedLikeBandsDoNotOverrideExplicitPerformersAndReversedTitlesStayCorrect() {
        val title = "Skrillex And Diplo Ft Justin Bieber-Where Are Ü Now (subtitulado)"
        assertEquals("Skrillex", GenreIdentity.lookupArtist(title, "Venom"))
        assertEquals("Where Are Ü Now", GenreIdentity.cleanTitle(title, "Skrillex"))
        assertTrue(GenreIdentity.explicitCollaboration(title))
        assertEquals("Eminem", GenreIdentity.lookupArtist("Venom - Eminem (Lyrics)", "Eminem"))
        assertEquals("Venom", GenreIdentity.cleanTitle("Venom - Eminem (Lyrics)", "Eminem"))
        assertEquals("blink-182", GenreIdentity.artistFromTitle("blink-182 - All The Small Things"))
    }
    @Test fun importedGenreProfilesCanFollowTheVerifiedLocalSongIdentity() {
        val local = song().copy(id = "local", songId = "local", filePath = "/Music/audio.mp3")
        val saved = VaultSong("old", "remote", "audio.mp3", "Song", "Artist", null, "Pop", 180, null,
            genreProfileJson = GenreProfileCodec.encode(profile()))
        val plan = VaultImportPlanner.plan(VaultData(updatedAt = 1, songs = listOf(saved)),
            listOf(com.clashofmusic.app.data.database.entity.DownloadedSongEntity.fromDomain(local)), listOf(local), emptyList(), emptyList())
        assertEquals("local", plan.identities["remote"])
    }
    @Test fun analysisKeepsManualChoicesAndCanBeStopped() = runTest {
        val songs = MutableStateFlow(listOf(song(), song().copy(id = "two", songId = "two", genreProfile = profile().copy(manual = true))))
        var writes = 0
        val repo = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ -> when (method.name) {
            "observeDownloadedSongs" -> songs
            "updateGenreProfile" -> { writes++; Unit }
            else -> error(method.name)
        } } as LibraryRepository
        val lookup = object : GenreLookup { override suspend fun lookup(song: DownloadedSong): List<GenreAssignment> { delay(1000); return profile().entries } }
        val manager = GenreLibraryManager(repo, lookup, backgroundScope)
        manager.start(); runCurrent(); assertEquals(1, manager.state.value.total)
        manager.cancel(); runCurrent(); assertEquals(0, writes); assertFalse(manager.state.value.running)
        manager.start(); advanceTimeBy(1001); runCurrent(); assertEquals(1, writes)
        assertEquals(1, manager.state.value.found)
    }
    @Test fun unavailableServiceStopsAfterThreeFailuresWithoutSavingEmptyGenres() = runTest {
        var writes = 0
        val repo = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ -> when (method.name) {
            "observeDownloadedSongs" -> flowOf(List(5) { song().copy(id = "$it", songId = "$it") })
            "updateGenreProfile" -> { writes++; Unit }
            else -> error(method.name)
        } } as LibraryRepository
        val lookup = object : GenreLookup { override suspend fun lookup(song: DownloadedSong): List<GenreAssignment> = throw java.io.IOException("Offline") }
        val manager = GenreLibraryManager(repo, lookup, backgroundScope)
        manager.start(); runCurrent()
        assertEquals(3, manager.state.value.errors); assertEquals(3, manager.state.value.done)
        assertFalse(manager.state.value.running); assertEquals(0, writes)
    }
}
