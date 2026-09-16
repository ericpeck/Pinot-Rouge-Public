package com.pinotrouge.messaging.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily hybrid-quarantine commit: file or permanently delete expired held rows.
 *
 * Logic lives in [QuarantineRepository.commitExpired] — this worker only schedules
 * and logs. A `skipped` count means provider writes failed and rows were **kept**
 * for tomorrow; never treat that as "delete them".
 */
@HiltWorker
class QuarantineCommitWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val quarantineRepository: QuarantineRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        return try {
            val summary = quarantineRepository.commitExpired(now)
            Log.i(
                TAG,
                "commitExpired: deleted=${summary.deleted} filed=${summary.filed} " +
                    "skipped=${summary.skipped} (skipped rows retained for retry)",
            )
            // Always success: skipped rows stay in Room and the next daily run retries.
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "commitExpired failed", t)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "QuarantineCommitWorker"
        const val UNIQUE_NAME = "quarantine_commit_daily"
    }
}
