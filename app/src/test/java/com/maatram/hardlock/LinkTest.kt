package com.maatram.hardlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {
    private val min = 60_000L

    @Test fun normalizeAcceptsTypedForms() {
        assertEquals("ABCD2345", Link.normalize("abcd-2345"))
        assertEquals("ABCD2345", Link.normalize(" ab cd 23 45 "))
        assertEquals("", Link.normalize("--"))
    }

    @Test fun codeIsEightLettersOrDigitsWithoutLookalikes() {
        assertTrue(Link.isCode("ABCD2345"))
        assertFalse(Link.isCode("ABCD234"))      // too short
        assertFalse(Link.isCode("ABCD23456"))    // too long
        assertFalse(Link.isCode("abcd2345"))     // not normalized
        for (c in listOf('I', 'O', '0', '1')) assertFalse("$c", Link.isCode("ABCD234$c"))
    }

    @Test fun prettySplitsFullCodes() {
        assertEquals("ABCD-2345", Link.pretty("ABCD2345"))
        assertEquals("ABC", Link.pretty("ABC"))
    }

    @Test fun linkedEndCorrectsForPhoneClock() {
        // Phone clock is 2 minutes ahead of the server: the end moves forward by the same amount.
        val serverNow = 1_000_000_000L
        val end = Link.linkedEnd(serverNow + 30 * min, serverNow, 30, serverNow + 2 * min, 0L)
        assertEquals(serverNow + 32 * min, end)
    }

    @Test fun linkedEndIgnoresFinishedOrEmptyLocks() {
        val now = 1_000_000_000L
        assertNull(Link.linkedEnd(now - min, now, 30, now, 0L))       // already over
        assertNull(Link.linkedEnd(now + 3_000L, now, 1, now, 0L))     // ends within 3 s
        assertNull(Link.linkedEnd(now + 30 * min, now, 0, now, 0L))   // no minutes
        assertNull(Link.linkedEnd(0L, 0L, 0, now, 0L))                // no lock on the link
    }

    @Test fun linkedEndOnlyExtendsOurLock() {
        val now = 1_000_000_000L
        val shared = now + 30 * min
        assertNull(Link.linkedEnd(shared, now, 30, now, shared))              // same lock we already run
        assertNull(Link.linkedEnd(shared, now, 30, now, shared - 5_000L))     // within 5 s: same lock
        assertNull(Link.linkedEnd(shared, now, 30, now, shared + min))        // ours is longer
        assertEquals(shared, Link.linkedEnd(shared, now, 30, now, shared - 5_001L))
        assertEquals(shared, Link.linkedEnd(shared, now, 30, now, now + 10 * min))
    }

    @Test fun linkedEndRejectsBadReplies() {
        val now = 1_000_000_000L
        assertNull(Link.linkedEnd(now + 30 * min, 0L, 30, now, 0L))                          // no server time
        assertEquals(now + LockManager.MAX_MINUTES * min, Link.linkedEnd(now + 3 * 1440 * min, now, 30, now, 0L))  // days away: capped
    }

    @Test fun usageMinutes_groupsKnownAppsAndDropsTheRest() {
        val m = Link.usageMinutes(mapOf(
            "com.instagram.android" to 30 * 60_000L, "com.instagram.lite" to 15 * 60_000L,
            "com.twitter.android" to 5 * 60_000L, "com.snapchat.android" to 40_000L,
            "com.example.notes" to 99 * 60_000L))
        assertEquals(mapOf("instagram" to 45, "other" to 5), m)
    }
}
