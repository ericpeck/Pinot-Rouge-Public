package com.pinotrouge.messaging.ui.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleRelativeTimeTest {

    @Test
    fun neverRunWhenNull() {
        assertEquals("never run", formatLastCaught(null, nowMillis = 1_000_000L))
    }

    @Test
    fun justNowUnderOneMinute() {
        val now = 1_000_000L
        assertEquals("last caught just now", formatLastCaught(now - 30_000L, now))
    }

    @Test
    fun minutes() {
        val now = 1_000_000L
        assertEquals(
            "last caught 12 min ago",
            formatLastCaught(now - 12 * 60_000L, now),
        )
    }

    @Test
    fun hours() {
        val now = 10_000_000L
        assertEquals(
            "last caught 1 h ago",
            formatLastCaught(now - 3_600_000L, now),
        )
        assertEquals(
            "last caught 4 h ago",
            formatLastCaught(now - 4 * 3_600_000L, now),
        )
    }

    @Test
    fun days() {
        val now = 100_000_000L
        assertEquals(
            "last caught 2 d ago",
            formatLastCaught(now - 2 * 86_400_000L, now),
        )
    }

    @Test
    fun caughtThisMonth() {
        assertEquals("0 caught this month", formatCaughtThisMonth(0))
        assertEquals("34 caught this month", formatCaughtThisMonth(34))
    }

    @Test
    fun caughtLabel_includes_swept_when_nonzero() {
        assertEquals("2 caught this month", formatCaughtLabel(caught = 2, swept = 0))
        assertEquals(
            "2 caught this month · 18 swept",
            formatCaughtLabel(caught = 2, swept = 18),
        )
        // Swept-only must not look like a blank counter alone — label still has both halves.
        assertEquals(
            "0 caught this month · 18 swept",
            formatCaughtLabel(caught = 0, swept = 18),
        )
    }

    @Test
    fun neverRun_not_when_only_swept() {
        // lastCaughtAt is set by recordSweep in production; if null but swept>0, not "never run".
        assertEquals("never run", formatLastCaught(null, nowMillis = 1L, sweptCount = 0))
        assertEquals("swept", formatLastCaught(null, nowMillis = 1L, sweptCount = 18))
    }
}
