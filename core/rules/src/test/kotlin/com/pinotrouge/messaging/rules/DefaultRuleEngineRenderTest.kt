package com.pinotrouge.messaging.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins summarize / plainWords to the prototype wording for all four seed rules.
 * Curly quotes are U+201C / U+201D.
 */
class DefaultRuleEngineRenderTest {

    private val engine = DefaultRuleEngine()

    private val qL = "\u201C"
    private val qR = "\u201D"

    @Test
    fun `summarize seed - Links from people I do not know`() {
        assertEquals(
            "If the sender is not in my contacts and a link is present → hold it in Filtered, stay silent",
            engine.summarize(SeedRules.linksFromUnknown),
        )
    }

    @Test
    fun `plainWords seed - Links from people I do not know`() {
        assertEquals(
            "When the sender is not in my contacts and a link is present, hold it in Filtered, do not make a sound.",
            engine.plainWords(SeedRules.linksFromUnknown),
        )
    }

    @Test
    fun `summarize seed - Promotions and sales`() {
        assertEquals(
            "If the message text contains any of ${qL}sale, % off, coupon, deal${qR} → hold it in Filtered, stay silent, auto-delete",
            engine.summarize(SeedRules.promotionsAndSales),
        )
    }

    @Test
    fun `plainWords seed - Promotions and sales`() {
        assertEquals(
            "When the message text contains any of ${qL}sale, % off, coupon, deal${qR}, hold it in Filtered, do not make a sound, delete it after 14 days.",
            engine.plainWords(SeedRules.promotionsAndSales),
        )
    }

    @Test
    fun `summarize seed - Loan and crypto offers`() {
        assertEquals(
            "If the message text matches the pattern ${qL}(pre-?approved|no credit check|crypto|wallet)${qR} → hold it in Filtered, block the sender",
            engine.summarize(SeedRules.loanAndCrypto),
        )
    }

    @Test
    fun `plainWords seed - Loan and crypto offers`() {
        assertEquals(
            "When the message text matches the pattern ${qL}(pre-?approved|no credit check|crypto|wallet)${qR}, hold it in Filtered, block the sender.",
            engine.plainWords(SeedRules.loanAndCrypto),
        )
    }

    @Test
    fun `summarize seed - Quiet after 10 pm`() {
        assertEquals(
            "If the arrival time is between ${qL}22:00 – 07:00${qR} → stay silent",
            engine.summarize(SeedRules.quietAfterTen),
        )
    }

    @Test
    fun `plainWords seed - Quiet after 10 pm`() {
        assertEquals(
            "When the arrival time is between ${qL}22:00 – 07:00${qR}, do not make a sound.",
            engine.plainWords(SeedRules.quietAfterTen),
        )
    }

    @Test
    fun `summarize empty actions says do nothing yet`() {
        val rule = Rule(
            id = "x",
            name = "Empty",
            order = 0,
            conditions = listOf(Condition.Sender(SenderOp.IS_SHORT_CODE)),
            actions = emptySet(),
        )
        assertEquals(
            "If the sender is a short code → do nothing yet",
            engine.summarize(rule),
        )
    }

    @Test
    fun `plainWords empty actions prompts to pick an action`() {
        val rule = Rule(
            id = "x",
            name = "Empty",
            order = 0,
            conditions = listOf(Condition.Sender(SenderOp.IS_SHORT_CODE)),
            actions = emptySet(),
        )
        assertEquals(
            "When the sender is a short code, nothing happens yet — pick an action.",
            engine.plainWords(rule),
        )
    }

    @Test
    fun `summarize uses or for ANY match mode`() {
        val rule = Rule(
            id = "x",
            name = "Any",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Text(TextOp.CONTAINS_ANY, "a"),
                Condition.Text(TextOp.CONTAINS_ANY, "b"),
            ),
            actions = setOf(Action.HOLD),
        )
        assertEquals(
            "If the message text contains any of ${qL}a${qR} or the message text contains any of ${qL}b${qR} → hold it in Filtered",
            engine.summarize(rule),
        )
    }

    @Test
    fun `plainWords uses distinct silence and delete wording from summarize`() {
        val rule = Rule(
            id = "x",
            name = "Both",
            order = 0,
            conditions = listOf(Condition.Link(LinkOp.PRESENT)),
            actions = linkedSetOf(Action.SILENCE, Action.DELETE, Action.REPLY, Action.READ),
            deleteAfterDays = 7,
        )
        assertEquals(
            "If a link is present → stay silent, auto-delete, auto-reply, mark it read",
            engine.summarize(rule),
        )
        assertEquals(
            "When a link is present, do not make a sound, delete it after 7 days, send an automatic reply, mark it read.",
            engine.plainWords(rule),
        )
    }

    @Test
    fun `unknown match mode does not render as or`() {
        val rule = Rule(
            id = "x",
            name = "Future",
            order = 0,
            match = MatchMode.parse("XOR"),
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Text(TextOp.CONTAINS_ANY, "a"),
            ),
            actions = setOf(Action.HOLD),
        )
        val summary = engine.summarize(rule)
        val words = engine.plainWords(rule)
        assertFalse(summary.contains(" or "))
        assertFalse(words.contains(" or "))
        assertTrue(summary.contains(" and "))
        assertTrue(rule.isUnreadable)
        assertEquals(FilterDecision.Allow, engine.evaluate(
            IncomingMessage("18445550192", "a", java.time.Instant.EPOCH),
            listOf(rule),
            EvaluationContext(isKnownContact = false, neverFilterContacts = true),
        ))
    }
}
