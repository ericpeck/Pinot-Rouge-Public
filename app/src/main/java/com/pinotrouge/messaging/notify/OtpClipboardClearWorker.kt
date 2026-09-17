package com.pinotrouge.messaging.notify

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Best-effort clipboard clear after an OTP copy.
 *
 * WorkManager's initial delay is a minimum, not a wall-clock guarantee.
 * Background clipboard reads can return null when the process has no focus;
 * in that case we do not wipe, because we cannot tell whether the clip is
 * still ours.
 */
@HiltWorker
class OtpClipboardClearWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val expectedToken = inputData.getString(KEY_TOKEN).orEmpty()
        if (expectedToken.isEmpty()) {
            Log.w(TAG, "No ownership token in input; nothing to clear")
            return Result.success()
        }

        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Log.w(TAG, "ClipboardManager unavailable")
            return Result.success()
        }

        val currentToken = readOwnershipToken(clipboard)
        if (!OtpClipboardPolicy.shouldClear(currentToken, expectedToken)) {
            Log.d(
                TAG,
                "Skip clear: clip is not ours or unreadable (readable=${currentToken != null})",
            )
            return Result.success()
        }

        return try {
            clipboard.clearPrimaryClip()
            Log.i(TAG, "OTP clipboard cleared")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "clearPrimaryClip failed: ${t.javaClass.simpleName}")
            Result.success()
        }
    }

    private fun readOwnershipToken(clipboard: ClipboardManager): String? {
        return try {
            if (!clipboard.hasPrimaryClip()) return null
            val clip = clipboard.primaryClip ?: return null
            clip.description.extras?.getString(OtpClipboardPolicy.EXTRA_OWNERSHIP)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read clipboard extras: ${t.javaClass.simpleName}")
            null
        }
    }

    companion object {
        const val TAG = "OtpClipboardClear"
        const val UNIQUE_WORK_NAME = "otp_clipboard_clear"
        const val KEY_TOKEN = "ownership_token"
    }
}
