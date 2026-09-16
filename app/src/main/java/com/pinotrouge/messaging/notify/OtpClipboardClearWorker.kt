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
 * Clears the primary clipboard 60s after an OTP copy — only if the clip still
 * matches the code we put there.
 *
 * Background clipboard reads can return null on some Android versions when the
 * app process has no focus. In that case we **do not** wipe (would risk
 * destroying a later user copy we cannot compare). See Log.
 */
@HiltWorker
class OtpClipboardClearWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val expected = inputData.getString(KEY_CODE).orEmpty()
        if (expected.isEmpty()) {
            Log.w(TAG, "No code in input; nothing to clear")
            return Result.success()
        }

        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Log.w(TAG, "ClipboardManager unavailable")
            return Result.success()
        }

        val current = readPrimaryText(clipboard)
        if (!OtpClipboardPolicy.shouldClear(current, expected)) {
            Log.d(
                TAG,
                "Skip clear: clip does not match expected OTP " +
                    "(readable=${current != null})",
            )
            return Result.success()
        }

        return try {
            clipboard.clearPrimaryClip()
            Log.i(TAG, "OTP cleared from clipboard")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "clearPrimaryClip failed", t)
            Result.success()
        }
    }

    private fun readPrimaryText(clipboard: ClipboardManager): String? {
        return try {
            if (!clipboard.hasPrimaryClip()) return null
            val clip = clipboard.primaryClip ?: return null
            if (clip.itemCount < 1) return null
            clip.getItemAt(0).coerceToText(appContext)?.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read clipboard for compare", t)
            null
        }
    }

    companion object {
        const val TAG = "OtpClipboardClear"
        const val UNIQUE_WORK_NAME = "otp_clipboard_clear"
        const val KEY_CODE = "code"
    }
}
