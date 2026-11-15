package com.clashofmusic.app.services

import com.clashofmusic.app.domain.model.DownloadStatus
import com.clashofmusic.app.domain.model.DownloadStatus.*
import org.junit.Assert.*
import org.junit.Test

class DownloadActivityStateTest {
    private fun phases(vararg statuses: DownloadStatus) = statuses.mapIndexed { i, s -> DownloadPhase("$i", s) }

    @Test fun oldCompletedHistoryDoesNotCreateANewCompletion() {
        val state = DownloadActivityState()
        assertNull(state.update(phases(COMPLETED, FAILED, CANCELLED), 0, false))
        assertEquals(DownloadBadgeState(), state.badge)
    }
    @Test fun counterIncludesQueuedConversionAndSavingButNotPaused() {
        val state = DownloadActivityState()
        assertNull(state.update(phases(QUEUED, DOWNLOADING, PROCESSING, SAVING, PAUSED), 0, false))
        assertEquals(4, state.badge.activeCount)
        assertNull(state.update(phases(COMPLETED, COMPLETED, COMPLETED, COMPLETED, PAUSED), 0, false))
        assertFalse(state.badge.unseenCompletion)
    }
    @Test fun allFinishedCreatesOneUnseenCompletionUntilViewed() {
        val state = DownloadActivityState()
        state.update(phases(DOWNLOADING, QUEUED), 0, false)
        assertEquals(DownloadBatchResult(2, 0, 0), state.update(phases(COMPLETED, COMPLETED), 0, false))
        assertEquals(DownloadBadgeState(0, true), state.badge)
        assertNull(state.update(phases(COMPLETED, COMPLETED), 0, false))
        state.update(phases(COMPLETED, COMPLETED), 0, true)
        assertFalse(state.badge.unseenCompletion)
    }
    @Test fun completionAlreadyViewedDoesNotLeaveACheckmark() {
        val state = DownloadActivityState()
        state.update(phases(QUEUED), 0, true)
        assertNotNull(state.update(phases(COMPLETED), 0, true))
        assertFalse(state.badge.unseenCompletion)
    }
    @Test fun albumPreparationGapsDoNotFinishTheBatchBetweenTracks() {
        val state = DownloadActivityState()
        state.update(phases(DOWNLOADING), 1, false)
        assertNull(state.update(phases(COMPLETED), 1, false))
        state.update(phases(COMPLETED, QUEUED), 1, false)
        assertNull(state.update(phases(COMPLETED, COMPLETED), 1, false))
        assertEquals(DownloadBatchResult(2, 0, 0), state.update(phases(COMPLETED, COMPLETED), 0, false))
    }
    @Test fun cancellationsAndErrorsAreNotReportedAsSuccessfulDownloads() {
        val state = DownloadActivityState()
        state.update(phases(QUEUED, QUEUED, DOWNLOADING), 0, false)
        assertEquals(DownloadBatchResult(0, 1, 2), state.update(phases(CANCELLED, CANCELLED, FAILED), 0, false))
        assertFalse(state.badge.unseenCompletion)
    }
    @Test fun mixedOutcomeCountsOnlyTheActualResults() {
        val state = DownloadActivityState()
        state.update(phases(DOWNLOADING, QUEUED, QUEUED), 0, false)
        assertEquals(DownloadBatchResult(1, 1, 1), state.update(phases(COMPLETED, FAILED, CANCELLED), 0, false))
        assertTrue(state.badge.unseenCompletion)
    }
    @Test fun unseenCompletionSurvivesRestartAndNewBatchesDoNotRecountHistory() {
        val state = DownloadActivityState(true)
        state.update(phases(COMPLETED), 0, false)
        assertTrue(state.badge.unseenCompletion)
        state.update(phases(COMPLETED, QUEUED), 0, true)
        assertEquals(DownloadBatchResult(1, 0, 0), state.update(phases(COMPLETED, COMPLETED), 0, false))
    }
    @Test fun clearingFinishedRowsDoesNotTurnTheirSuccessfulResultIntoACancellation() {
        val state = DownloadActivityState()
        state.update(phases(DOWNLOADING, QUEUED), 0, false)
        state.update(phases(COMPLETED, DOWNLOADING), 0, false)
        assertEquals(DownloadBatchResult(2, 0, 0), state.update(listOf(DownloadPhase("1", COMPLETED)), 0, false))
    }
}
