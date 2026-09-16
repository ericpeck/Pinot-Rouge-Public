package com.pinotrouge.messaging.data.repo

import com.pinotrouge.messaging.data.telephony.SmsThread
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the unread badge aggregation so ContentObserver wiring cannot change
 * what the badge *displays* — only how often / when it is recomputed.
 */
class UnreadBadgeTotalTest {

    @Test
    fun `sums non-negative thread unread flags`() {
        val threads = listOf(
            thread(unread = 1),
            thread(unread = 0),
            thread(unread = 1),
            thread(unread = 1),
        )
        assertEquals(3, unreadBadgeTotal(threads))
    }

    @Test
    fun `empty list is zero`() {
        assertEquals(0, unreadBadgeTotal(emptyList()))
    }

    @Test
    fun `negative unread is coerced to zero`() {
        // Provider should never send this; defensive same as the old sumOf.
        assertEquals(1, unreadBadgeTotal(listOf(thread(unread = -3), thread(unread = 1))))
    }

    private fun thread(unread: Int) = SmsThread(
        threadId = 1L,
        address = "1",
        snippet = null,
        date = 0L,
        messageCount = 1,
        unreadCount = unread,
    )
}
