package com.pinotrouge.messaging.rules

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The audit's ReDoS probe: nested quantifiers against a 100-character
 * all-`a` body. Java's matcher exceeded five seconds on API 33 and 36;
 * RE2 must finish in well under a quarter second on both.
 */
@RunWith(AndroidJUnit4::class)
class RegexAdversarialInstrumentedTest {

    private val engine = DefaultRuleEngine()

    @Test
    fun nestedQuantifiersFinishQuicklyOnDevice() {
        val rule = Rule(
            id = "re2",
            name = "nested",
            order = 0,
            conditions = listOf(Condition.Text(TextOp.MATCHES_REGEX, "(a+)+b")),
            actions = linkedSetOf(Action.HOLD),
        )
        assertFalse(rule.isUnreadable)
        val body = "a".repeat(100)
        val started = System.nanoTime()
        val decision = engine.evaluate(
            IncomingMessage(
                sender = "5550100",
                body = body,
                receivedAt = Instant.EPOCH,
            ),
            listOf(rule),
            EvaluationContext(isKnownContact = false, neverFilterContacts = true),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(FilterDecision.Allow, decision)
        assertTrue("nested quantifiers took ${elapsedMs}ms on this device", elapsedMs < 250L)
    }

    @Test
    fun lookaroundPausesTheRuleOnDevice() {
        val rule = Rule(
            id = "look",
            name = "lookaround",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "(?=pre-approved).+"),
                Condition.Sender(SenderOp.IS_SHORT_CODE),
            ),
            actions = linkedSetOf(Action.HOLD),
        )
        assertTrue(rule.isUnreadable)
        val decision = engine.evaluate(
            IncomingMessage(
                sender = "55555",
                body = "pre-approved",
                receivedAt = Instant.EPOCH,
            ),
            listOf(rule),
            EvaluationContext(isKnownContact = false, neverFilterContacts = true),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun nestedClassUnionMatchesOnDevice() {
        val rule = Rule(
            id = "nested",
            name = "nested class",
            order = 0,
            conditions = listOf(Condition.Text(TextOp.MATCHES_REGEX, "[a-d[m-p]]")),
            actions = linkedSetOf(Action.HOLD),
        )
        assertFalse(rule.isUnreadable)
        val decision = engine.evaluate(
            IncomingMessage(
                sender = "5550100",
                body = "a",
                receivedAt = Instant.EPOCH,
            ),
            listOf(rule),
            EvaluationContext(isKnownContact = false, neverFilterContacts = true),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun intersectionPausesAnyOnDevice() {
        val rule = Rule(
            id = "inter",
            name = "intersection",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "[a-z&&[^aeiou]]"),
                Condition.Sender(SenderOp.IS_SHORT_CODE),
            ),
            actions = linkedSetOf(Action.HOLD),
        )
        assertTrue(rule.isUnreadable)
        val decision = engine.evaluate(
            IncomingMessage(
                sender = "55555",
                body = "bcd",
                receivedAt = Instant.EPOCH,
            ),
            listOf(rule),
            EvaluationContext(isKnownContact = false, neverFilterContacts = true),
        )
        assertEquals(FilterDecision.Allow, decision)
    }
}
