package com.maatram.hardlock

import org.junit.Assert.assertEquals
import org.junit.Test

class LockTimingTest {
    private val min = 60_000L
    private val cap = LockManager.MAX_MINUTES * min + min
    private val wall = 1_700_000_000_000L
    private val up = 5_000_000L

    @Test fun notLockedWhenNoEnd() {
        assertEquals(0L, LockManager.remaining(0L, up + 10 * min, true, up, wall))
    }

    @Test fun sameBootUsesUptimeClock() {
        // Wall clock moved 1 hour forward in Settings: the lock still has 10 minutes.
        assertEquals(10 * min, LockManager.remaining(wall + 10 * min, up + 10 * min, true, up, wall + 60 * min))
        // Wall clock moved back: the lock is not stretched.
        assertEquals(10 * min, LockManager.remaining(wall + 10 * min, up + 10 * min, true, up, wall - 60 * min))
    }

    @Test fun afterRebootUsesWallClock() {
        // Uptime restarted after reboot, so the stored uptime end is meaningless.
        assertEquals(7 * min, LockManager.remaining(wall + 10 * min, up + 10 * min, false, 1_000L, wall + 3 * min))
        // No uptime anchor stored (older version).
        assertEquals(7 * min, LockManager.remaining(wall + 10 * min, 0L, true, up, wall + 3 * min))
    }

    @Test fun afterRebootUsesCheckpointNotClock() {
        // Clock moved 2 hours forward, then a reboot: the 7 minutes left at the last checkpoint still run.
        assertEquals(7 * min, LockManager.remaining(wall + 10 * min, up + 10 * min, false, 1_000L, wall + 120 * min, 7 * min))
        // Same boot: the uptime end wins over the checkpoint.
        assertEquals(10 * min, LockManager.remaining(wall + 10 * min, up + 10 * min, true, up, wall, 7 * min))
        // A cleared lock stays cleared.
        assertEquals(0L, LockManager.remaining(0L, up + 10 * min, false, 1_000L, wall, 7 * min))
    }

    @Test fun finishedLockIsZeroNotNegative() {
        assertEquals(0L, LockManager.remaining(wall + 10 * min, up + 10 * min, true, up + 11 * min, wall))
        assertEquals(0L, LockManager.remaining(wall, 0L, false, up, wall + min))
    }

    @Test fun remainingIsCappedAtMaxPlusOneMinute() {
        assertEquals(cap, LockManager.remaining(wall + 10_000 * min, 0L, false, up, wall))
        assertEquals(cap, LockManager.remaining(wall + 10 * min, up + 10_000 * min, true, up, wall))
        assertEquals(LockManager.MAX_MINUTES * min, LockManager.remaining(wall + LockManager.MAX_MINUTES * min, 0L, false, up, wall))
    }
}
