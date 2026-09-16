package com.pinotrouge.messaging.ui.filtered

import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeldMessageDisplayReasonTest {

    @Test
    fun `filter catch suppresses Filter-prefixed reason`() {
        assertNull(
            HeldMessageViewModel.displayReason(
                ruleId = "r3",
                ruleName = "Loan and crypto offers",
                reason = "Filter: Loan and crypto offers",
            ),
        )
    }

    @Test
    fun `filter catch suppresses bare rule name as reason`() {
        assertNull(
            HeldMessageViewModel.displayReason(
                ruleId = "r1",
                ruleName = "Promotions and sales",
                reason = "Promotions and sales",
            ),
        )
    }

    @Test
    fun `blocked sender keeps reason`() {
        assertEquals(
            "Blocked sender",
            HeldMessageViewModel.displayReason(
                ruleId = IncomingMessagePipeline.RULE_ID_SYSTEM,
                ruleName = "System",
                reason = IncomingMessagePipeline.REASON_BLOCKED_SENDER,
            ),
        )
    }

    @Test
    fun `could not be filed keeps reason`() {
        assertEquals(
            IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED,
            HeldMessageViewModel.displayReason(
                ruleId = IncomingMessagePipeline.RULE_ID_SYSTEM,
                ruleName = "System",
                reason = IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED,
            ),
        )
    }

    @Test
    fun `blank reason is null`() {
        assertNull(
            HeldMessageViewModel.displayReason(
                ruleId = "r1",
                ruleName = "Any",
                reason = "  ",
            ),
        )
    }
}
