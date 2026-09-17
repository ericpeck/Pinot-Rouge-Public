package com.pinotrouge.messaging.notify

import androidx.work.Data
import androidx.work.workDataOf
import java.util.UUID

/**
 * Pure policy for the OTP clipboard clear. Unit-tested without Android.
 *
 * Ownership is a random UUID placed on [android.content.ClipDescription]
 * extras and in WorkManager input. The raw code is never persisted in work
 * input, and is not hashed (short codes are brute-forceable).
 *
 * We only wipe when the primary clip still carries that token. If the user
 * copied something else, or the clipboard is unreadable in the background,
 * leave it alone.
 */
object OtpClipboardPolicy {

    const val EXTRA_OWNERSHIP = "com.pinotrouge.messaging.otp_clip_token"

    fun newOwnershipToken(): String = UUID.randomUUID().toString()

    fun workInput(token: String): Data = workDataOf(
        OtpClipboardClearWorker.KEY_TOKEN to token,
    )

    fun shouldClear(clipToken: String?, expectedToken: String): Boolean {
        if (expectedToken.isEmpty()) return false
        return clipToken != null && clipToken == expectedToken
    }
}
