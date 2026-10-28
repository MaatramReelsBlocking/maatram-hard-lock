package com.maatram.hardlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelTrackerTest {
    @Test fun firstReelAfterOpenCounts() {
        assertTrue(ReelTracker.startsNewReel(0L, 5_000L))
    }

    @Test fun burstOfChangesInOneSwipeCountsOnce() {
        var last = 0L
        var counted = 0
        for (t in listOf(10_000L, 10_100L, 10_200L, 10_300L)) {
            if (ReelTracker.startsNewReel(last, t)) { counted++; last = t }
        }
        assertEquals(1, counted)
    }

    @Test fun nextReelAfterGapCountsAgain() {
        assertTrue(ReelTracker.startsNewReel(10_000L, 10_000L + ReelTracker.IDLE_MS + 1))
        assertFalse(ReelTracker.startsNewReel(10_000L, 10_000L + ReelTracker.IDLE_MS))
    }

    @Test fun newCaptionsMeanNewReel() {
        val old = setOf("FootballMonk.in sponsored caption", "Shop high-quality replica")
        val new = setOf("Another creator's caption here", "Shop the new jersey now")
        assertTrue(ReelTracker.isNewReel(old, new))
    }

    @Test fun sameCaptionsMeanSameReel() {
        val a = setOf("FootballMonk.in sponsored caption", "Shop high-quality replica")
        assertFalse(ReelTracker.isNewReel(a, a))
    }

    @Test fun oneChangedLineIsNotANewReel() {
        // a single changed label (e.g. a timer or button) must not count as a new reel
        val old = setOf("FootballMonk.in sponsored caption", "Shop high-quality replica")
        val new = setOf("FootballMonk.in sponsored caption", "Shop high-quality replica!")
        assertFalse(ReelTracker.isNewReel(old, new))
    }
}
