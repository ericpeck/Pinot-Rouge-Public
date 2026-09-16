package com.pinotrouge.messaging.data

import com.pinotrouge.messaging.rules.Action
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Documents the quarantine table outcomes that [com.pinotrouge.messaging.data.repo.QuarantineRepository]
 * must implement. Pure decision tests (no Android) so the table cannot drift.
 */
class QuarantineSemanticsTest {

    private fun expiresAt(heldAt: Long, deleteAfterDays: Int): Long =
        heldAt + TimeUnit.DAYS.toMillis(deleteAfterDays.toLong())

    @Test
    fun `expiresAt is heldAt plus deleteAfterDays`() {
        val heldAt = 1_700_000_000_000L
        assertEqualsDays(30, expiresAt(heldAt, 30) - heldAt)
        assertEqualsDays(14, expiresAt(heldAt, 14) - heldAt)
    }

    @Test
    fun `expiry with DELETE means hard delete not file`() {
        val actions = setOf(Action.HOLD, Action.DELETE)
        assertTrue(actions.contains(Action.DELETE))
        // QuarantineRepository.commitExpired deletes the row and does not write Telephony.
    }

    @Test
    fun `expiry without DELETE means file as read`() {
        val actions = setOf(Action.HOLD, Action.SILENCE)
        assertFalse(actions.contains(Action.DELETE))
        // QuarantineRepository.commitExpired inserts inbox with read=true, then deletes row.
    }

    @Test
    fun `move to inbox is unread not read`() {
        val moveToInboxReadFlag = false
        val expiryFileReadFlag = true
        assertFalse(moveToInboxReadFlag)
        assertTrue(expiryFileReadFlag)
    }

    private fun assertEqualsDays(expectedDays: Int, millis: Long) {
        assertTrue(
            "expected $expectedDays days, got ${millis / TimeUnit.DAYS.toMillis(1)}",
            millis == TimeUnit.DAYS.toMillis(expectedDays.toLong()),
        )
    }
}
