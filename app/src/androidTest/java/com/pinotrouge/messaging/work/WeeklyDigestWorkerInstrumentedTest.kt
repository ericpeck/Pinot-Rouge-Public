package com.pinotrouge.messaging.work

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.util.createInMemoryDb
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Tier 2 — weekly digest posts **nothing** when nothing was held.
 *
 * [WeeklyDigestWorker.nowMillisOverride] lets tests pin Sunday vs weekday
 * without moving the device clock.
 *
 * [NotificationManager.notify] / [NotificationManager.cancel] are one-way
 * calls into NotificationManagerService. [activeDigestCount] must poll
 * [NotificationManager.getActiveNotifications] with a bounded wait — a
 * bare read races the service (see Tasks/fix-digest-test-races).
 */
@RunWith(AndroidJUnit4::class)
class WeeklyDigestWorkerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var quarantine: QuarantineRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        quarantine = QuarantineRepository(
            heldMessageDao = db.heldMessageDao(),
            blockedSenderDao = db.blockedSenderDao(),
            ruleDao = db.ruleDao(),
            smsRepository = mockk(relaxed = true),
            heldMediaDao = db.heldMediaDao(),
            mmsRepository = mockk(relaxed = true),
            context = context,
        )
        settings = SettingsRepository(context)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(710_001)
        awaitDigestCount(expected = 0)
        WeeklyDigestWorker.nowMillisOverride = null
        runBlocking { settings.setHoldByDefault(true) }
    }

    @After
    fun tearDown() {
        WeeklyDigestWorker.nowMillisOverride = null
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(710_001)
        db.close()
    }

    @Test
    fun planner_returns_null_for_empty_week() {
        assertNull(WeeklyDigestPlanner.plan(emptyList()))
    }

    @Test
    fun worker_with_empty_held_returns_success_and_posts_nothing_when_sunday() = runBlocking {
        // Empty Room held list is the precondition. Pin Sunday so this
        // reaches the planner, not the weekday early-return.
        WeeklyDigestWorker.nowMillisOverride = day(2026, Calendar.JULY, 26)
        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        // 0 is also the state one microsecond after setUp's cancel. The
        // awaitDigestCount(0) in setUp is what makes this assertion about
        // the worker, not about a cancel that has not landed.
        assertEquals(
            "Empty week must not leave a digest notification",
            0,
            awaitDigestSettled(expected = 0),
        )
    }

    @Test
    fun worker_posts_digest_on_sunday_and_not_on_monday() = runBlocking {
        val sunday = day(2026, Calendar.JULY, 26)
        val monday = day(2026, Calendar.JULY, 27)
        quarantine.holdOnArrival(
            sender = "18445550192",
            body = "PRE-APPROVED",
            receivedAtMillis = sunday,
            ruleId = "r",
            reason = "Filter: test",
            deleteAfterDays = 30,
            heldAtMillis = sunday,
            id = "digest-h1",
        )

        WeeklyDigestWorker.nowMillisOverride = sunday
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(
            "Sunday with held mail must post a digest",
            1,
            awaitDigestCount(expected = 1),
        )

        context.getSystemService(NotificationManager::class.java)?.cancel(710_001)
        // Wait for the cancel to land before the weekday run. Folding this
        // into the Monday assertion would treat a leftover Sunday digest as
        // a Monday post — the other side of the same race.
        assertEquals(
            "Cancel must land before the weekday run",
            0,
            awaitDigestCount(expected = 0),
        )

        WeeklyDigestWorker.nowMillisOverride = monday
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(
            "Weekday must not post a digest",
            0,
            awaitDigestSettled(expected = 0),
        )
    }

    @Test
    fun enqueueAll_updates_existing_seven_day_period_to_one_day() {
        val alreadyInitialized = WorkManager.isInitialized()
        println(
            "WorkManager.isInitialized() before ensureWorkManager: $alreadyInitialized",
        )
        ensureWorkManager()
        val wm = WorkManager.getInstance(context)
        val stale = PeriodicWorkRequestBuilder<WeeklyDigestWorker>(7, TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork(
            WeeklyDigestWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            stale,
        ).result.get()

        val before = wm.getWorkInfosForUniqueWork(WeeklyDigestWorker.UNIQUE_NAME).get()
        assertEquals(
            "stale 7-day schedule must be live, not a failed WorkSpec" +
                if (alreadyInitialized) " (WorkManager was already initialized)" else "",
            WorkInfo.State.ENQUEUED,
            before.single().state,
        )
        assertEquals(TimeUnit.DAYS.toMillis(7), before.single().periodicityInfo!!.repeatIntervalMillis)

        // enqueueAll already calls enqueueWeeklyDigest. Call once, wait on
        // the Operation — that is what production does on process start.
        WorkScheduler.enqueueWeeklyDigest(context).result.get()

        val after = wm.getWorkInfosForUniqueWork(WeeklyDigestWorker.UNIQUE_NAME).get()
        assertEquals(
            "updated 1-day schedule must be live, not a failed WorkSpec",
            WorkInfo.State.ENQUEUED,
            after.single().state,
        )
        assertEquals(
            "Existing installs must pick up the daily period via UPDATE, not KEEP",
            TimeUnit.DAYS.toMillis(1),
            after.single().periodicityInfo!!.repeatIntervalMillis,
        )
        assertFalse(
            "WorkManager was already initialized; test WorkerFactory may not have been installed",
            alreadyInitialized,
        )
    }

    private fun ensureWorkManager() {
        if (WorkManager.isInitialized()) return
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setWorkerFactory(digestWorkerFactory())
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    private fun digestWorkerFactory(): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                if (workerClassName != WeeklyDigestWorker::class.java.name) return null
                return WeeklyDigestWorker(
                    appContext,
                    workerParameters,
                    quarantine,
                    settings,
                )
            }
        }

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, dayOfMonth, 10, 0, 0)
        }.timeInMillis

    private fun activeDigestCount(): Int {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return 0
        return nm.activeNotifications.count { it.id == 710_001 }
    }

    /**
     * Poll until [activeDigestCount] equals [expected], or [timeoutMs] elapses.
     * Same shape as `awaitRoleHeld` in SmsRepositoryProviderInstrumentedTest.
     */
    private fun awaitDigestCount(expected: Int, timeoutMs: Long = DIGEST_POLL_TIMEOUT_MS): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var count = activeDigestCount()
        while (count != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(DIGEST_POLL_MS)
            count = activeDigestCount()
        }
        return count
    }

    /**
     * Watch [expected] for [settleMs]. Returns early if the count diverges,
     * so a delayed notify fails the "must not post" assertion instead of
     * racing past a single immediate read of 0.
     */
    private fun awaitDigestSettled(expected: Int, settleMs: Long = DIGEST_SETTLE_MS): Int {
        val deadline = SystemClock.elapsedRealtime() + settleMs
        var count = activeDigestCount()
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(DIGEST_POLL_MS)
            count = activeDigestCount()
            if (count != expected) return count
        }
        return count
    }

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder.from(context, WeeklyDigestWorker::class.java)
            .setWorkerFactory(digestWorkerFactory())
            .build()
        return worker.startWork().get()
    }

    private companion object {
        // 0 proves the poll is load-bearing (Defect 1 red). 5_000L is the fix.
        const val DIGEST_POLL_TIMEOUT_MS = 5_000L
        const val DIGEST_POLL_MS = 50L
        const val DIGEST_SETTLE_MS = 1_000L
    }
}
