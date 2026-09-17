package com.pinotrouge.messaging.notify

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTP / verification-code clipboard: mark sensitive, schedule a best-effort clear.
 *
 * WorkManager input stores only a random ownership token, never the code.
 * Clear runs after [CLEAR_AFTER_SECONDS] **at the earliest**; Android may delay
 * or skip the worker, and a background process may be unable to read the
 * clipboard. We wipe only when the clip still carries our token.
 */
@Singleton
class OtpClipboard @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Copy [code] as sensitive plain text and schedule a best-effort clear.
     */
    fun copyCode(code: String) {
        if (code.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return

        val token = OtpClipboardPolicy.newOwnershipToken()
        clipboard.setPrimaryClip(clipFor(code, token))
        scheduleClear(token)
    }

    private fun scheduleClear(token: String) {
        val request = OneTimeWorkRequestBuilder<OtpClipboardClearWorker>()
            .setInitialDelay(CLEAR_AFTER_SECONDS, TimeUnit.SECONDS)
            .setInputData(OtpClipboardPolicy.workInput(token))
            .addTag(OtpClipboardClearWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            OtpClipboardClearWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val CLEAR_AFTER_SECONDS = 60L
        const val CLIP_LABEL = "verification code"

        fun clipFor(code: String, token: String): ClipData {
            val clip = ClipData.newPlainText(CLIP_LABEL, code)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                putString(OtpClipboardPolicy.EXTRA_OWNERSHIP, token)
            }
            return clip
        }
    }
}
