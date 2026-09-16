package com.pinotrouge.messaging.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpClipboardPolicyTest {

    @Test
    fun `clears when clip still matches our code`() {
        assertTrue(OtpClipboardPolicy.shouldClear("882041", "882041"))
    }

    @Test
    fun `does not clear when user copied something else`() {
        assertFalse(OtpClipboardPolicy.shouldClear("hello", "882041"))
    }

    @Test
    fun `does not clear when clipboard unreadable`() {
        assertFalse(OtpClipboardPolicy.shouldClear(null, "882041"))
    }

    @Test
    fun `does not clear empty expected code`() {
        assertFalse(OtpClipboardPolicy.shouldClear("", ""))
        assertFalse(OtpClipboardPolicy.shouldClear("x", ""))
    }
}
