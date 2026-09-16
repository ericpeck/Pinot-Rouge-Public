package com.pinotrouge.messaging.rules

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DefaultRuleEngineBacktestTest {

    private val engine = DefaultRuleEngine()
    private val now = Instant.parse("2024-06-15T12:00:00Z")

    private fun sample(
        body: String,
        sender: String = "18445550192",
        known: Boolean = false,
    ) = BacktestSample(
        message = IncomingMessage(sender, body, now),
        isKnownContact = known,
    )

    @Test
    fun `backtest counts catches and contact split`() {
        val rule = Rule(
            id = "r",
            name = "Sales",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "sale, deal")),
            actions = setOf(Action.HOLD),
        )
        val samples = listOf(
            sample("big sale today", known = false),
            sample("hello from Mom", known = true),
            sample("deal of the week", known = true),
            sample("package delivered", known = false),
            sample("FLASH SALE", known = false),
        )
        val result = engine.backtest(
            rule,
            samples,
            EvaluationContext(isKnownContact = false, neverFilterContacts = true),
        )
        assertEquals(5, result.sampled)
        assertEquals(3, result.caught)
        assertEquals(1, result.caughtFromContacts)
    }

    @Test
    fun `backtest reports zero contact catches when none hit`() {
        val rule = SeedRules.loanAndCrypto
        val samples = listOf(
            sample("PRE-APPROVED loan", known = false),
            sample("lunch?", known = true),
            sample("crypto wallet tip", known = false),
        )
        val result = engine.backtest(
            rule,
            samples,
            EvaluationContext(isKnownContact = false),
        )
        assertEquals(3, result.sampled)
        assertEquals(2, result.caught)
        assertEquals(0, result.caughtFromContacts)
    }

    @Test
    fun `backtest with empty conditions catches nothing`() {
        val rule = Rule(
            id = "empty",
            name = "Empty",
            order = 0,
            conditions = emptyList(),
            actions = setOf(Action.HOLD),
        )
        val samples = listOf(sample("anything"))
        val result = engine.backtest(
            rule,
            samples,
            EvaluationContext(isKnownContact = false),
        )
        assertEquals(1, result.sampled)
        assertEquals(0, result.caught)
        assertEquals(0, result.caughtFromContacts)
    }

    @Test
    fun `backtest still evaluates NOT_IN_CONTACTS using each sample flag`() {
        val rule = SeedRules.linksFromUnknown
        val samples = listOf(
            sample("see https://evil.example/x", known = false),
            sample("see https://evil.example/x", known = true),
            sample("no url here", known = false),
        )
        val result = engine.backtest(
            rule,
            samples,
            EvaluationContext(isKnownContact = false, neverFilterContacts = true),
        )
        // Only the unknown-contact + link sample matches; contact short-circuit
        // is intentionally not applied so the builder can warn about contact hits
        // for other rules — here NOT_IN_CONTACTS itself filters contacts out.
        assertEquals(1, result.caught)
        assertEquals(0, result.caughtFromContacts)
    }
}
