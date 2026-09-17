package com.pinotrouge.messaging.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpClipboardPolicyTest {

    @Test
    fun `clears only when the clip still carries our ownership token`() {
        val token = OtpClipboardPolicy.newOwnershipToken()
        assertTrue(OtpClipboardPolicy.shouldClear(token, token))
    }

    @Test
    fun `does not clear when the user copied something else`() {
        val ours = OtpClipboardPolicy.newOwnershipToken()
        val later = OtpClipboardPolicy.newOwnershipToken()
        assertFalse(OtpClipboardPolicy.shouldClear(later, ours))
        assertFalse(OtpClipboardPolicy.shouldClear(null, ours))
    }

    @Test
    fun `does not clear when the clipboard is unreadable`() {
        assertFalse(
            OtpClipboardPolicy.shouldClear(
                clipToken = null,
                expectedToken = OtpClipboardPolicy.newOwnershipToken(),
            ),
        )
    }

    @Test
    fun `does not clear an empty expected token`() {
        assertFalse(OtpClipboardPolicy.shouldClear("", ""))
        assertFalse(OtpClipboardPolicy.shouldClear("x", ""))
    }

    @Test
    fun `work input stores the token and never the OTP`() {
        val otp = "882041"
        val token = OtpClipboardPolicy.newOwnershipToken()
        val data = OtpClipboardPolicy.workInput(token)
        assertEquals(token, data.getString(OtpClipboardClearWorker.KEY_TOKEN))
        assertFalse(data.keyValueMap.containsKey("code"))
        assertFalse(data.keyValueMap.values.any { it == otp })
        assertNotEquals(otp, token)
    }

    @Test
    fun `ownership tokens are not a hash of the code`() {
        val a = OtpClipboardPolicy.newOwnershipToken()
        val b = OtpClipboardPolicy.newOwnershipToken()
        assertNotEquals(a, b)
        assertTrue(a.length >= 32)
        assertFalse(a.contains("882041"))
    }
}
