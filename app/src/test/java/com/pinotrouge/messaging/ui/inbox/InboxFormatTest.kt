package com.pinotrouge.messaging.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class InboxFormatTest {

    @Test
    fun `initials from multi-word name`() {
        assertEquals("DP", InboxFormat.initials("Dev Patel", ThreadCategory.People))
    }

    @Test
    fun `initials from single word`() {
        assertEquals("M", InboxFormat.initials("Mom", ThreadCategory.People))
    }

    @Test
    fun `codes use hash`() {
        assertEquals("#", InboxFormat.initials("88022", ThreadCategory.Codes))
    }

    @Test
    fun `phone number uses hash not trailing digits`() {
        // Retargeted (fix/wave-3-followups): trailing digits looked like a bug.
        assertEquals("#", InboxFormat.initials("+1 555 123 4567", ThreadCategory.Other))
        assertEquals("#", InboxFormat.initials("18445550192", ThreadCategory.Other))
        assertEquals("#", InboxFormat.initials("21212", ThreadCategory.Other))
    }

    @Test
    fun `alphanumeric brand ids use first two letters`() {
        assertEquals("LO", InboxFormat.initials("LOANFAST", ThreadCategory.Other))
        assertEquals("CA", InboxFormat.initials("CARTLY", ThreadCategory.Other))
    }

    @Test
    fun `no avatar is two trailing phone digits`() {
        val samples = listOf(
            "+15551234567",
            "844-555-0192",
            "001",
            "99",
        )
        for (s in samples) {
            val initials = InboxFormat.initials(s, ThreadCategory.Other)
            assertEquals("#", initials)
            assertTrue(
                "must not be pure digits for $s, was $initials",
                initials.any { !it.isDigit() },
            )
        }
    }

    @Test
    fun `formatTime today is hour colon minute`() {
        val now = cal(2026, Calendar.JULY, 28, 15, 30).timeInMillis
        val msg = cal(2026, Calendar.JULY, 28, 9, 14).timeInMillis
        assertEquals("9:14", InboxFormat.formatTime(msg, now))
    }

    @Test
    fun `formatTime yesterday`() {
        val now = cal(2026, Calendar.JULY, 28, 10, 0).timeInMillis
        val msg = cal(2026, Calendar.JULY, 27, 18, 0).timeInMillis
        assertEquals("Yesterday", InboxFormat.formatTime(msg, now))
    }

    private fun cal(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
