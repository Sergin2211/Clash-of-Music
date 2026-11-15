package com.clashofmusic.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class ToneSelectionTest {
    @Test fun parsesSecondsMinutesHoursAndMillisecondPrecision() {
        assertEquals(15500L, ToneTime.parse("15.5"))
        assertEquals(62500L, ToneTime.parse("1:02,5"))
        assertEquals(3723004L, ToneTime.parse("1:02:03.004"))
        assertEquals(6000000L, ToneTime.parse("100:00"))
    }
    @Test fun rejectsMalformedOrOverflowingTimes() {
        listOf("", "-1", "1:60", "1:60:00", "2:30.1234", "1e9", "00::03", "999999999999999999999999:00").forEach {
            assertNull(it, ToneTime.parse(it))
        }
    }
    @Test fun displayedTimesRoundTripWithoutLocaleOrPrecisionLoss() {
        listOf(0L, 1L, 999L, 1000L, 62500L, 3600001L, 123456789L).forEach {
            assertEquals(it, ToneTime.parse(ToneTime.format(it)))
        }
        assertEquals("1.250", ToneTime.seconds(1250))
    }
    @Test fun selectionAllowsAnInteriorFragmentAndTheWholeSong() {
        assertEquals(1500L, ToneSelection(2250, 3750).validate(6000).durationMs)
        assertEquals(6000L, ToneSelection(0, 6000).validate(6000).durationMs)
    }
    @Test fun rejectsEmptyReversedOutsideAndTooShortSelections() {
        listOf(ToneSelection(0, 0), ToneSelection(3000, 2000), ToneSelection(-1, 2000),
            ToneSelection(0, 6001), ToneSelection(2000, 2999)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate(6000) }
        }
    }
    @Test fun shortSourceCanStillBeUsedInFull() {
        assertEquals(500L, ToneSelection(0, 500).validate(500).durationMs)
        assertThrows(IllegalArgumentException::class.java) { ToneSelection(1, 500).validate(500) }
    }
}
