package com.pinotrouge.messaging.ui.filtered

import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * feat/rule-deletion: group headings for held messages when the rule is gone.
 * Orphan [rule_stats] rows prove the rule once existed (delete leaves stats).
 */
class ResolveRuleNameTest {

    @Test
    fun liveRule_usesName() {
        assertEquals(
            "Loan and crypto offers",
            FilteredViewModel.resolveRuleName(
                ruleId = "r1",
                namesById = mapOf("r1" to "Loan and crypto offers"),
                statsRuleIds = setOf("r1"),
            ),
        )
    }

    @Test
    fun systemHold_isSystem() {
        assertEquals(
            FilteredViewModel.LABEL_SYSTEM,
            FilteredViewModel.resolveRuleName(
                ruleId = IncomingMessagePipeline.RULE_ID_SYSTEM,
                namesById = emptyMap(),
                statsRuleIds = emptySet(),
            ),
        )
    }

    @Test
    fun orphanStats_isDeletedFilter() {
        assertEquals(
            FilteredViewModel.LABEL_DELETED_FILTER,
            FilteredViewModel.resolveRuleName(
                ruleId = "gone",
                namesById = emptyMap(),
                statsRuleIds = setOf("gone"),
            ),
        )
    }

    @Test
    fun missingRuleAndStats_isUnknownFilter() {
        assertEquals(
            FilteredViewModel.LABEL_UNKNOWN_FILTER,
            FilteredViewModel.resolveRuleName(
                ruleId = "never-existed",
                namesById = emptyMap(),
                statsRuleIds = emptySet(),
            ),
        )
    }
}
