package com.pinotrouge.messaging.notify

/**
 * Pure policy for the OTP clipboard clear. Unit-tested without Android.
 *
 * We only wipe the clipboard when it still holds the code we placed there.
 * If the user copied something else in the meantime, leave it alone.
 */
object OtpClipboardPolicy {

    fun shouldClear(currentClipText: String?, expectedCode: String): Boolean {
        if (expectedCode.isEmpty()) return false
        return currentClipText != null && currentClipText == expectedCode
    }
}
