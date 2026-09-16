package com.pinotrouge.messaging.ui.thread

import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.EvaluationContext
import com.pinotrouge.messaging.rules.FilterDecision
import com.pinotrouge.messaging.rules.IncomingMessage
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Funnel prefill must use the telephony address so `Sender IS` can match
 * real PDUs (fix/wave-3-followups Bug 1).
 */
class ThreadPrefillTest {

    private val engine = DefaultRuleEngine()

    @Test
    fun `contact-backed thread prefills address not display name`() {
        val address = "+15551234"
        val displayName = "Mom"
        val prefill = ThreadViewModel.senderMatchForPrefill(address, displayName)
        assertEquals(address, prefill)
        assertTrue(prefill != displayName)
    }

    @Test
    fun `prefill address matches incoming number after normalisation`() {
        val address = "15551234567"
        val prefill = ThreadViewModel.senderMatchForPrefill(
            address = address,
            displayName = "Dev Patel",
        )
        val rule = Rule(
            id = "funnel",
            name = "Messages from Dev Patel",
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(Condition.Sender(SenderOp.IS, prefill)),
        )
        val decision = engine.evaluate(
            IncomingMessage(
                sender = "+1 (555) 123-4567",
                body = "hi",
                receivedAt = Instant.parse("2024-06-15T12:00:00Z"),
            ),
            listOf(rule),
            EvaluationContext(isKnownContact = true, neverFilterContacts = false),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `display name as condition value does not match phone sender`() {
        val rule = Rule(
            id = "broken",
            name = "Messages from Mom",
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(Condition.Sender(SenderOp.IS, "Mom")),
        )
        val decision = engine.evaluate(
            IncomingMessage(
                sender = "+15551234",
                body = "hi",
                receivedAt = Instant.parse("2024-06-15T12:00:00Z"),
            ),
            listOf(rule),
            EvaluationContext(isKnownContact = true, neverFilterContacts = false),
        )
        assertEquals(FilterDecision.Allow, decision)
    }
}
