package com.pinotrouge.messaging.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pinotrouge.messaging.MainActivity
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.notify.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Quiet Sunday summary of held messages when the user enabled the digest setting.
 *
 * Posts nothing if the week was empty or the setting is off. Uses the silent
 * low-importance channel (same id as [NotificationHelper.CHANNEL_SILENT]).
 */
@HiltWorker
class WeeklyDigestWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val quarantineRepository: QuarantineRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!isSunday(nowMillis())) {
                Log.d(TAG, "Not Sunday — no digest")
                return Result.success()
            }
            val settings = settingsRepository.settings.first()
            if (!settings.holdByDefault) {
                // Setting key "held" / holdByDefault gates the weekly digest.
                Log.d(TAG, "Weekly digest setting off — skip")
                return Result.success()
            }

            val weekAgo = nowMillis() - TimeUnit.DAYS.toMillis(7)
            val held = quarantineRepository.observeHeld().first()
                .filter { it.heldAt >= weekAgo }

            val plan = WeeklyDigestPlanner.plan(held.map { it.sender })
            if (plan == null) {
                Log.d(TAG, "Nothing held this week — no notification")
                return Result.success()
            }

            postDigest(plan)
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Weekly digest failed", t)
            Result.retry()
        }
    }

    private fun postDigest(plan: WeeklyDigestPlanner.Plan) {
        ensureSilentChannel()
        val deepLink = NotificationHelper.filteredDeepLinkUri()
        val intent = Intent(Intent.ACTION_VIEW, deepLink, appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, NotificationHelper.CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(appContext.getString(R.string.digest_title))
            .setContentText(plan.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plan.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS denied; digest not shown")
        }
    }

    private fun ensureSilentChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val silent = NotificationChannel(
            NotificationHelper.CHANNEL_SILENT,
            appContext.getString(R.string.notif_channel_silent),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.notif_channel_silent_desc)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(silent)
    }

    companion object {
        const val TAG = "WeeklyDigestWorker"
        const val UNIQUE_NAME = "weekly_digest_sunday"
        private const val NOTIFICATION_ID = 710_001

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        @Volatile
        internal var nowMillisOverride: Long? = null

        internal fun nowMillis(): Long = nowMillisOverride ?: System.currentTimeMillis()

        fun isSunday(nowMillis: Long): Boolean {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            return cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        }
    }
}

/**
 * Pure digest planning — unit-tested without Android.
 */
object WeeklyDigestPlanner {
    data class Plan(val messageCount: Int, val senderCount: Int, val body: String)

    /**
     * @return null when there is nothing to announce (no notification).
     */
    fun plan(sendersThisWeek: List<String>): Plan? {
        if (sendersThisWeek.isEmpty()) return null
        val messageCount = sendersThisWeek.size
        val senderCount = sendersThisWeek.distinct().size
        val body = when {
            messageCount == 1 && senderCount == 1 ->
                "1 message held from 1 sender this week."
            messageCount == 1 ->
                "1 message held from $senderCount senders this week."
            senderCount == 1 ->
                "$messageCount messages held from 1 sender this week."
            else ->
                "$messageCount messages held from $senderCount senders this week."
        }
        return Plan(messageCount, senderCount, body)
    }
}
