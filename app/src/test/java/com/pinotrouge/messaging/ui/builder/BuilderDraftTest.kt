package com.pinotrouge.messaging.ui.builder

import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.rules.TimeOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine tests for the filter builder (prototype lines 738–796).
 *
 * Covers the three behaviours the task brief requires of the UI:
 * 1. Changing field resets operator and clears value.
 * 2. Last condition cannot be removed.
 * 3. The plain-words sentence updates as conditions change.
 */
class BuilderDraftTest {

    private val engine = DefaultRuleEngine()

    // ── Field reset ───────────────────────────────────────────────────────

    @Test
    fun `changing field resets operator to first of that field and clears value`() {
        var draft = BuilderDraft(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "(pre-?approved|crypto)"),
            ),
        )

        draft = draft.setField(0, ConditionField.Sender)

        val c = draft.conditions.single()
        assertTrue(c is Condition.Sender)
        c as Condition.Sender
        assertEquals(SenderOp.NOT_IN_CONTACTS, c.op)
        assertEquals("", c.value)
        assertFalse(c.needsValue)
    }

    @Test
    fun `changing field to text starts at contains any of with empty value`() {
        var draft = BuilderDraft.newRule()
        draft = draft.setField(0, ConditionField.Text)

        val c = draft.conditions.single() as Condition.Text
        assertEquals(TextOp.CONTAINS_ANY, c.op)
        assertEquals("", c.value)
        assertTrue(c.needsValue)
    }

    @Test
    fun `changing field to link then time never leaves a TextOp on a sender condition`() {
        var draft = BuilderDraft(
            conditions = listOf(Condition.Text(TextOp.DOES_NOT_CONTAIN, "sale")),
        )
        draft = draft.setField(0, ConditionField.Link)
        assertTrue(draft.conditions[0] is Condition.Link)
        assertEquals(LinkOp.PRESENT, (draft.conditions[0] as Condition.Link).op)

        draft = draft.setField(0, ConditionField.Time)
        assertTrue(draft.conditions[0] is Condition.Time)
        assertEquals(TimeOp.BETWEEN, (draft.conditions[0] as Condition.Time).op)
        assertEquals("", draft.conditions[0].value)
    }

    // ── Last condition ────────────────────────────────────────────────────

    @Test
    fun `cannot remove the last condition`() {
        val draft = BuilderDraft.newRule()
        assertEquals(1, draft.conditions.size)

        val after = draft.removeCondition(0)
        assertEquals(1, after.conditions.size)
        assertEquals(draft.conditions, after.conditions)
    }

    @Test
    fun `can remove a condition when more than one remain`() {
        var draft = BuilderDraft.newRule()
        draft = draft.addCondition()
        assertEquals(2, draft.conditions.size)

        draft = draft.removeCondition(0)
        assertEquals(1, draft.conditions.size)
        assertTrue(draft.conditions.single() is Condition.Text)
    }

    // ── Sentence updates ──────────────────────────────────────────────────

    @Test
    fun `plainWords updates when a condition is added`() {
        var draft = BuilderDraft.newRule()
        val before = engine.plainWords(draft.toPreviewRule())
        assertEquals(
            "When the sender is not in my contacts, hold it in Filtered.",
            before,
        )

        draft = draft.addCondition()
        draft = draft.setValue(1, "sale, % off")
        val after = engine.plainWords(draft.toPreviewRule())
        assertEquals(
            "When the sender is not in my contacts and the message text contains any of \u201Csale, % off\u201D, hold it in Filtered.",
            after,
        )
        assertTrue(after != before)
    }

    @Test
    fun `plainWords updates when match mode toggles to any`() {
        var draft = BuilderDraft(
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Link(LinkOp.PRESENT),
            ),
            actions = linkedSetOf(Action.HOLD),
        )
        assertTrue(engine.plainWords(draft.toPreviewRule()).contains(" and "))

        draft = draft.toggleMatch()
        assertEquals(MatchMode.ANY, draft.match)
        assertTrue(engine.plainWords(draft.toPreviewRule()).contains(" or "))
    }

    @Test
    fun `plainWords updates when an action chip is toggled`() {
        var draft = BuilderDraft.newRule()
        assertEquals(
            "When the sender is not in my contacts, hold it in Filtered.",
            engine.plainWords(draft.toPreviewRule()),
        )

        draft = draft.toggleAction(Action.SILENCE)
        assertEquals(
            "When the sender is not in my contacts, hold it in Filtered, do not make a sound.",
            engine.plainWords(draft.toPreviewRule()),
        )
    }

    // ── Defaults / prefill / save name ────────────────────────────────────

    @Test
    fun `new rule default is NOT_IN_CONTACTS and HOLD`() {
        val draft = BuilderDraft.newRule()
        assertEquals(1, draft.conditions.size)
        assertEquals(Condition.Sender(SenderOp.NOT_IN_CONTACTS), draft.conditions.single())
        assertEquals(setOf(Action.HOLD), draft.actions.toSet())
        assertEquals(MatchMode.ALL, draft.match)
        assertFalse(draft.isEditing)
    }

    @Test
    fun `prefill sender seeds IS condition HOLD and Messages from name`() {
        val draft = BuilderDraft.prefillSender("18445550192")
        assertEquals("Messages from 18445550192", draft.name)
        assertEquals(
            Condition.Sender(SenderOp.IS, "18445550192"),
            draft.conditions.single(),
        )
        assertEquals(setOf(Action.HOLD), draft.actions.toSet())
    }

    @Test
    fun `actions preserve LinkedHashSet insertion order`() {
        var draft = BuilderDraft(actions = linkedSetOf())
        draft = draft.toggleAction(Action.BLOCK)
        draft = draft.toggleAction(Action.HOLD)
        draft = draft.toggleAction(Action.SILENCE)
        assertEquals(
            listOf(Action.BLOCK, Action.HOLD, Action.SILENCE),
            draft.actions.toList(),
        )
    }

    @Test
    fun `delete days stepper clamps to 1–90 by sevens`() {
        var draft = BuilderDraft(deleteAfterDays = 30, actions = linkedSetOf(Action.DELETE))
        draft = draft.adjustDeleteDays(-7)
        assertEquals(23, draft.deleteAfterDays)
        // Drive down to floor
        repeat(10) { draft = draft.adjustDeleteDays(-7) }
        assertEquals(1, draft.deleteAfterDays)
        // Drive up to ceiling
        repeat(20) { draft = draft.adjustDeleteDays(+7) }
        assertEquals(90, draft.deleteAfterDays)
    }

    @Test
    fun `select labels differ from field labels for link and time`() {
        assertEquals("a link in the message", ConditionField.Link.selectLabel)
        assertEquals("the time it arrives", ConditionField.Time.selectLabel)
        assertEquals("the sender", ConditionField.Sender.selectLabel)
        assertEquals("the message text", ConditionField.Text.selectLabel)
    }

    // ── Round trip: Loan and crypto offers seed ───────────────────────────

    @Test
    fun `building Loan and crypto offers by hand matches seed plainWords`() {
        // Mirror SeedRules.loanAndCrypto / StarterFilterPacks Loans
        var draft = BuilderDraft(
            name = "Loan and crypto offers",
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Text(
                    TextOp.MATCHES_REGEX,
                    "(pre-?approved|no credit check|crypto|wallet)",
                ),
            ),
            actions = linkedSetOf(Action.HOLD, Action.BLOCK),
            deleteAfterDays = 30,
        )
        // Simulate the user building it via the state machine from a blank draft
        draft = BuilderDraft.newRule()
            .copy(name = "Loan and crypto offers")
            .setField(0, ConditionField.Text)
            .setOperator(0, TextOp.MATCHES_REGEX.name)
            .setValue(0, "(pre-?approved|no credit check|crypto|wallet)")
            .toggleMatch() // ALL → ANY
            .toggleAction(Action.BLOCK) // HOLD already on; add BLOCK

        val plain = engine.plainWords(draft.toPreviewRule())
        assertEquals(
            "When the message text matches the pattern \u201C(pre-?approved|no credit check|crypto|wallet)\u201D, hold it in Filtered, block the sender.",
            plain,
        )
    }

    @Test
    fun `blank name saves as Untitled filter`() {
        val draft = BuilderDraft.newRule().copy(name = "   ")
        val rule = draft.toRule(id = "x", order = 0, name = draft.name.trim().ifEmpty { UNTITLED_FILTER_NAME })
        assertEquals(UNTITLED_FILTER_NAME, rule.name)
    }

    @Test
    fun `backtest line wording for zero and non-zero contact hits`() {
        assertEquals(
            "This would have caught 23 of your last 200 texts, and none from your contacts.",
            formatBacktestLine(23, 200, 0),
        )
        assertEquals(
            "This would have caught 5 of your last 200 texts, and 2 from your contacts.",
            formatBacktestLine(5, 200, 2),
        )
    }

    @Test
    fun `fromRule keeps an unsupported condition and opens read-only`() {
        val saved = Rule(
            id = "future",
            name = "Photos",
            order = 0,
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Unsupported("attachment|HAS_VIDEO|"),
            ),
            actions = linkedSetOf(Action.HOLD),
        )
        val draft = BuilderDraft.fromRule(saved)
        assertTrue(draft.isReadOnly)
        assertEquals(2, draft.conditions.size)
        assertTrue(draft.conditions[1] is Condition.Unsupported)
        assertEquals(draft, draft.setField(0, ConditionField.Text))
        assertEquals(draft, draft.toggleMatch())
    }

    @Test
    fun `fromRule opens read-only when an action name is unknown`() {
        val saved = Rule(
            id = "future-action",
            name = "Photos",
            order = 0,
            conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
            actions = linkedSetOf(Action.HOLD, Action.parse("FLY")),
        )
        val draft = BuilderDraft.fromRule(saved)
        assertTrue(draft.isReadOnly)
        assertEquals(draft, draft.toggleAction(Action.SILENCE))
        assertEquals(listOf(Action.HOLD, Action.parse("FLY")), draft.actions.toList())
    }

    @Test
    fun `unsupported reason copy is Eric's sentence`() {
        assertEquals(
            "This filter was written in a newer version of Pinot Rouge. It is paused until you update, and nothing in it has been changed.",
            UNSUPPORTED_RULE_REASON,
        )
    }

    @Test
    fun `save toast uses curly quotes`() {
        assertEquals(
            "\u201CLoan and crypto offers\u201D is on. It starts with the next message.",
            saveToastMessage("Loan and crypto offers"),
        )
    }

    // ── fix/remove-auto-reply: keep data, stop offering ───────────────────

    @Test
    fun `ACTION_CHIP_ORDER does not offer REPLY`() {
        assertFalse(ACTION_CHIP_ORDER.contains(Action.REPLY))
        assertEquals(
            listOf(
                Action.HOLD,
                Action.SILENCE,
                Action.DELETE,
                Action.BLOCK,
                Action.READ,
            ),
            ACTION_CHIP_ORDER,
        )
    }

    @Test
    fun `saved REPLY rule loads renders and saves without dropping data`() {
        // A rule authored before auto-reply was removed must still round-trip:
        // open in the builder, render English, save again — without sending.
        val saved = Rule(
            id = "legacy-reply",
            name = "No offers",
            order = 2,
            match = MatchMode.ALL,
            conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
            actions = linkedSetOf(Action.HOLD, Action.REPLY),
            autoReplyText = "Sorry, this number does not accept offers.",
        )

        val draft = BuilderDraft.fromRule(saved)
        assertEquals(setOf(Action.HOLD, Action.REPLY), draft.actions.toSet())
        assertEquals("Sorry, this number does not accept offers.", draft.autoReplyText)
        assertTrue(draft.isEditing)

        assertEquals(
            "When the sender is not in my contacts, hold it in Filtered, send an automatic reply.",
            engine.plainWords(draft.toPreviewRule()),
        )

        // Save again without touching actions — REPLY and text must survive.
        val rewritten = draft.toRule(
            id = saved.id,
            order = saved.order,
            name = draft.name.trim().ifEmpty { UNTITLED_FILTER_NAME },
        )
        assertEquals(saved.actions, rewritten.actions)
        assertEquals(saved.autoReplyText, rewritten.autoReplyText)
        assertEquals(saved.name, rewritten.name)
    }

    // ── fix/delete-requires-hold: DELETE is meaningless without HOLD ──────

    @Test
    fun `selecting DELETE from a draft without HOLD yields both`() {
        var draft = BuilderDraft(actions = linkedSetOf())
        draft = draft.toggleAction(Action.DELETE)
        assertEquals(setOf(Action.HOLD, Action.DELETE), draft.actions.toSet())
    }

    @Test
    fun `deselecting HOLD while DELETE is set yields neither`() {
        var draft = BuilderDraft(actions = linkedSetOf(Action.HOLD, Action.DELETE))
        draft = draft.toggleAction(Action.HOLD)
        assertEquals(emptySet<Action>(), draft.actions.toSet())
    }

    @Test
    fun `selecting DELETE then deselecting it leaves HOLD`() {
        var draft = BuilderDraft.newRule()
        draft = draft.toggleAction(Action.DELETE)
        assertEquals(setOf(Action.HOLD, Action.DELETE), draft.actions.toSet())
        draft = draft.toggleAction(Action.DELETE)
        assertEquals(setOf(Action.HOLD), draft.actions.toSet())
    }

    @Test
    fun `SILENCE BLOCK and READ still toggle independently`() {
        var draft = BuilderDraft.newRule()
        draft = draft.toggleAction(Action.SILENCE)
        draft = draft.toggleAction(Action.BLOCK)
        draft = draft.toggleAction(Action.READ)
        assertEquals(
            setOf(Action.HOLD, Action.SILENCE, Action.BLOCK, Action.READ),
            draft.actions.toSet(),
        )

        draft = draft.toggleAction(Action.SILENCE)
        assertEquals(setOf(Action.HOLD, Action.BLOCK, Action.READ), draft.actions.toSet())

        draft = draft.toggleAction(Action.HOLD)
        assertEquals(setOf(Action.BLOCK, Action.READ), draft.actions.toSet())
        draft = draft.toggleAction(Action.BLOCK)
        assertEquals(setOf(Action.READ), draft.actions.toSet())
        draft = draft.toggleAction(Action.READ)
        assertEquals(emptySet<Action>(), draft.actions.toSet())
    }

    @Test
    fun `plainWords says delete after N days only when HOLD is also selected`() {
        val chipActions = listOf(
            Action.HOLD,
            Action.SILENCE,
            Action.DELETE,
            Action.BLOCK,
            Action.READ,
        )
        for (mask in 0 until (1 shl chipActions.size)) {
            var draft = BuilderDraft(actions = linkedSetOf())
            chipActions.forEachIndexed { index, action ->
                if (mask and (1 shl index) != 0) {
                    draft = draft.toggleAction(action)
                }
            }
            val sentence = engine.plainWords(draft.toPreviewRule())
            if (sentence.contains("delete it after")) {
                assertTrue(
                    "plainWords promised a deletion without HOLD for mask=$mask " +
                        "actions=${draft.actions}: $sentence",
                    Action.HOLD in draft.actions,
                )
            } else {
                assertFalse(
                    "DELETE is set but the sentence omitted it for mask=$mask: $sentence",
                    Action.DELETE in draft.actions,
                )
            }
        }
    }

    @Test
    fun `DELETE-selected draft plainWords holds then deletes`() {
        val fromDefault = BuilderDraft.newRule().toggleAction(Action.DELETE)
        assertEquals(
            "When the sender is not in my contacts, hold it in Filtered, delete it after 30 days.",
            engine.plainWords(fromDefault.toPreviewRule()),
        )

        val fromEmpty = BuilderDraft(actions = linkedSetOf()).toggleAction(Action.DELETE)
        assertEquals(
            "When the sender is not in my contacts, hold it in Filtered, delete it after 30 days.",
            engine.plainWords(fromEmpty.toPreviewRule()),
        )
    }

    @Test
    fun `fromRule keeps DELETE without HOLD so stored rules are not rewritten`() {
        val saved = Rule(
            id = "legacy-delete",
            name = "Old auto-delete",
            order = 0,
            conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
            actions = linkedSetOf(Action.DELETE, Action.BLOCK),
            deleteAfterDays = 30,
        )
        val draft = BuilderDraft.fromRule(saved)
        assertEquals(setOf(Action.DELETE, Action.BLOCK), draft.actions.toSet())
        assertEquals(
            "When the sender is not in my contacts, delete it after 30 days, block the sender.",
            engine.plainWords(draft.toPreviewRule()),
        )
        val rewritten = draft.toRule(id = saved.id, order = saved.order, name = saved.name)
        assertEquals(saved.actions, rewritten.actions)
    }
}
