package com.pinotrouge.messaging.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test
    fun `same thread id maps to same notification id`() {
        val a = NotificationIds.forThread(42L)
        val b = NotificationIds.forThread(42L)
        assertEquals(a, b)
    }

    @Test
    fun `different threads map to different ids`() {
        assertNotEquals(
            NotificationIds.forThread(1L),
            NotificationIds.forThread(2L),
        )
    }

    @Test
    fun `notification id is positive`() {
        assertTrue(NotificationIds.forThread(0L) >= 1)
        assertTrue(NotificationIds.forThread(-1L) >= 1)
        assertTrue(NotificationIds.forThread(Long.MAX_VALUE) >= 1)
        assertTrue(NotificationIds.forThread(Long.MIN_VALUE) >= 1)
    }

    @Test
    fun `group key is stable per thread`() {
        assertEquals("thread_99", NotificationIds.groupKey(99L))
        assertEquals(NotificationIds.groupKey(7L), NotificationIds.groupKey(7L))
    }
}
