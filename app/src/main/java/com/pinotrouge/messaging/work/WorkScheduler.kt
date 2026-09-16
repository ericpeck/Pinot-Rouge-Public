package com.pinotrouge.messaging.work

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues Pinot's background jobs. Called from [com.pinotrouge.messaging.PinotRougeApp].
 */
object WorkScheduler {

    /** Daily; [WeeklyDigestWorker.isSunday] is the real weekly gate. */
    internal const val WEEKLY_DIGEST_REPEAT_INTERVAL_DAYS = 1L

    /** UPDATE so existing installs replace the broken 7-day KEEP schedule. */
    internal val WEEKLY_DIGEST_EXISTING_POLICY = ExistingPeriodicWorkPolicy.UPDATE

    fun enqueueAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        enqueueQuarantineCommit(wm)
        enqueueWeeklyDigest(wm)
        Log.i(TAG, "Enqueued quarantine commit (daily) and weekly digest")
    }

    private fun enqueueQuarantineCommit(wm: WorkManager) {
        val request = PeriodicWorkRequestBuilder<QuarantineCommitWorker>(
            1,
            TimeUnit.DAYS,
        ).build()
        wm.enqueueUniquePeriodicWork(
            QuarantineCommitWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun enqueueWeeklyDigest(wm: WorkManager): androidx.work.Operation {
        // Daily period; worker no-ops unless the calendar day is Sunday.
        val request = PeriodicWorkRequestBuilder<WeeklyDigestWorker>(
            WEEKLY_DIGEST_REPEAT_INTERVAL_DAYS,
            TimeUnit.DAYS,
        ).build()
        return wm.enqueueUniquePeriodicWork(
            WeeklyDigestWorker.UNIQUE_NAME,
            WEEKLY_DIGEST_EXISTING_POLICY,
            request,
        )
    }

    /**
     * Same unique work as [enqueueAll]; exposed so tests can wait on the Operation.
     */
    internal fun enqueueWeeklyDigest(context: Context): androidx.work.Operation {
        return enqueueWeeklyDigest(WorkManager.getInstance(context))
    }

    /**
     * Debug / verification: run quarantine commit as soon as the system allows.
     * Prefer `adb shell cmd jobscheduler run -f …` when testing expiry end-to-end.
     */
    fun enqueueQuarantineCommitNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<QuarantineCommitWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${QuarantineCommitWorker.UNIQUE_NAME}_now",
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.i(TAG, "Enqueued one-shot quarantine commit")
    }

    private const val TAG = "WorkScheduler"
}
