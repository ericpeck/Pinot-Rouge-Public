package com.pinotrouge.messaging.ui.sweep

import com.pinotrouge.messaging.data.repo.SweepCandidate
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.IncomingMessage
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InboxSweepTest {

    private val engine = DefaultRuleEngine()

    @Test
    fun first_match_wins_does_not_double_count() {
        val early = rule(
            id = "r1",
            name = "Contains loan",
            order = 0,
            condition = Condition.Text(TextOp.CONTAINS_ANY, "loan"),
        )
        val later = rule(
            id = "r2",
            name = "Unknown",
            order = 1,
            condition = Condition.Sender(SenderOp.NOT_IN_CONTACTS),
        )
        val samples = listOf(
            candidate(1, "Loan offer here", known = false),
            candidate(2, "hi", known = false),
        )
        val result = InboxSweep.scan(
            candidates = samples,
            rules = listOf(later, early), // deliberately unordered input
            neverFilterContacts = true,
            engine = engine,
        )
        assertEquals(2, result.scanned)
        assertEquals(2, result.wouldHold)
        // Loan message attributed to r1 only; unknown to r2
        assertEquals(1, result.groups.find { it.ruleId == "r1" }?.count)
        assertEquals(1, result.groups.find { it.ruleId == "r2" }?.count)
        assertEquals("r1", result.matches.first { it.providerMessageId == 1L }.ruleId)
    }

    @Test
    fun contacts_short_circuit_skips_all_rules() {
        val rule = rule(
            id = "r1",
            name = "All text",
            order = 0,
            condition = Condition.Text(TextOp.CONTAINS_ANY, "a"),
        )
        val result = InboxSweep.scan(
            candidates = listOf(candidate(1, "aaa", known = true)),
            rules = listOf(rule),
            neverFilterContacts = true,
            engine = engine,
        )
        assertEquals(0, result.wouldHold)
        assertEquals(0, result.caughtFromContacts)
    }

    @Test
    fun disabled_rules_are_ignored() {
        val rule = rule(
            id = "r1",
            name = "Off",
            order = 0,
            condition = Condition.Text(TextOp.CONTAINS_ANY, "loan"),
            enabled = false,
        )
        val result = InboxSweep.scan(
            candidates = listOf(candidate(1, "loan", known = false)),
            rules = listOf(rule),
            neverFilterContacts = true,
            engine = engine,
        )
        assertEquals(0, result.wouldHold)
    }

    @Test
    fun hold_only_rules_count() {
        val silenceOnly = Rule(
            id = "r1",
            name = "Silence",
            enabled = true,
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "x")),
            actions = setOf(Action.SILENCE),
            deleteAfterDays = 30,
            autoReplyText = null,
        )
        val result = InboxSweep.scan(
            candidates = listOf(candidate(1, "x", known = false)),
            rules = listOf(silenceOnly),
            neverFilterContacts = true,
            engine = engine,
        )
        assertEquals(0, result.wouldHold)
    }

    @Test
    fun scanned_count_is_candidate_size() {
        val result = InboxSweep.scan(
            candidates = (1L..5L).map { candidate(it, "n", known = false) },
            rules = emptyList(),
            neverFilterContacts = true,
            engine = engine,
        )
        assertEquals(5, result.scanned)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun wasRead_is_carried_onto_matches() {
        val rule = rule(
            id = "r1",
            name = "Loan",
            order = 0,
            condition = Condition.Text(TextOp.CONTAINS_ANY, "loan"),
        )
        val result = InboxSweep.scan(
            candidates = listOf(candidate(1, "loan", known = false, wasRead = true)),
            rules = listOf(rule),
            neverFilterContacts = true,
            engine = engine,
        )
        assertEquals(1, result.wouldHold)
        assertTrue(result.matches.single().wasRead)
    }

    private fun rule(
        id: String,
        name: String,
        order: Int,
        condition: Condition,
        enabled: Boolean = true,
    ) = Rule(
        id = id,
        name = name,
        enabled = enabled,
        order = order,
        match = MatchMode.ALL,
        conditions = listOf(condition),
        actions = setOf(Action.HOLD),
        deleteAfterDays = 30,
        autoReplyText = null,
    )

    private fun candidate(id: Long, body: String, known: Boolean, wasRead: Boolean = false) =
        SweepCandidate(
            providerMessageId = id,
            message = IncomingMessage(
                sender = "+1555$id",
                body = body,
                receivedAt = Instant.ofEpochMilli(1_000L + id),
            ),
            isKnownContact = known,
            wasRead = wasRead,
        )
}
