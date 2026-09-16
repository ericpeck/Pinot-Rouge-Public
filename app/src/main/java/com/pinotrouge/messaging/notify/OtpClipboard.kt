package com.pinotrouge.messaging.notify

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTP / verification-code clipboard: mark sensitive, schedule a 60s clear.
 *
 * Clear is a [WorkManager] one-shot ([OtpClipboardClearWorker]) so it survives
 * process death. The worker wipes the clipboard **only** when the primary clip
 * still equals the code we set (see [OtpClipboardPolicy]).
 *
 * Unique work name is replaced on each copy so only the latest code's timer
 * remains — copying a second code cancels the first clear job, which is correct
 * because the first code is no longer on the clipboard.
 */
@Singleton
class OtpClipboard @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Copy [code] as sensitive plain text and schedule clear after [CLEAR_AFTER_SECONDS].
     */
    fun copyCode(code: String) {
        if (code.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return

        val clip = ClipData.newPlainText(CLIP_LABEL, code)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)
        scheduleClear(code)
    }

    private fun scheduleClear(code: String) {
        val request = OneTimeWorkRequestBuilder<OtpClipboardClearWorker>()
            .setInitialDelay(CLEAR_AFTER_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(OtpClipboardClearWorker.KEY_CODE to code))
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
    }
}
