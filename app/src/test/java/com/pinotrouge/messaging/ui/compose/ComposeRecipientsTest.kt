package com.pinotrouge.messaging.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeRecipientsTest {

    @Test
    fun `parseRecipients splits comma semicolon newline`() {
        assertEquals(
            listOf("15551111", "15552222", "15553333"),
            ComposeViewModel.parseRecipients("15551111, 15552222; 15553333"),
        )
    }

    @Test
    fun `parseRecipients trims and dedupes`() {
        assertEquals(
            listOf("A", "B"),
            ComposeViewModel.parseRecipients(" A , B, A "),
        )
    }

    @Test
    fun `parseRecipients empty on blank`() {
        assertEquals(emptyList<String>(), ComposeViewModel.parseRecipients("  , ; "))
    }
}
