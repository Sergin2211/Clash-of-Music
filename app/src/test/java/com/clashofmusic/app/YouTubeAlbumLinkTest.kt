package com.clashofmusic.app

import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class YouTubeAlbumLinkTest {
    private val albumUrl = "https://music.youtube.com/playlist?list=OLAK5uy_l8fQyUWZfOO25E9HeDeeJCIDm0tsxwguc&si=example"

    @Test fun missingUploaderDoesNotHideAlbumTracks() = runBlocking {
        val source = YouTubeExtractorDataSource { StubPlaylist(it, missingUploader = true) }
        val result = source.extractPlaylist(albumUrl).getOrThrow()
        assertEquals("Test Album", result.title)
        assertEquals("Test Artist", result.author)
        assertTrue(result.isAlbum)
        assertEquals(2, result.trackCount)
        assertEquals(listOf("First Song", "Second Song"), result.tracks.map { it.title })
        assertTrue(result.tracks.all { it.album == "Test Album" })
    }

    @Test fun missingArtworkAndDescriptionDoNotHideAlbumTracks() = runBlocking {
        val source = YouTubeExtractorDataSource { StubPlaylist(it, missingOptionalFields = true) }
        val result = source.extractPlaylist(albumUrl).getOrThrow()
        assertEquals(2, result.trackCount)
        assertNull(result.description)
    }

    @Test fun userPlaylistNeverBecomesTrackAlbum() = runBlocking {
        val source = YouTubeExtractorDataSource { StubPlaylist(it) }
        val result = source.extractPlaylist("https://www.youtube.com/playlist?list=PL12345678901234567890123456789012").getOrThrow()
        assertFalse(result.isAlbum)
        assertEquals("Playlist Owner", result.author)
        assertTrue(result.tracks.all { it.album == null })
    }

    @Test fun emptyAlbumIsFailureInsteadOfSuccessfulSingleTrackFallback() = runBlocking {
        val source = YouTubeExtractorDataSource { StubPlaylist(it, empty = true) }
        assertTrue(source.extractPlaylist(albumUrl).isFailure)
    }

    @Test fun cancelledAlbumRequestIsNotReportedAsExtractionFailure() {
        val source = YouTubeExtractorDataSource { throw CancellationException("New link selected") }
        assertThrows(CancellationException::class.java) { runBlocking { source.extractPlaylist(albumUrl) } }
    }

    @Test fun readsAllPagesAndPreservesRepeatedSongsAsSeparatePositions() = runBlocking {
        val result = YouTubeExtractorDataSource { StubPlaylist(it, paginated = true) }.extractPlaylist(albumUrl).getOrThrow()
        assertEquals(listOf("First Song", "Second Song", "Second Song", "Third Song", "Fourth Song"), result.tracks.map { it.title })
        assertEquals(5, result.trackCount)
    }
    @Test fun laterPageFailureDoesNotReturnAnApparentlyCompletePartialAlbum() = runBlocking {
        assertTrue(YouTubeExtractorDataSource { StubPlaylist(it, paginated = true, failLater = true) }.extractPlaylist(albumUrl).isFailure)
    }
    @Test fun repeatedContinuationFailsInsteadOfLoopingForever() = runBlocking {
        assertTrue(YouTubeExtractorDataSource { StubPlaylist(it, paginated = true, repeatPage = true) }.extractPlaylist(albumUrl).isFailure)
    }

    @Test fun itemParsingErrorsDoNotDiscardOtherPages() = runBlocking {
        val result = YouTubeExtractorDataSource { StubPlaylist(it, paginated = true, itemError = true) }
            .extractPlaylist(albumUrl).getOrThrow()
        assertEquals(5, result.trackCount)
        assertNotNull(result.readWarning)
    }
    @Test fun recoversMissingItemsUsingAlternateReader() = runBlocking {
        var calls = 0
        val result = YouTubeExtractorDataSource(playlistRecovery = { calls++
            com.clashofmusic.app.domain.model.RemotePlaylistInfo("id", "Recovered", "Owner", trackCount = 402,
                source = com.clashofmusic.app.domain.model.PlaylistSource.YOUTUBE,
                tracks = List(402) { com.clashofmusic.app.domain.model.RemoteTrackItem("Track $it", "Artist") })
        }, playlistExtractorFactory = { StubPlaylist(it, paginated = true, itemError = true) }).extractPlaylist(albumUrl).getOrThrow()
        assertEquals(1, calls)
        assertEquals(402, result.tracks.size)
    }
    @Test fun brokenAlternateReaderStillKeepsReadableTracksWithWarning() = runBlocking {
        val result = YouTubeExtractorDataSource(playlistRecovery = { throw java.io.IOException("Offline") },
            playlistExtractorFactory = { StubPlaylist(it, paginated = true, itemError = true) }).extractPlaylist(albumUrl).getOrThrow()
        assertEquals(5, result.trackCount)
        assertNotNull(result.readWarning)
    }

    private class StubPlaylist(
        url: String,
        private val missingUploader: Boolean = false,
        private val missingOptionalFields: Boolean = false,
        private val empty: Boolean = false,
        private val paginated: Boolean = false,
        private val failLater: Boolean = false,
        private val repeatPage: Boolean = false,
        private val itemError: Boolean = false
    ) : PlaylistExtractor(ServiceList.YouTube, ServiceList.YouTube.playlistLHFactory.fromUrl(url)) {
        override fun onFetchPage(downloader: Downloader) = Unit
        override fun getName() = "Test Album"
        override fun getUploaderName(): String {
            if (missingUploader) throw ParsingException("Could not get playlist uploader name")
            return "Playlist Owner"
        }
        override fun getUploaderUrl() = "https://www.youtube.com/channel/test"
        override fun getUploaderAvatars(): List<Image> = emptyList()
        override fun isUploaderVerified() = false
        override fun getStreamCount() = if (empty) 0L else 2L
        override fun getDescription(): Description {
            if (missingOptionalFields) throw ParsingException("Missing description")
            return Description("", Description.PLAIN_TEXT)
        }
        override fun getThumbnails(): List<Image> {
            if (missingOptionalFields) throw ParsingException("Missing artwork")
            return emptyList()
        }
        override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
            val tracks = if (empty) emptyList() else listOf(
                track("dQw4w9WgXcQ", "First Song"), track("abcdefghijk", "Second Song")
            )
            return InfoItemsPage(tracks, if (paginated) Page("https://example.org/page/2") else null, emptyList())
        }
        override fun getPage(page: Page): InfoItemsPage<StreamInfoItem> {
            if (failLater) throw java.io.IOException("Page unavailable")
            if (page.url.endsWith("2")) return InfoItemsPage(listOf(track("abcdefghijk", "Second Song"), track("third123456", "Third Song")),
                Page(if (repeatPage) "https://example.org/page/2" else "https://example.org/page/3"), if (itemError) listOf(ParsingException("Missing item title")) else emptyList())
            return InfoItemsPage(listOf(track("fourth12345", "Fourth Song")), null, emptyList())
        }
        private fun track(id: String, title: String) = StreamInfoItem(
            ServiceList.YouTube.serviceId, "https://www.youtube.com/watch?v=$id", title, StreamType.AUDIO_STREAM
        ).apply {
            uploaderName = "Test Artist"
            duration = 180L
        }
    }
}
