package com.clashofmusic.app.data.player

import com.clashofmusic.app.domain.model.DownloadedSong
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private fun song(id: String) = DownloadedSong(id, id, id, "Artist", null, 180, null,
        "/Music/$id.mp3", "file:///Music/$id.mp3", "MP3", "320k", 1000, 1)
    private fun queue() = PlaybackQueue { it.reversed() }.apply { load(listOf(song("a"), song("b"), song("c"), song("d")), 0) }

    @Test fun duplicateSongsHaveDistinctOccurrencesAndExactSelection() {
        val queue = queue()
        queue.enqueue(song("b"), false)
        queue.enqueue(song("b"), false)
        val state = queue.value
        assertEquals(state.entries.size, state.entries.map { it.id }.toSet().size)
        val second = state.manualQueue[1]
        queue.select(second.id)
        assertEquals(second.id, queue.value.currentEntryId)
        assertTrue(queue.value.manualQueue.isEmpty())
        assertEquals(listOf("b", "c", "d"), queue.value.followingList.map { it.song.id })
    }

    @Test fun manualQueueIsFifoAndPlayNextTakesPriority() {
        val queue = queue()
        queue.enqueue(song("x"), false)
        queue.enqueue(song("y"), false)
        queue.enqueue(song("z"), true)
        assertEquals(listOf("z", "x", "y", "b", "c", "d"), queue.value.upcoming.map { it.song.id })
        assertEquals(queue.value.upcoming.size, queue.value.manualQueue.size + queue.value.followingList.size)
    }

    @Test fun removalDoesNotDeleteCurrentOrOriginalSongOccurrence() {
        val queue = queue()
        queue.enqueue(song("a"), false)
        queue.enqueue(song("b"), false)
        val originalIds = queue.value.entries.filter { it.origin == QueueEntryOrigin.LIST }.map { it.id }
        queue.removeQueuedEntry(queue.value.currentEntryId!!)
        queue.removeQueuedEntry(queue.value.manualQueue.first().id)
        assertEquals(listOf("b"), queue.value.manualQueue.map { it.song.id })
        queue.clearManualQueue()
        assertEquals(originalIds, queue.value.entries.map { it.id })
    }

    @Test fun automaticTransitionConsumesOnlyTheExactQueuedOccurrence() {
        val queue = queue()
        queue.enqueue(song("b"), false)
        queue.enqueue(song("b"), false)
        val second = queue.value.manualQueue[1].id
        queue.select(queue.value.upcoming.first().id)
        assertEquals(second, queue.value.manualQueue.single().id)
        assertFalse(queue.value.current!!.isQueued)
    }

    @Test fun previewBecomesCurrentAndPreservesAllUpcomingOccurrences() {
        val queue = queue()
        queue.enqueue(song("b"), false)
        val pending = queue.value.upcoming.map { it.id }
        val id = queue.preview(song("preview"))
        assertEquals(id, queue.value.currentEntryId)
        assertEquals("preview", queue.value.current!!.song.id)
        assertEquals(pending, queue.value.upcoming.map { it.id })
        assertTrue(queue.value.manualQueue.single().isQueued)
    }

    @Test fun shuffleOrdersTheActualTimelineAndKeepsManualPriority() {
        val queue = queue()
        val current = queue.value.currentEntryId
        queue.enqueue(song("x"), false)
        queue.setShuffle(true)
        assertEquals(current, queue.value.currentEntryId)
        assertEquals(listOf("x", "d", "c", "b"), queue.value.upcoming.map { it.song.id })
        queue.select(queue.value.upcoming.first().id)
        assertEquals("d", queue.value.upcoming.first().song.id)
        queue.setShuffle(false)
        assertEquals(listOf("b", "c", "d"), queue.value.upcoming.map { it.song.id })
    }

    @Test fun changingShufflePreservesPlayedPrefixAndCurrentOccurrence() {
        val queue = queue()
        queue.select(queue.value.entries[1].id)
        queue.select(queue.value.entries[2].id)
        val prefix = queue.value.entries.take(3)
        queue.setShuffle(true)
        assertEquals(prefix, queue.value.entries.take(3))
        assertEquals(prefix.last().id, queue.value.currentEntryId)
        queue.setShuffle(false)
        assertEquals(prefix, queue.value.entries.take(3))
    }

    @Test fun enablingShuffleAfterAQueueJumpRecoversSkippedListAndManualEntries() {
        val queue = queue()
        queue.enqueue(song("manual"), false)
        val target = queue.value.entries.first { it.song.id == "d" }
        queue.select(target.id)
        queue.setShuffle(true)

        assertEquals(target.id, queue.value.currentEntryId)
        assertEquals(listOf("a", "d", "manual", "c", "b"), queue.value.entries.map { it.song.id })
        assertEquals(listOf("manual"), queue.value.manualQueue.map { it.song.id })
    }

    @Test fun disablingShuffleAfterAQueueJumpRecoversSkippedTracksInOriginalOrder() {
        val queue = queue()
        queue.setShuffle(true)
        val target = queue.value.entries.first { it.song.id == "c" }
        queue.select(target.id)
        queue.setShuffle(false)

        assertEquals(target.id, queue.value.currentEntryId)
        assertEquals(listOf("a", "c", "b", "d"), queue.value.entries.map { it.song.id })
    }

    @Test fun enablingShuffleAfterStartingInTheMiddleRecoversEverySkippedTrack() {
        val queue = PlaybackQueue { it.reversed() }
        val songs = (1..18).map { song(it.toString()) }
        queue.load(songs, 3)
        val current = queue.value.currentEntryId
        queue.setShuffle(true)

        assertEquals(current, queue.value.currentEntryId)
        assertEquals((songs.take(3) + songs.drop(4)).reversed(), queue.value.upcoming.map { it.song })
    }

    @Test fun skippedPrefixReturnsWithoutReplayingTracksAlreadySelectedOrDisplacingManualEntries() {
        val queue = PlaybackQueue { it.reversed() }
        queue.load(listOf(song("a"), song("b"), song("c"), song("d"), song("e")), 2)
        val entries = queue.value.entries
        queue.select(entries[1].id)
        queue.select(entries[2].id)
        queue.select(entries[3].id)
        queue.enqueue(song("manual"), true)
        queue.setShuffle(true)

        assertEquals(listOf("b", "c", "d"), queue.value.entries.take(3).map { it.song.id })
        assertEquals(entries[3].id, queue.value.currentEntryId)
        assertEquals(listOf("manual", "e", "a"), queue.value.upcoming.map { it.song.id })
    }

    @Test fun loadingWhileShuffleIsEnabledAlsoIncludesTracksBeforeTheSelectedOne() {
        val queue = PlaybackQueue { it.reversed() }
        queue.setShuffle(true)
        queue.load(listOf(song("a"), song("b"), song("c")), 1)

        assertEquals("b", queue.value.current?.song?.id)
        assertEquals(listOf("c", "a"), queue.value.upcoming.map { it.song.id })
    }

    @Test fun reloadRetainsShuffleAndClearInvalidatesOldTokens() {
        val queue = queue()
        val staleId = queue.value.currentEntryId!!
        queue.setShuffle(true)
        queue.load(listOf(song("a"), song("b"), song("c")), 0)
        assertEquals(listOf("c", "b"), queue.value.upcoming.map { it.song.id })
        queue.clear()
        assertNull(queue.value.current)
        assertTrue(queue.value.upcoming.isEmpty())
        queue.load(listOf(song("a")), 0)
        assertFalse(queue.select(staleId))
        assertEquals("a", queue.value.current!!.song.id)
    }

    @Test fun returningToHistoryKeepsPendingManualEntriesBeforeTheFollowingList() {
        val queue = queue()
        queue.select(queue.value.entries[2].id)
        queue.enqueue(song("x"), false)
        queue.enqueue(song("y"), false)
        val previous = queue.value.entries[1].id
        queue.select(previous)
        assertEquals(previous, queue.value.currentEntryId)
        assertEquals(listOf("x", "y", "c", "d"), queue.value.upcoming.map { it.song.id })
        assertEquals(queue.value.upcoming, queue.value.manualQueue + queue.value.followingList)
        queue.select(queue.value.upcoming.first().id)
        assertEquals(listOf("y", "c", "d"), queue.value.upcoming.map { it.song.id })
    }

    @Test fun editedMetadataUpdatesAllLocalOccurrencesWithoutChangingTheirIdentityOrPreviewSource() {
        val queue = queue()
        val original = queue.value.current!!.song
        queue.enqueue(original, false)
        queue.preview(original.copy(filePath = "", fileUri = "https://example.org/preview", format = "PREVIEW"))
        val before = queue.value
        val edited = original.copy(title = "Edited", album = "New album", year = "2024")
        queue.updateSongMetadata(edited)
        assertEquals(before.entries.map { it.id }, queue.value.entries.map { it.id })
        assertEquals(before.currentEntryId, queue.value.currentEntryId)
        assertEquals("https://example.org/preview", queue.value.current!!.song.fileUri)
        assertEquals(original.title, queue.value.current!!.song.title)
        assertEquals(2, queue.value.entries.count { it.song == edited })
    }

    @Test fun appendKeepsCurrentOccurrenceAndExistingQueue() {
        val queue = queue()
        queue.enqueue(song("x"), true)
        val entries = queue.value.entries
        val current = queue.value.currentEntryId
        queue.append(listOf(song("a"), song("z")))
        assertEquals(entries, queue.value.entries.take(entries.size))
        assertEquals(current, queue.value.currentEntryId)
        assertEquals(listOf("a", "z"), queue.value.entries.takeLast(2).map { it.song.id })
    }
}
