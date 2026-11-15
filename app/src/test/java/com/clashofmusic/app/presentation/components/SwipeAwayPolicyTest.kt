package com.clashofmusic.app.presentation.components

import org.junit.Assert.*
import org.junit.Test

class SwipeAwayPolicyTest {
    @Test fun initialDominantDirectionLocksRightLeftOrDown() {
        assertEquals(SwipeAwayDirection.RIGHT, SwipeAwayPolicy.direction(24f, 10f, true))
        assertEquals(SwipeAwayDirection.LEFT, SwipeAwayPolicy.direction(-24f, 10f, true))
        assertEquals(SwipeAwayDirection.DOWN, SwipeAwayPolicy.direction(10f, 24f, true))
        assertNull(SwipeAwayPolicy.direction(10f, -24f, true))
        assertNull(SwipeAwayPolicy.direction(10f, 24f, false))
    }
    @Test fun reversingCanReturnToOriginButCannotCrossToAnotherDirection() {
        assertEquals(0f, SwipeAwayDirection.RIGHT.constrain(-10f), 0f)
        assertEquals(0f, SwipeAwayDirection.LEFT.constrain(10f), 0f)
        assertEquals(0f, SwipeAwayDirection.DOWN.constrain(-10f), 0f)
    }
    @Test fun slowShortDragReturnsAndLongDragExitsInBothHorizontalDirections() {
        for (direction in listOf(SwipeAwayDirection.LEFT, SwipeAwayDirection.RIGHT)) {
            assertFalse(SwipeAwayPolicy.dismiss(direction, 70f * direction.sign, 0f, 360f, 1f))
            assertTrue(SwipeAwayPolicy.dismiss(direction, 160f * direction.sign, 0f, 360f, 1f))
        }
    }
    @Test fun deliberateFlickCanDismissWithoutCoveringHalfTheScreen() {
        assertTrue(SwipeAwayPolicy.dismiss(SwipeAwayDirection.RIGHT, 60f, 1500f, 360f, 1f))
        assertTrue(SwipeAwayPolicy.dismiss(SwipeAwayDirection.LEFT, -60f, -1500f, 360f, 1f))
        assertFalse(SwipeAwayPolicy.dismiss(SwipeAwayDirection.RIGHT, 8f, 2000f, 360f, 1f))
    }
    @Test fun flickBackCancelsInsteadOfFinishingACompletedDistance() {
        assertFalse(SwipeAwayPolicy.dismiss(SwipeAwayDirection.RIGHT, 200f, -1500f, 360f, 1f))
        assertFalse(SwipeAwayPolicy.dismiss(SwipeAwayDirection.LEFT, -200f, 1500f, 360f, 1f))
    }
    @Test fun downRequiresIntentionalDistanceEvenForASmallNotice() {
        assertFalse(SwipeAwayPolicy.dismiss(SwipeAwayDirection.DOWN, 40f, 0f, 64f, 1f))
        assertTrue(SwipeAwayPolicy.dismiss(SwipeAwayDirection.DOWN, 80f, 0f, 64f, 1f))
    }
    @Test fun bottomNoticeCanExitBeforeTheFingerReachesSystemNavigation() {
        assertTrue(SwipeAwayPolicy.dismiss(SwipeAwayDirection.DOWN, 25f, 0f, 80f, 1f, availableTravel = 40f))
        assertFalse(SwipeAwayPolicy.dismiss(SwipeAwayDirection.DOWN, 5f, 0f, 80f, 1f, availableTravel = 40f))
    }
    @Test fun horizontalDismissNeeds25PercentLessTravelInBothDirections() {
        for (direction in listOf(SwipeAwayDirection.RIGHT, SwipeAwayDirection.LEFT)) {
            assertTrue(SwipeAwayPolicy.dismiss(direction, 120f * direction.sign, 0f, 360f, 1f))
            assertFalse(SwipeAwayPolicy.dismiss(direction, 100f * direction.sign, 0f, 360f, 1f))
        }
    }
    @Test fun noticeDismissAlsoNeeds25PercentLessTravel() {
        assertTrue(SwipeAwayPolicy.dismiss(SwipeAwayDirection.DOWN, 60f, 0f, 64f, 1f))
        assertFalse(SwipeAwayPolicy.dismiss(SwipeAwayDirection.DOWN, 45f, 0f, 64f, 1f))
    }
    @Test fun thresholdsScaleWithScreenDensity() {
        assertEquals(SwipeAwayPolicy.dismiss(SwipeAwayDirection.RIGHT, 70f, 1500f, 360f, 1f),
            SwipeAwayPolicy.dismiss(SwipeAwayDirection.RIGHT, 210f, 4500f, 1080f, 3f))
    }
}
