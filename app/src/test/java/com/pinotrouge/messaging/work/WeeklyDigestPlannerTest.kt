package com.pinotrouge.messaging.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WeeklyDigestPlannerTest {

    @Test
    fun `empty week posts nothing`() {
        assertNull(WeeklyDigestPlanner.plan(emptyList()))
    }

    @Test
    fun `counts messages and distinct senders`() {
        val plan = WeeklyDigestPlanner.plan(
            listOf("a", "b", "a", "c"),
        )
        requireNotNull(plan)
        assertEquals(4, plan.messageCount)
        assertEquals(3, plan.senderCount)
        assertTrue(plan.body.contains("4 messages"))
        assertTrue(plan.body.contains("3 senders"))
    }

    @Test
    fun `single message wording`() {
        val plan = WeeklyDigestPlanner.plan(listOf("18445550192"))
        requireNotNull(plan)
        assertEquals(1, plan.messageCount)
        assertEquals(1, plan.senderCount)
        assertTrue(plan.body.startsWith("1 message held"))
    }

    @Test
    fun `sunday check`() {
        val sunday = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.JULY)
            set(Calendar.DAY_OF_MONTH, 26) // a Sunday
            set(Calendar.HOUR_OF_DAY, 10)
        }.timeInMillis
        val monday = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.JULY)
            set(Calendar.DAY_OF_MONTH, 27)
        }.timeInMillis
        assertTrue(WeeklyDigestWorker.isSunday(sunday))
        assertTrue(!WeeklyDigestWorker.isSunday(monday))
    }
}
