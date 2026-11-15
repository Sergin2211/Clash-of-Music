package com.clashofmusic.app.data.player

import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RadioTest {
    private fun item(n: Int, artist: String = "Artist $n", title: String = "Song $n") = SongItem(
        n.toString().padStart(11, '0'), title, artist, null, 180, "https://youtube.com/watch?v=${n.toString().padStart(11, '0')}")
    private fun seed() = item(0, "Ado", "Value").toRadioSong()
    private fun source(items: List<SongItem>) = object : RadioRecommendationSource {
        override suspend fun related(song: DownloadedSong) = items
    }
    @Test fun windowKeepsFiveBehindAndTenAheadAtAnyPosition() {
        val entries = (0..500).toList()
        assertEquals((95..110).toList(), RadioWindow.around(entries, 100))
        assertEquals((0..10).toList(), RadioWindow.around(entries, 0))
        assertEquals((495..500).toList(), RadioWindow.around(entries, 500))
        assertTrue(RadioWindow.around(entries, -1).isEmpty())
        assertEquals(16, RadioWindow.MAX_TRACKS)
        assertEquals(96L * 1024 * 1024, RadioWindow.MAX_BYTES)
    }
    @Test fun recommendationBatchCanFillTenAheadButNeverMore() = runBlocking {
        val recommender = RadioRecommender(source((1..30).map { item(it) }))
        val result = recommender.next(seed(), listOf(seed()), 100)
        assertEquals((1..10).map { item(it).id }, result.map { it.songId })
        assertEquals((1..10).toList(), recommender.lastTrace!!.selected.map { it.sourceRank })
    }
    @Test fun radioMetadataCannotBeMistakenForDownloadedAudio() {
        val song = seed()
        assertEquals("RADIO", song.format)
        assertEquals("", song.filePath)
        assertTrue(song.fileUri.startsWith("clashradio://"))
        assertEquals(0L, song.fileSizeBytes)
        assertEquals(RadioRecommender.identity("【Ado】Value", "Ado"), RadioRecommender.identity("Value (Official Audio)", "Ado - Topic"))
    }
    @Test fun avoidsHistorySeedAndDuplicateVersions() = runBlocking {
        val played = item(3).toRadioSong()
        val recommendations = RadioRecommender(source(listOf(item(0), item(3), item(4), item(5, "Artist 4", "Song 4 (Official Audio)"), item(6))))
            .next(seed(), listOf(seed(), played), 5)
        assertEquals(2, recommendations.size)
        assertFalse(recommendations.any { it.songId in setOf(seed().songId, played.songId) })
    }
    @Test fun preservesMusicMixOrderInsteadOfForcingAnotherArtist() = runBlocking {
        val options = (1..4).map { item(it, "Ado") } + item(5, "Other J-Pop artist") + item(6, "Another artist")
        val result = RadioRecommender(source(options)).next(seed(), listOf(seed()), 5)
        assertEquals(5, result.size)
        assertEquals(options.take(5).map { it.id }, result.map { it.songId })
    }
    @Test fun filtersConcertsCoversNonMusicAndLongCompilations() {
        listOf("Song LIVE", "Song cover", "Song slowed", "Interview with singer", "Full album", "Song mashup", "Music Director Reacts | Song").forEach {
            assertFalse(it, RadioRecommender.eligible(item(1, title = it), seed()))
        }
        assertFalse(RadioRecommender.eligible(item(1).copy(sourceTitle = "Song (Official LIVE Video)"), seed()))
        assertFalse(RadioRecommender.eligible(item(1).copy(channelName = "Music Director Reacts"), seed()))
        assertTrue(RadioRecommender.eligible(item(1, title = "Song live"), seed().copy(title = "Value live")))
        assertFalse(RadioRecommender.eligible(item(1).copy(durationSeconds = 3600), seed()))
        assertFalse(RadioRecommender.eligible(item(1).copy(id = "invalid"), seed()))
    }
    @Test fun failedMixDoesNotFallBackToGenreOrArtistSearch() = runBlocking {
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong): List<SongItem> = throw java.io.IOException("offline")
        })
        try { recommender.next(seed(), listOf(seed()), 5); fail() } catch (_: java.io.IOException) { }
        assertTrue(recommender.lastTrace!!.selected.isEmpty())
        assertNotNull(recommender.lastTrace!!.message)
    }
    @Test fun reusesTheSeedMixAndRecordsItsOrderedProvenance() = runBlocking {
        val calls = mutableListOf<String>()
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong): List<SongItem> { calls.add(seed.songId); return (1..12).map { item(it) } }
        })
        val seed = seed().copy(genre = "Rock")
        val first = recommender.next(seed, listOf(seed), 5)
        val second = recommender.next(seed, listOf(seed) + first, 5)
        assertEquals(listOf(seed.songId), calls)
        assertEquals((6..10).map { item(it).id }, second.map { it.songId })
        val trace = recommender.lastTrace!!
        assertEquals(seed.songId, trace.seedId)
        assertEquals((6..10).toList(), trace.selected.map { it.sourceRank })
        assertEquals(10, trace.recentChoices.size)
        val json = org.json.JSONObject(trace.json())
        assertEquals("YOUTUBE_MUSIC_MIX", json.getString("source"))
        assertTrue(json.getString("mixUrl").endsWith("list=RDAMVM${seed.songId}"))
    }
    @Test fun cancellationNeverPublishesAReplacementSource() = runBlocking {
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong): List<SongItem> = throw CancellationException()
        })
        try { recommender.next(seed(), listOf(seed()), 5); fail() } catch (_: CancellationException) { }
        assertNull(recommender.lastTrace)
    }
    @Test fun radioQueueBoundsHistoryWithoutChangingCurrentOccurrenceOrManualPriority() {
        val queue = PlaybackQueue()
        queue.loadRadio(seed())
        queue.appendRadio((1..10).map { item(it).toRadioSong() })
        val current = queue.value.entries[8].id
        queue.select(current)
        queue.enqueue(item(30).toRadioSong(), true)
        queue.trimRadioHistory()
        assertEquals(current, queue.value.currentEntryId)
        assertEquals(5, queue.value.currentIndex)
        assertEquals(item(30).id, queue.value.manualQueue.single().song.songId)
        assertTrue(queue.value.entries.all { it.origin == QueueEntryOrigin.RADIO || it.origin == QueueEntryOrigin.MANUAL })
        queue.clear()
        assertTrue(queue.value.entries.isEmpty())
    }
}
