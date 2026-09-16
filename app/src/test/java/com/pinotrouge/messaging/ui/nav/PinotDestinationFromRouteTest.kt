package com.pinotrouge.messaging.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PinotDestination.fromRoute] / [PinotDestination.showsBottomBar] for the
 * Version 5 bottom bar (Chats · Filtered · Settings).
 *
 * Only Chats / Settings are tabs. Filtered is a bar destination that is *not* a
 * tab — [fromRoute] is null (FAB stays off) while [showsBottomBar] is true.
 * Search is a stack route, not a tab. Other overlays hide the bar.
 *
 * Retargeted from the Wave 8 three-tab (Chats/Search/Settings) contract when
 * Filtered replaced Search in the bar (`fix/filtered-in-bottom-nav`).
 */
class PinotDestinationFromRouteTest {

    @Test
    fun fromRoute_tabRoutes_returnThemselves() {
        assertEquals(PinotDestination.Inbox, PinotDestination.fromRoute("inbox"))
        assertEquals(PinotDestination.Settings, PinotDestination.fromRoute("settings"))
    }

    @Test
    fun fromRoute_search_is_not_a_tab() {
        // Search left the bar; still a real route, but not a PinotDestination.
        assertNull(PinotDestination.fromRoute("search"))
        assertFalse(PinotDestination.showsBottomBar("search"))
    }

    @Test
    fun fromRoute_filtered_is_not_a_tab_but_keeps_the_bar() {
        assertNull(PinotDestination.fromRoute("filtered"))
        assertTrue(PinotDestination.isFilteredRoute("filtered"))
        assertTrue(PinotDestination.showsBottomBar("filtered"))
    }

    @Test
    fun fromRoute_other_overlays_hide_the_bar() {
        assertNull(PinotDestination.fromRoute("rules"))
        assertNull(PinotDestination.fromRoute("archive"))
        assertFalse(PinotDestination.showsBottomBar("rules"))
        assertFalse(PinotDestination.showsBottomBar("archive"))
        // Tabs still resolve — bar visible, not treated as overlay.
        assertEquals(PinotDestination.Inbox, PinotDestination.fromRoute("inbox"))
    }

    @Test
    fun fromRoute_nonTabRoutes_returnNull() {
        assertNull(PinotDestination.fromRoute("thread/12"))
        assertNull(PinotDestination.fromRoute("thread/{threadId}"))
        assertNull(PinotDestination.fromRoute("compose"))
        assertNull(PinotDestination.fromRoute("builder"))
        assertNull(PinotDestination.fromRoute("builder/abc"))
        assertNull(PinotDestination.fromRoute("builder/{ruleId}"))
        assertNull(PinotDestination.fromRoute("builder/prefill/555"))
        assertNull(PinotDestination.fromRoute("builder/prefill/{prefillSender}"))
        assertFalse(PinotDestination.showsBottomBar("thread/12"))
        assertFalse(PinotDestination.showsBottomBar("compose"))
        assertFalse(PinotDestination.showsBottomBar("builder"))
    }

    @Test
    fun fromRoute_null_returnsNull() {
        assertNull(PinotDestination.fromRoute(null))
        assertFalse(PinotDestination.showsBottomBar(null))
    }

    @Test
    fun two_tabs_only_in_enum() {
        // Bar has three *slots*; enum is tabs only (Chats + Settings).
        assertEquals(2, PinotDestination.entries.size)
        assertEquals(
            listOf("inbox", "settings"),
            PinotDestination.entries.map { it.route },
        )
        assertEquals(
            listOf("Chats", "Settings"),
            PinotDestination.entries.map { it.label },
        )
    }

    @Test
    fun showsBottomBar_true_for_tabs() {
        assertTrue(PinotDestination.showsBottomBar("inbox"))
        assertTrue(PinotDestination.showsBottomBar("settings"))
    }
}

/**
 * Companion tests for builder back/save navigation helpers (STATE.md cases).
 * The Filters-under-thread cancel path is unexercised in UI until Wave 9
 * *Filter this sender* is the only entry; the mechanism is pinned here.
 */
class BuilderReturnsToTest {

    @Test
    fun builderRoute_encodesReturnsTo() {
        assertEquals("builder", builderRoute())
        assertEquals(
            "builder?returnsTo=filters",
            builderRoute(returnsTo = BuilderReturnsTo.Filters),
        )
        assertEquals(
            "builder/prefill/555?returnsTo=thread_9",
            builderRoute(prefillSender = "555", returnsTo = BuilderReturnsTo.thread(9)),
        )
    }

    @Test
    fun thread_returnsTo_format() {
        assertEquals("thread_42", BuilderReturnsTo.thread(42))
        assertEquals("thread/42", BuilderReturnsTo.threadRoute("thread_42"))
    }
}
