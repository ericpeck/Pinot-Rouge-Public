package com.pinotrouge.messaging.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string checks only — [android.net.Uri] is not usable under JVM unit tests
 * without Robolectric.
 */
class NotificationDeepLinkTest {

    @Test
    fun `deep link scheme and host constants`() {
        assertEquals("pinotrouge", NotificationHelper.DEEP_LINK_SCHEME)
        assertEquals("thread", NotificationHelper.DEEP_LINK_HOST_THREAD)
    }

    @Test
    fun `deep link pattern matches nav host registration`() {
        val pattern = NotificationHelper.threadDeepLinkPattern()
        assertEquals("pinotrouge://thread/{threadId}", pattern)
        assertTrue(pattern.contains("{threadId}"))
    }

    @Test
    fun `extra thread id key is stable for MainActivity`() {
        assertEquals(
            "com.pinotrouge.messaging.extra.THREAD_ID",
            NotificationHelper.EXTRA_THREAD_ID,
        )
    }

    @Test
    fun `filtered deep link scheme and host constants`() {
        assertEquals("pinotrouge", NotificationHelper.DEEP_LINK_SCHEME)
        assertEquals("filtered", NotificationHelper.DEEP_LINK_HOST_FILTERED)
    }

    @Test
    fun `filtered deep link round-trips to Filtered destination`() {
        val pattern = NotificationHelper.filteredDeepLinkPattern()
        assertEquals("pinotrouge://filtered", pattern)
        val scheme = pattern.substringBefore("://")
        val host = pattern.substringAfter("://")
        assertEquals(NotificationHelper.DEEP_LINK_SCHEME, scheme)
        assertEquals(NotificationHelper.DEEP_LINK_HOST_FILTERED, host)
        assertEquals("filtered", host)
    }
}
