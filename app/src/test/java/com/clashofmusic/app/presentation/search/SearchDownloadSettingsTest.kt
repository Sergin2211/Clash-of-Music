package com.clashofmusic.app.presentation.search

import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import java.lang.reflect.Proxy
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchDownloadSettingsTest {
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args ?: emptyArray())
        } as T

    private val song = SongItem("song", "Tot Musica", "Ado", null, 201, "https://youtube.com/watch?v=song")
    private val album = AlbumItem("album", "Uta", "Ado", null, "2022", 1, null)
    private val track = AlbumTrackItem(1, "Tot Musica", "Ado", "Uta", 201, null)

    private class Fixture {
        val settings = MutableStateFlow(AppSettings(defaultFormat = AudioFormat.OPUS, defaultQuality = AudioQuality.MEDIUM_192K))
        val queued = mutableListOf<DownloadTask>()
        val playlists = MutableStateFlow(listOf(Playlist("existing", "Uta", "Ado • Álbum • 2022", isAlbum = true)))
        val relations = mutableListOf<String>()
        var created = 0
        var eligibility = DownloadEligibility.CAN_OVERWRITE_QUALITY
        var matches: List<SongItem>? = null
        var searchError: Exception? = null
        var generation = 0L
        var cancelAfter = Int.MAX_VALUE
        var preparing = 0
    }

    private fun viewModel(f: Fixture) = SearchViewModel(
        musicRepository = fake { name, _ -> when (name) {
            "search" -> { f.searchError?.let { throw it }; f.matches ?: listOf(song) }
            "getAlbumTracks" -> listOf(track)
            else -> error(name)
        } },
        historyRepository = fake { name, _ -> if (name == "observeRecentHistory") flowOf(emptyList<SearchHistoryItem>()) else Unit },
        downloadRepository = fake { name, args -> when (name) {
            "observeTasks" -> flowOf(emptyList<DownloadTask>())
            "beginBatchPreparation" -> { f.preparing++; Unit }
            "endBatchPreparation" -> { f.preparing--; Unit }
            "getCancellationGeneration" -> f.generation
            "checkDownloadBatch" -> { if (args[0] != f.generation) throw DownloadBatchCancelled(); Unit }
            "enqueueDownload" -> {
                if (args.getOrNull(1) is Long && args[1] != f.generation) throw DownloadBatchCancelled()
                (args[0] as DownloadTask).also { f.queued.add(it); if (f.queued.size == f.cancelAfter) f.generation++ }.id
            }
            else -> error(name)
        } },
        libraryRepository = fake { name, _ -> when (name) {
            "observeDownloadedSongs" -> flowOf(emptyList<DownloadedSong>())
            "checkDownloadEligibility" -> f.eligibility
            else -> error(name)
        } },
        playlistRepository = fake { name, args -> when (name) {
            "observePlaylists" -> f.playlists
            "createPlaylist" -> { f.created++; "new" }
            "addSongToPlaylist" -> { f.relations.add(args[0] as String); Unit }
            else -> error(name)
        } },
        progressDispatcher = Dispatchers.Main,
        preferencesRepository = fake { name, _ -> when (name) {
            "observeSettings" -> f.settings
            else -> error(name)
        } }
    )

    private fun check(block: suspend TestScope.(Fixture, SearchViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = Fixture()
            val vm = viewModel(fixture)
            try { runCurrent(); block(fixture, vm); assertEquals("La preparación debe terminar también al cancelar", 0, fixture.preparing) }
            finally { vm.viewModelScope.cancel() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun quickDownloadReadsLatestDefaultAfterSettingsChange() = check { f, vm ->
        vm.downloadSong(song); runCurrent()
        assertEquals(AudioFormat.OPUS, f.queued.last().format)
        f.settings.value = f.settings.value.copy(defaultFormat = AudioFormat.M4A, defaultQuality = AudioQuality.STANDARD_128K)
        vm.downloadSong(song); runCurrent()
        assertEquals(AudioFormat.M4A, f.queued.last().format)
        assertEquals(AudioQuality.BEST_320K, f.queued.last().quality) // Legacy preference is ignored.
    }

    @Test fun cancelAllStopsTheRestOfA401TrackPlaylistWithoutAddingMoreRelations() = check { f, vm ->
        f.cancelAfter = 3
        vm.downloadExtractedPlaylist(RemotePlaylistInfo("bulk", "Bulk", "Artist", trackCount = 401,
            source = PlaylistSource.SPOTIFY, tracks = List(401) { RemoteTrackItem("Tot Musica", "Ado", durationSeconds = 201) }))
        runCurrent()
        assertEquals(3, f.queued.size)
        assertEquals(3, f.relations.size)
        assertFalse(vm.uiState.value.isQueueingLink)
        assertTrue(vm.uiState.value.linkSuccessMessage!!.contains("canceló"))
        vm.downloadExtractedPlaylist(RemotePlaylistInfo("new", "New", "Artist", trackCount = 1,
            source = PlaylistSource.SPOTIFY, tracks = listOf(RemoteTrackItem("Tot Musica", "Ado", durationSeconds = 201))))
        runCurrent()
        assertEquals(4, f.queued.size)
    }

    @Test fun cancelAllAlsoStopsCatalogAlbumPreparation() = check { f, vm ->
        f.cancelAfter = 1
        vm.downloadAlbum(album, List(20) { track.copy(trackNumber = it + 1) })
        runCurrent()
        assertEquals(1, f.queued.size)
        assertEquals(1, f.relations.size)
        assertFalse(vm.uiState.value.isQueueingAlbum)
        assertTrue(vm.uiState.value.resolvingAlbumTracks.isEmpty())
        assertTrue(vm.uiState.value.albumDownloadMessage!!.contains("canceló"))
    }

    @Test fun existingAlbumCanBeQueuedWithDifferentFormatWithoutCreatingDuplicate() = check { f, vm ->
        vm.selectAlbum(album); runCurrent()
        assertEquals(AudioFormat.OPUS, vm.uiState.value.selectedAlbumFormat)
        vm.onAlbumFormatSelected(AudioFormat.M4A)
        vm.downloadAlbum(album, listOf(track)); runCurrent()
        assertEquals(0, f.created)
        assertEquals(listOf("existing"), f.relations)
        assertEquals(AudioFormat.M4A, f.queued.single().format)
        assertEquals(AudioQuality.BEST_320K, f.queued.single().quality)
    }

    @Test fun albumTrackUsesChosenFormatAndSettingsDoNotOverwriteExplicitChoice() = check { f, vm ->
        vm.selectAlbum(album); runCurrent()
        vm.onAlbumFormatSelected(AudioFormat.ORIGINAL)
        f.settings.value = f.settings.value.copy(defaultFormat = AudioFormat.MP3)
        runCurrent()
        vm.downloadAlbumTrack(album, track); runCurrent()
        assertEquals(AudioFormat.ORIGINAL, f.queued.single().format)
    }

    @Test fun matchingAlbumReportsPreservedSongsInsteadOfPretendingToDownload() = check { f, vm ->
        f.eligibility = DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY
        vm.selectAlbum(album); runCurrent()
        vm.downloadAlbum(album, listOf(track)); runCurrent()
        assertTrue(f.queued.isEmpty())
        assertTrue(vm.uiState.value.albumDownloadMessage!!.contains("1 ya coinciden"))
        assertFalse(vm.uiState.value.isQueueingAlbum)
    }

    @Test fun linkDefaultsFollowSettingsUntilUserChoosesFormat() = check { f, vm ->
        assertEquals(AudioFormat.OPUS, vm.uiState.value.selectedLinkFormat)
        vm.onLinkFormatSelected(AudioFormat.M4A)
        f.settings.value = f.settings.value.copy(defaultFormat = AudioFormat.ORIGINAL)
        runCurrent()
        assertEquals(AudioFormat.M4A, vm.uiState.value.selectedLinkFormat)
        vm.downloadExtractedPlaylist(RemotePlaylistInfo("id", "Playlist", "Ado", trackCount = 1,
            source = PlaylistSource.YOUTUBE, tracks = listOf(RemoteTrackItem("Tot Musica", "Ado"))))
        runCurrent()
        assertEquals(AudioFormat.M4A, f.queued.single().format)
    }

    @Test fun spotifyAlbumArtworkSurvivesMatchingToDifferentYoutubeArtwork() = check { f, vm ->
        val albumCover = "https://spotify.test/album.jpg"
        val trackCover = "https://spotify.test/track.jpg"
        f.matches = listOf(song.copy(thumbnailUrl = "https://youtube.test/video.jpg"))
        vm.downloadExtractedPlaylist(RemotePlaylistInfo("spotify-album", "Uta", "Ado",
            thumbnailUrl = albumCover, trackCount = 1, source = PlaylistSource.SPOTIFY, isAlbum = true,
            tracks = listOf(RemoteTrackItem("Tot Musica", "Ado", "Uta", 201,
                "https://open.spotify.com/track/private", trackCover))))
        runCurrent()
        assertEquals(albumCover, f.queued.single().thumbnailUrl)
        assertEquals(trackCover, f.queued.single().artworkFallbackUrl)
        assertEquals("Uta", f.queued.single().album)
        assertEquals(song.url, f.queued.single().url)
    }

    @Test fun albumQueuesCorrectVersionInsteadOfFirstCover() = check { f, vm ->
        f.matches = listOf(song.copy(id = "cover", title = "Tot Musica (Cover)"), song)
        vm.selectAlbum(album); runCurrent()
        vm.downloadAlbum(album, listOf(track)); runCurrent()
        assertEquals("song", f.queued.single().songId)
        assertEquals(1, f.relations.size)
    }

    @Test fun unsafeAlbumMatchCreatesNoRelationOrDownloadAndShowsTrackReason() = check { f, vm ->
        f.playlists.value = emptyList()
        f.matches = emptyList()
        vm.selectAlbum(album); runCurrent()
        vm.downloadAlbum(album, listOf(track)); runCurrent()
        assertTrue(f.queued.isEmpty()); assertTrue(f.relations.isEmpty())
        assertEquals(0, f.created)
        assertTrue(vm.uiState.value.albumTrackMessages[1]!!.contains("No se encontraron resultados"))
        assertFalse(vm.uiState.value.isQueueingAlbum)
    }

    @Test fun individualAlbumTrackDownloadsBestCandidateWhenScoresAreEqual() = check { f, vm ->
        f.matches = listOf(song, song.copy(id = "duplicate"))
        vm.selectAlbum(album); runCurrent()
        vm.downloadAlbumTrack(album, track); runCurrent()
        assertEquals("song", f.queued.single().songId)
        assertTrue(vm.uiState.value.albumTrackMessages[1]!!.contains("Enviada a la cola"))
        assertTrue(vm.uiState.value.resolvingAlbumTracks.isEmpty())
    }

    @Test fun catalogLinkReportsOnlyActualEmptySearches() = check { f, vm ->
        f.matches = emptyList()
        vm.downloadExtractedPlaylist(RemotePlaylistInfo("id", "Playlist", "Ado", trackCount = 1,
            source = PlaylistSource.SPOTIFY, tracks = listOf(RemoteTrackItem("Tot Musica", "Ado", durationSeconds = 201))))
        runCurrent()
        assertTrue(f.queued.isEmpty()); assertTrue(f.relations.isEmpty())
        assertEquals(0, f.created)
        assertTrue(vm.uiState.value.linkSuccessMessage!!.contains("1 sin resultados"))
        assertTrue(vm.uiState.value.linkSuccessMessage!!.contains("Tot Musica"))
        assertFalse(vm.uiState.value.isQueueingLink)
    }

    @Test fun individualSearchFailureIsVisibleAndReleasesControls() = check { f, vm ->
        f.searchError = java.io.IOException("Offline")
        vm.selectAlbum(album); runCurrent()
        vm.downloadAlbumTrack(album, track); runCurrent()
        assertTrue(f.queued.isEmpty())
        assertTrue(vm.uiState.value.albumTrackMessages[1]!!.contains("consultar la fuente"))
        assertTrue(vm.uiState.value.resolvingAlbumTracks.isEmpty())
    }

    @Test fun translatedAlbumTrackWithMissingMetadataStillQueuesAutomatically() = check { f, vm ->
        f.matches = listOf(song.copy(title = "トット・ムジカ", durationSeconds = 0, artist = "Artist alias"))
        vm.selectAlbum(album); runCurrent()
        vm.downloadAlbum(album, listOf(track)); runCurrent()
        assertEquals("song", f.queued.single().songId)
        assertEquals(AudioFormat.OPUS, f.queued.single().format)
        assertEquals(1, f.relations.size)
    }

    @Test fun catalogLinkUsesBestAvailableEvenWhenArtistNameDiffers() = check { f, vm ->
        f.matches = listOf(song.copy(artist = "Artist alias"))
        vm.downloadExtractedPlaylist(RemotePlaylistInfo("id", "Playlist", "Ado", trackCount = 1,
            source = PlaylistSource.SPOTIFY, tracks = listOf(RemoteTrackItem("Tot Musica", "Ado", durationSeconds = 201))))
        runCurrent()
        assertEquals("song", f.queued.single().songId)
        assertTrue(vm.uiState.value.linkSuccessMessage!!.contains("0 sin resultados"))
    }
}
