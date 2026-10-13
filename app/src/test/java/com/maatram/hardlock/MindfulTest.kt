package com.maatram.hardlock

import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import org.junit.Assert.assertEquals
import org.junit.Test

class MindfulTest {
    private val s = 1_000L

    @Test fun movingBetweenScreensKeepsCounting() {
        // Open the feed, then a reel: feed paused, reel resumed, feed stopped. Ten minutes in the reel.
        val ev = listOf(
            Triple(ACTIVITY_RESUMED, "Feed", 0L), Triple(ACTIVITY_PAUSED, "Feed", 10 * s),
            Triple(ACTIVITY_RESUMED, "Reel", 10 * s + 500L), Triple(ACTIVITY_STOPPED, "Feed", 11 * s),
            Triple(ACTIVITY_PAUSED, "Reel", 610 * s), Triple(ACTIVITY_STOPPED, "Reel", 611 * s),
        )
        assertEquals(610 * s - 500L, Mindful.foregroundMs(ev, 0L, 700 * s))
    }

    @Test fun countsTheStintRunningNow() {
        val ev = listOf(Triple(ACTIVITY_RESUMED, "Feed", 100 * s))
        assertEquals(50 * s, Mindful.foregroundMs(ev, 0L, 150 * s))
    }

    @Test fun countsFromMidnightWhenOpenAcrossIt() {
        val ev = listOf(Triple(ACTIVITY_PAUSED, "Feed", 300 * s), Triple(ACTIVITY_STOPPED, "Feed", 301 * s))
        assertEquals(300 * s, Mindful.foregroundMs(ev, 0L, 400 * s))
    }

    @Test fun nothingWithoutEvents() {
        assertEquals(0L, Mindful.foregroundMs(emptyList(), 0L, 400 * s))
    }
}
