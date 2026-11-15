package com.clashofmusic.app.presentation.search

import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDownloadIdentityTest {
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args.orEmpty())
        } as T
    private val album = AlbumItem("catalog", "Keisoudo", "ZUTOMAYO", null, null, 18, null)
    private val tracks = (1..18).map { AlbumTrackItem(it, if (it == 1) "Almost Human" else if (it == 2) "Antimony" else "Track $it", album.artist, album.title, 200L + it, null) }
    private val remote = RemotePlaylistInfo("spotify", album.title, album.artist, trackCount = 18,
        source = PlaylistSource.SPOTIFY, isAlbum = true,
        tracks = tracks.map { RemoteTrackItem(it.title, it.artist, it.album, it.durationSeconds) })
    private fun saved(track: AlbumTrackItem, format: String = "MP3") = DownloadedSong(
        "video%06d".format(track.trackNumber), "video%06d".format(track.trackNumber), track.title, track.artist,
        track.album, track.durationSeconds, null, "/private/${track.trackNumber}", "private", format, "123k", 100L, 1L)

    private inner class Fixture {
        val songs = MutableStateFlow(tracks.map { saved(it) })
        val tasks = MutableStateFlow(emptyList<DownloadTask>())
        val queued = mutableListOf<DownloadTask>()
        val missing = mutableSetOf<String>()
        var searches = 0
        var created = 0
        var selectedSource = "newvideo001"
        val vm = SearchViewModel(
            musicRepository = fake { name, args -> when (name) {
                "getAlbumTracks" -> tracks
                "extractRemotePlaylist" -> remote
                "search" -> { searches++; listOf(SongItem(selectedSource, (args[0] as String).substringAfter(" - "), album.artist, null, 201, "https://youtube.com/watch?v=$selectedSource")) }
                else -> error(name)
            } },
            historyRepository = fake { name, _ -> if (name == "observeRecentHistory") flowOf(emptyList<SearchHistoryItem>()) else error(name) },
            preferencesRepository = fake { name, _ -> if (name == "observeSettings") flowOf(AppSettings(defaultFormat = AudioFormat.MP3)) else error(name) },
            libraryRepository = fake { name, args -> when (name) {
                "observeDownloadedSongs" -> songs
                "checkDownloadEligibility" -> {
                    val song = songs.value.firstOrNull { it.songId == args[0] }
                    when { song == null || song.songId in missing -> DownloadEligibility.CAN_DOWNLOAD
                        song.format.equals((args[1] as AudioFormat).extension, true) -> DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY
                        else -> DownloadEligibility.CAN_OVERWRITE_QUALITY }
                }
                else -> error(name)
            } },
            downloadRepository = fake { name, args -> when (name) {
                "observeTasks" -> tasks
                "getCancellationGeneration" -> 0L
                "checkDownloadBatch" -> Unit
                "beginBatchPreparation", "endBatchPreparation" -> Unit
                "enqueueDownload" -> { val task = args[0] as DownloadTask; queued.add(task); tasks.value = tasks.value + task; task.id }
                else -> error(name)
            } },
            playlistRepository = fake { name, _ -> when (name) {
                "observePlaylists" -> flowOf(listOf(Playlist("existing", album.title,
                    "Importado de SPOTIFY: ${album.artist}", isAlbum = true)))
                "createPlaylist" -> { created++; "new" }
                "addSongToPlaylist" -> Unit
                else -> error(name)
            } }, progressDispatcher = Dispatchers.Main)
    }
    private fun check(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try { runCurrent(); block(fixture) }
        finally { fixture.vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun formatSwitchKeepsExactly18SourcesEvenWhenSearchWouldReturnUnrelatedAudio() = check { f ->
        f.vm.onLinkFormatSelected(AudioFormat.OPUS)
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertEquals(18, f.queued.size)
        assertEquals(18, f.queued.map { it.songId }.toSet().size)
        assertEquals(f.songs.value.map { it.songId }, f.queued.map { it.songId })
        assertTrue(f.queued.all { it.format == AudioFormat.OPUS })
        assertEquals(0, f.searches)
        assertEquals(0, f.created)
    }

    @Test fun completeLinkedAlbumIsDisabledAndCallingAgainCannotEnqueueIt() = check { f ->
        f.vm.extractLink("private"); runCurrent()
        assertTrue(f.vm.uiState.value.linkDownloadState.complete)
        assertFalse(f.vm.uiState.value.linkDownloadState.canDownload)
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertTrue(f.queued.isEmpty())
        assertEquals(0, f.searches)
    }

    @Test fun externalDeletionEnablesOnlyMissingTrackWithoutChangingSource() = check { f ->
        f.vm.extractLink("private"); runCurrent()
        f.missing.add(f.songs.value[1].songId)
        f.vm.refreshDownloadAvailability(); runCurrent()
        assertEquals(1, f.vm.uiState.value.linkDownloadState.missing)
        assertTrue(f.vm.uiState.value.linkDownloadState.canDownload)
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertEquals(listOf(f.songs.value[1].songId), f.queued.map { it.songId })
        assertEquals(0, f.searches)
    }

    @Test fun deletedCatalogRowDownloadsOnlyMissingTrack() = check { f ->
        f.songs.value = f.songs.value.drop(1); runCurrent()
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertEquals(1, f.queued.size)
        assertEquals("Almost Human", f.queued.single().title)
        assertEquals(1, f.searches)
    }

    @Test fun searchAlbumSupportsCompleteDifferentFormatAndPartialStates() = check { f ->
        f.vm.selectAlbum(album); runCurrent()
        assertTrue(f.vm.uiState.value.albumDownloadState.complete)
        f.vm.onAlbumFormatSelected(AudioFormat.OPUS); runCurrent()
        assertEquals(18, f.vm.uiState.value.albumDownloadState.missing)
        assertTrue(f.vm.uiState.value.albumDownloadState.canDownload)
        f.vm.onAlbumFormatSelected(AudioFormat.MP3); runCurrent()
        f.missing.add(f.songs.value[0].songId)
        f.vm.refreshDownloadAvailability(); runCurrent()
        assertEquals(1, f.vm.uiState.value.albumDownloadState.missing)
        f.vm.downloadAlbum(album, tracks); runCurrent()
        assertEquals(1, f.queued.size)
        assertEquals(0, f.searches)
    }

    @Test fun activeAlbumStaysBusyAfterPreparationAndUpdatesOnCompletionOrFailure() = check { f ->
        f.vm.extractLink("private"); runCurrent()
        f.vm.onLinkFormatSelected(AudioFormat.OPUS)
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertFalse(f.vm.uiState.value.isQueueingLink)
        assertTrue(f.vm.uiState.value.linkDownloadState.active)
        assertFalse(f.vm.uiState.value.linkDownloadState.canDownload)
        f.tasks.value = f.tasks.value.map { it.copy(status = DownloadStatus.COMPLETED) }
        f.songs.value = f.songs.value.map { it.copy(format = "OPUS") }; runCurrent()
        assertTrue(f.vm.uiState.value.linkDownloadState.complete)
        assertFalse(f.vm.uiState.value.linkDownloadState.active)
        f.missing.add(f.songs.value[0].songId)
        f.vm.refreshDownloadAvailability(); runCurrent()
        assertTrue(f.vm.uiState.value.linkDownloadState.canDownload)
    }

    @Test fun retriesDoNotCreateAnotherAlbumPlaylist() = check { f ->
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertEquals(0, f.created)
        assertTrue(f.queued.isEmpty())
    }

    @Test fun sharedRecordingAcrossAlbumsKeepsTheSameSource() = check { f ->
        f.songs.value = f.songs.value.map { it.copy(album = "Another collection") }; runCurrent()
        f.vm.onLinkFormatSelected(AudioFormat.OPUS)
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertEquals(f.songs.value.map { it.songId }, f.queued.map { it.songId })
        assertEquals(0, f.searches)
    }

    @Test fun historicalDuplicateUsesDurationToPreferTheCorrectRecording() = check { f ->
        val correct = f.songs.value.first()
        f.songs.value = listOf(correct.copy(id = "wrong000001", songId = "wrong000001", durationSeconds = 90)) + f.songs.value
        runCurrent()
        f.vm.onLinkFormatSelected(AudioFormat.OPUS)
        f.vm.downloadExtractedPlaylist(remote); runCurrent()
        assertEquals(correct.songId, f.queued.first().songId)
        assertEquals(18, f.queued.size)
        assertEquals(0, f.searches)
        assertEquals(19, f.songs.value.size) // Never delete historical personal files automatically.
    }

    @Test fun matchingSourceCannotBeReusedForAnotherCatalogTrack() = check { f ->
        f.songs.value = emptyList(); runCurrent()
        f.vm.downloadExtractedPlaylist(remote.copy(tracks = remote.tracks.take(2))); runCurrent()
        assertEquals(1, f.queued.size)
        assertTrue(f.vm.uiState.value.linkSuccessMessage!!.contains("1 sin resultados"))
    }
}
