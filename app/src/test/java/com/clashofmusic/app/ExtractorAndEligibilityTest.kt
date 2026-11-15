package com.clashofmusic.app

import com.clashofmusic.app.data.extractor.SpotifyExtractorDataSource
import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.AudioQuality
import com.clashofmusic.app.domain.model.DownloadEligibility
import org.junit.Assert.*
import org.junit.Test

class ExtractorAndEligibilityTest {

    private val youtubeDataSource = YouTubeExtractorDataSource()
    private val spotifyDataSource = SpotifyExtractorDataSource()

    @Test
    fun testYouTubeCleanTitle() {
        val rawTitle1 = "Artist Name - Song Title [Official Video]"
        val clean1 = youtubeDataSource.cleanTitle(rawTitle1)
        assertEquals("Song Title", clean1)

        val rawTitle2 = "Band - Epic Track (Audio Oficial) 4K"
        val clean2 = youtubeDataSource.cleanTitle(rawTitle2)
        assertEquals("Epic Track", clean2)

        val rawTitle3 = "Solo Title (Explicit)"
        val clean3 = youtubeDataSource.cleanTitle(rawTitle3)
        assertEquals("Solo Title", clean3)
    }

    @Test
    fun testYouTubeParseArtist() {
        val rawTitle = "Queen - Bohemian Rhapsody"
        val artist = youtubeDataSource.parseArtistFromTitle(rawTitle)
        assertEquals("Queen", artist)

        val rawWithoutDash = "Bohemian Rhapsody"
        val noArtist = youtubeDataSource.parseArtistFromTitle(rawWithoutDash)
        assertNull(noArtist)
    }

    @Test
    fun testYouTubeVideoIdExtraction() {
        val url1 = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", youtubeDataSource.extractVideoId(url1))

        val url2 = "https://youtu.be/dQw4w9WgXcQ?si=abcdef"
        assertEquals("dQw4w9WgXcQ", youtubeDataSource.extractVideoId(url2))

        val url3 = "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", youtubeDataSource.extractVideoId(url3))
    }

    @Test
    fun testYouTubePlaylistNormalization() {
        val ytMusicList = "https://music.youtube.com/playlist?list=OLAK5uy_kQ4d123"
        assertEquals("https://www.youtube.com/playlist?list=OLAK5uy_kQ4d123", youtubeDataSource.normalizePlaylistUrl(ytMusicList))

        val ytMusicBrowse = "https://music.youtube.com/browse/VLOLAK5uy_kQ4d123"
        assertEquals("https://www.youtube.com/playlist?list=OLAK5uy_kQ4d123", youtubeDataSource.normalizePlaylistUrl(ytMusicBrowse))

        val regularYt = "https://www.youtube.com/playlist?list=PL12345"
        assertEquals("https://www.youtube.com/playlist?list=PL12345", youtubeDataSource.normalizePlaylistUrl(regularYt))
    }

    @Test
    fun testSpotifyUrlDetection() {
        val validPlaylist = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=12345"
        assertTrue(spotifyDataSource.isSpotifyUrl(validPlaylist))

        val validAlbum = "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"
        assertTrue(spotifyDataSource.isSpotifyUrl(validAlbum))

        val validRegionalAlbum = "https://open.spotify.com/intl-es/album/0JGOiO34nwfUdDrD612dOp?si=abcdef12345"
        assertTrue(spotifyDataSource.isSpotifyUrl(validRegionalAlbum))

        val validRegionalPlaylist = "https://open.spotify.com/intl-pt/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertTrue(spotifyDataSource.isSpotifyUrl(validRegionalPlaylist))

        val validShortened = "https://spotify.link/AbCdEf123"
        assertTrue(spotifyDataSource.isSpotifyUrl(validShortened))

        val invalid = "https://youtube.com/watch?v=123"
        assertFalse(spotifyDataSource.isSpotifyUrl(invalid))
    }

    @Test
    fun testAudioQualityBitrateConversion() {
        assertEquals(AudioQuality.BEST_320K, AudioQuality.fromBitrate("320k"))
        assertEquals(AudioQuality.HIGH_256K, AudioQuality.fromBitrate("256k"))
        assertEquals(AudioQuality.MEDIUM_192K, AudioQuality.fromBitrate("192k"))
        assertEquals(AudioQuality.STANDARD_128K, AudioQuality.fromBitrate("128k"))
        assertEquals(AudioQuality.BEST_320K, AudioQuality.fromBitrate("unknown"))
    }

    @Test
    fun testDownloadTaskStatusAndRunningState() {
        val queuedTask = com.clashofmusic.app.domain.model.DownloadTask(
            id = "task1",
            songId = "song1",
            title = "Test Song",
            artist = "Test Artist",
            url = "https://youtube.com/watch?v=123",
            status = com.clashofmusic.app.domain.model.DownloadStatus.QUEUED
        )
        // Queued task must be active (tracked in download list) but NOT running
        assertTrue(queuedTask.isActive)
        assertFalse(queuedTask.isRunning)

        val downloadingTask = queuedTask.copy(status = com.clashofmusic.app.domain.model.DownloadStatus.DOWNLOADING)
        assertTrue(downloadingTask.isActive)
        assertTrue(downloadingTask.isRunning)

        val completedTask = queuedTask.copy(status = com.clashofmusic.app.domain.model.DownloadStatus.COMPLETED)
        assertFalse(completedTask.isActive)
        assertFalse(completedTask.isRunning)

        // Verify queue simulation: when 1 task is queued and 0 running, running count is 0
        val tasks = listOf(queuedTask)
        val runningCount = tasks.count { it.isRunning }
        val maxConcurrent = 1
        assertTrue(runningCount < maxConcurrent)
    }
}
