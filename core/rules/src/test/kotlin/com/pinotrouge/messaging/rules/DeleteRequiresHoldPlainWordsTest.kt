package com.pinotrouge.messaging.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The builder can no longer produce DELETE without HOLD. For every action set
 * that pairing allows, [RuleEngine.plainWords] may say "delete it after N days"
 * only when HOLD is also present.
 *
 * Stored rules that already carry DELETE without HOLD are out of this set;
 * [DefaultRuleEngineRenderTest] still pins their renderer strings as-is.
 */
class DeleteRequiresHoldPlainWordsTest {

    private val engine = DefaultRuleEngine()

    private val chipActions = listOf(
        Action.HOLD,
        Action.SILENCE,
        Action.DELETE,
        Action.BLOCK,
        Action.READ,
    )

    @Test
    fun `plainWords says delete after N days only when HOLD is also in the set`() {
        for (mask in 0 until (1 shl chipActions.size)) {
            val produced = LinkedHashSet<Action>()
            chipActions.forEachIndexed { index, action ->
                if (mask and (1 shl index) != 0) produced.add(action)
            }
            // The sets BuilderDraft.toggleAction can now emit: DELETE implies HOLD.
            if (Action.DELETE in produced) produced.add(Action.HOLD)
            if (Action.HOLD !in produced) produced.remove(Action.DELETE)

            val sentence = engine.plainWords(ruleWith(produced))
            if (sentence.contains("delete it after")) {
                assertTrue(
                    "plainWords promised a deletion without HOLD for $produced: $sentence",
                    Action.HOLD in produced,
                )
            } else {
                assertFalse(
                    "DELETE is set but the sentence omitted it for $produced: $sentence",
                    Action.DELETE in produced,
                )
            }
        }
    }

    @Test
    fun `HOLD then DELETE reads hold it in Filtered then delete it after N days`() {
        val sentence = engine.plainWords(
            ruleWith(linkedSetOf(Action.HOLD, Action.DELETE)),
        )
        assertTrue(sentence.startsWith("When the sender is not in my contacts, hold it in Filtered, delete it after 30 days."))
    }

    private fun ruleWith(actions: Set<Action>): Rule = Rule(
        id = "draft",
        name = "Untitled filter",
        order = 0,
        conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
        actions = actions,
        deleteAfterDays = 30,
    )
}
