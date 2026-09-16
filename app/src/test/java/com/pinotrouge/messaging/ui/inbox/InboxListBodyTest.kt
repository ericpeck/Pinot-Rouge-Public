package com.pinotrouge.messaging.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the three-state inbox contract from fix/preview-mode-permissions:
 * empty, filter-empty, and "cannot read" must never collapse together.
 */
class InboxListBodyTest {

    @Test
    fun `loading wins over everything`() {
        assertEquals(
            InboxListBody.Loading,
            resolveInboxListBody(
                isLoading = true,
                canReadMessages = false,
                filteredEmpty = true,
                hasAnyThreads = false,
            ),
        )
    }

    @Test
    fun `loading with existing threads does not blank the list`() {
        assertEquals(
            InboxListBody.Threads,
            resolveInboxListBody(
                isLoading = true,
                canReadMessages = true,
                filteredEmpty = false,
                hasAnyThreads = true,
            ),
        )
    }

    @Test
    fun `loading still wins on first run with no threads`() {
        assertEquals(
            InboxListBody.Loading,
            resolveInboxListBody(
                isLoading = true,
                canReadMessages = true,
                filteredEmpty = true,
                hasAnyThreads = false,
            ),
        )
    }

    @Test
    fun `no permission is never a real empty inbox`() {
        assertEquals(
            InboxListBody.NeedsPermission,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = false,
                filteredEmpty = true,
                hasAnyThreads = false,
            ),
        )
        // Even if we somehow had threads cached, lack of permission still
        // must not present as a normal list when filteredEmpty is true.
        assertEquals(
            InboxListBody.NeedsPermission,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = false,
                filteredEmpty = true,
                hasAnyThreads = true,
            ),
        )
    }

    @Test
    fun `permission granted and empty is real empty`() {
        assertEquals(
            InboxListBody.Empty,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = true,
                filteredEmpty = true,
                hasAnyThreads = false,
            ),
        )
    }

    @Test
    fun `permission granted filter empty`() {
        assertEquals(
            InboxListBody.EmptyFilter,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = true,
                filteredEmpty = true,
                hasAnyThreads = true,
            ),
        )
    }

    @Test
    fun `permission granted with rows shows threads`() {
        assertEquals(
            InboxListBody.Threads,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = true,
                filteredEmpty = false,
                hasAnyThreads = true,
            ),
        )
    }

    @Test
    fun `no permission does not show threads even if list not empty flag`() {
        // filteredEmpty=false would only happen with rows; if canRead is false
        // we still prioritize NeedsPermission so UI never pretends it can list.
        assertEquals(
            InboxListBody.NeedsPermission,
            resolveInboxListBody(
                isLoading = false,
                canReadMessages = false,
                filteredEmpty = false,
                hasAnyThreads = false,
            ),
        )
    }
}
