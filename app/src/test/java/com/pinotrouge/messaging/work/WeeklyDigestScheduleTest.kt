package com.pinotrouge.messaging.work

import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Shape B: daily period + Sunday guard. Pins the schedule so a non-Sunday
 * install can still fire the digest the next Sunday.
 */
class WeeklyDigestScheduleTest {

    @Test
    fun digest_repeats_daily_not_every_seven_days() {
        assertEquals(1L, WorkScheduler.WEEKLY_DIGEST_REPEAT_INTERVAL_DAYS)
        assertEquals(TimeUnit.DAYS.toMillis(1), TimeUnit.DAYS.toMillis(
            WorkScheduler.WEEKLY_DIGEST_REPEAT_INTERVAL_DAYS,
        ))
    }

    @Test
    fun digest_existing_work_policy_is_update() {
        assertEquals(
            ExistingPeriodicWorkPolicy.UPDATE,
            WorkScheduler.WEEKLY_DIGEST_EXISTING_POLICY,
        )
    }

    @Test
    fun isSunday_true_only_on_sunday() {
        // 2026-07-26 is a Sunday (pinned in WeeklyDigestPlannerTest).
        val sunday = day(2026, Calendar.JULY, 26)
        assertTrue(WeeklyDigestWorker.isSunday(sunday))
        assertFalse(WeeklyDigestWorker.isSunday(day(2026, Calendar.JULY, 27))) // Mon
        assertFalse(WeeklyDigestWorker.isSunday(day(2026, Calendar.JULY, 28))) // Tue
        assertFalse(WeeklyDigestWorker.isSunday(day(2026, Calendar.JULY, 29))) // Wed
        assertFalse(WeeklyDigestWorker.isSunday(day(2026, Calendar.JULY, 30))) // Thu
        assertFalse(WeeklyDigestWorker.isSunday(day(2026, Calendar.JULY, 31))) // Fri
        assertFalse(WeeklyDigestWorker.isSunday(day(2026, Calendar.AUGUST, 1))) // Sat
        assertTrue(WeeklyDigestWorker.isSunday(day(2026, Calendar.AUGUST, 2))) // next Sun
    }

    @Test
    fun extra_open_filtered_is_removed() {
        val extra = WeeklyDigestWorker::class.java.fields.find { it.name == "EXTRA_OPEN_FILTERED" }
        assertNull(
            "EXTRA_OPEN_FILTERED must be deleted so the deep link is the only tap path",
            extra,
        )
    }

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, dayOfMonth, 10, 0, 0)
        }.timeInMillis
}
