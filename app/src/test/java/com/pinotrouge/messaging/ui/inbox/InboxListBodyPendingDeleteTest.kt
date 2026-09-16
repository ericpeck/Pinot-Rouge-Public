package com.pinotrouge.messaging.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * During the deferred-delete undo window the visible list can be empty.
 * Stashed rows still count so we never flash "No messages yet".
 */
class InboxListBodyPendingDeleteTest {

    @Test
    fun pendingDelete_keepsThreadsBody_whenListTemporarilyEmpty() {
        assertEquals(
            InboxListBody.Threads,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = true,
                filteredEmpty = true,
                hasAnyThreads = false,
                pendingDeleteCount = 3,
            ),
        )
    }

    @Test
    fun noPending_emptyIsEmpty() {
        assertEquals(
            InboxListBody.Empty,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = true,
                filteredEmpty = true,
                hasAnyThreads = false,
                pendingDeleteCount = 0,
            ),
        )
    }
}
