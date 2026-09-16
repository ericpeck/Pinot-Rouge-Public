package com.pinotrouge.messaging.ui.components

import com.pinotrouge.messaging.ui.builder.saveToastMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Session contract for filter-save confirmation (claim / leave / clear).
 */
class FilterSaveToastSessionTest {

    @Test
    fun show_exposes_message_until_clear() {
        val session = FilterSaveToastSession()
        assertNull(session.message.value)

        val text = saveToastMessage("Links from strangers")
        session.show(text)
        assertEquals(text, session.message.value)

        session.clear()
        assertNull(session.message.value)
    }

    @Test
    fun clearOnLeave_without_claim_keeps_message() {
        val session = FilterSaveToastSession()
        session.show(saveToastMessage("A"))
        // Simulate dispose of an under-stack Filters during save navigation.
        session.clearOnLeave()
        assertEquals(saveToastMessage("A"), session.message.value)
    }

    @Test
    fun clearOnLeave_after_claim_drops_message() {
        val session = FilterSaveToastSession()
        session.show(saveToastMessage("A"))
        session.claim()
        session.clearOnLeave()
        assertNull(session.message.value)
    }

    @Test
    fun show_resets_claim_so_next_dispose_does_not_wipe_fresh_save() {
        val session = FilterSaveToastSession()
        session.show(saveToastMessage("Old"))
        session.claim()
        // New save while a claimed host is being torn down / replaced:
        session.show(saveToastMessage("New"))
        session.clearOnLeave() // unclaimed after show → keep
        assertEquals(saveToastMessage("New"), session.message.value)
        session.claim()
        session.clearOnLeave()
        assertNull(session.message.value)
    }
}
