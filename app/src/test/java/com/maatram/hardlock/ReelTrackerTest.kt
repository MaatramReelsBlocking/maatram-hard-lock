package com.maatram.hardlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelTrackerTest {
    @Test fun firstScrollStartsAReel() {
        assertTrue(ReelTracker.startsNewReel(0L, 5_000L))
    }

    @Test fun scrollBurstFromOneSwipeCountsOnce() {
        // events 100 ms apart are one swipe: only the first starts a reel
        var last = 0L
        var counted = 0
        for (t in listOf(10_000L, 10_100L, 10_200L, 10_300L)) {
            if (ReelTracker.startsNewReel(last, t)) counted++
            last = t
        }
        assertEquals(1, counted)
    }

    @Test fun nextSwipeAfterPauseCountsAgain() {
        assertTrue(ReelTracker.startsNewReel(10_300L, 10_300L + ReelTracker.IDLE_MS + 1))
        assertFalse(ReelTracker.startsNewReel(10_300L, 10_300L + ReelTracker.IDLE_MS))
    }
}
