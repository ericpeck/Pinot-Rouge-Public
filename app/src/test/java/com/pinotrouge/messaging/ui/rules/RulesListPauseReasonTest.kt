package com.pinotrouge.messaging.ui.rules

import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.RegexPatterns
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.ui.builder.REGEX_UNRUNNABLE_REASON
import com.pinotrouge.messaging.ui.builder.UNSUPPORTED_RULE_REASON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RulesListPauseReasonTest {

    @Test
    fun `readable enabled rule has no pause copy`() {
        val rule = Rule(
            id = "r1",
            name = "Loan",
            enabled = true,
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "loan")),
            actions = setOf(Action.HOLD),
        )
        assertNull(ruleListPauseReason(rule))
    }

    @Test
    fun `unrunnable regex shows the regex pause copy even when enabled`() {
        val rule = Rule(
            id = "r2",
            name = "Legacy",
            enabled = true,
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(Condition.Text(TextOp.MATCHES_REGEX, "[a-z&&[^aeiou]]")),
            actions = setOf(Action.HOLD),
        )
        assertEquals(REGEX_UNRUNNABLE_REASON, ruleListPauseReason(rule))
        assertEquals(RegexPatterns.UNRUNNABLE_REASON, ruleListPauseReason(rule))
    }

    @Test
    fun `unsupported condition shows the unsupported pause copy`() {
        val rule = Rule(
            id = "r3",
            name = "Future",
            enabled = true,
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Unsupported("attachment|HAS_VIDEO|"),
            ),
            actions = setOf(Action.HOLD),
        )
        assertEquals(UNSUPPORTED_RULE_REASON, ruleListPauseReason(rule))
    }
}
