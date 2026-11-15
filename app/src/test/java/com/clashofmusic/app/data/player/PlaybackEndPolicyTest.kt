package com.clashofmusic.app.data.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackEndPolicyTest {
    @Test fun unarmedBoundaryDoesNotBlockAutoplay() {
        val policy = PlaybackEndPolicy()
        assertFalse(policy.consumeTrackBoundary())
        assertFalse(policy.autoplayBlocked)
    }

    @Test fun endOfTrackStopsOnceAndRemainsBlockedAfterVisibleTimerResets() {
        val policy = PlaybackEndPolicy()
        policy.armEndOfTrack(true)
        assertTrue(policy.consumeTrackBoundary())
        assertFalse(policy.endOfTrackArmed)
        policy.armEndOfTrack(false)
        assertFalse(policy.consumeTrackBoundary())
        assertTrue(policy.autoplayBlocked)
    }

    @Test fun explicitPlaybackCanResumeAfterTimedSleep() {
        val policy = PlaybackEndPolicy()
        policy.timedSleep()
        assertTrue(policy.autoplayBlocked)
        policy.userPlayback()
        assertFalse(policy.autoplayBlocked)
    }

    @Test fun cancellationBeforeBoundaryAllowsNormalTransition() {
        val policy = PlaybackEndPolicy()
        policy.armEndOfTrack(true)
        policy.armEndOfTrack(false)
        assertFalse(policy.consumeTrackBoundary())
        assertFalse(policy.autoplayBlocked)
    }

    @Test fun stoppingClearsBothTheTimerAndSleepLatch() {
        val policy = PlaybackEndPolicy()
        policy.armEndOfTrack(true)
        policy.timedSleep()
        policy.reset()
        assertFalse(policy.endOfTrackArmed)
        assertFalse(policy.autoplayBlocked)
    }
}
