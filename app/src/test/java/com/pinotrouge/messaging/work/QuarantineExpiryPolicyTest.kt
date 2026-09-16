package com.pinotrouge.messaging.work

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the quarantine table expiry rows that [QuarantineCommitWorker] relies on.
 * Failed provider writes must retain the row (skipped), never delete it.
 */
class QuarantineExpiryPolicyTest {

    @Test
    fun `rule with DELETE deletes permanently without writing provider`() {
        assertEquals(
            QuarantineExpiryPolicy.Outcome.DeletedPermanently,
            QuarantineExpiryPolicy.outcome(ruleHasDelete = true, providerWriteSucceeded = null),
        )
    }

    @Test
    fun `rule without DELETE and successful write files as read`() {
        assertEquals(
            QuarantineExpiryPolicy.Outcome.FiledAsRead,
            QuarantineExpiryPolicy.outcome(ruleHasDelete = false, providerWriteSucceeded = true),
        )
    }

    @Test
    fun `rule without DELETE and failed write retains for retry`() {
        assertEquals(
            QuarantineExpiryPolicy.Outcome.RetainedForRetry,
            QuarantineExpiryPolicy.outcome(ruleHasDelete = false, providerWriteSucceeded = false),
        )
    }

    @Test
    fun `summary counts match fake repository batch`() {
        val outcomes = listOf(
            QuarantineExpiryPolicy.outcome(ruleHasDelete = true, providerWriteSucceeded = null),
            QuarantineExpiryPolicy.outcome(ruleHasDelete = false, providerWriteSucceeded = true),
            QuarantineExpiryPolicy.outcome(ruleHasDelete = false, providerWriteSucceeded = false),
            QuarantineExpiryPolicy.outcome(ruleHasDelete = false, providerWriteSucceeded = true),
        )
        val summary = QuarantineExpiryPolicy.Summary.of(outcomes)
        assertEquals(1, summary.deleted)
        assertEquals(2, summary.filed)
        assertEquals(1, summary.skipped)
    }
}
