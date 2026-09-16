package com.pinotrouge.messaging.ui.thread

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version 2 call button is gated on known contact (prototype `threadCallable`).
 * Pure mapping so the UI rule cannot drift from the ViewModel field.
 */
class ThreadCallableTest {

    @Test
    fun knownContact_withAddress_isCallable() {
        assertTrue(threadCallable(isKnownContact = true, address = "+15551234"))
    }

    @Test
    fun unknownSender_isNotCallable() {
        assertFalse(threadCallable(isKnownContact = false, address = "+15551234"))
    }

    @Test
    fun blankAddress_isNotCallable() {
        assertFalse(threadCallable(isKnownContact = true, address = ""))
        assertFalse(threadCallable(isKnownContact = true, address = "   "))
    }
}

/** Same rule ThreadTopBar uses for the audio-call button. */
fun threadCallable(isKnownContact: Boolean, address: String): Boolean =
    isKnownContact && address.isNotBlank()
