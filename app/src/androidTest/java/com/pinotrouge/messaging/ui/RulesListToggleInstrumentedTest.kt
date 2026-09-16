package com.pinotrouge.messaging.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule as FilterRule
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.ui.rules.RuleListItem
import com.pinotrouge.messaging.ui.rules.RulesListScreen
import com.pinotrouge.messaging.ui.rules.RulesListUiState
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 — toggling a rule must flip enabled via onToggle and must **not**
 * fire onReorder (switch must not collide with long-press drag reorder).
 */
@RunWith(AndroidJUnit4::class)
class RulesListToggleInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toggling_switch_calls_onToggle_without_reorder() {
        val filterRule = FilterRule(
            id = "r1",
            name = "Loan and crypto offers",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "loan")),
            actions = setOf(Action.HOLD),
            enabled = true,
        )
        var toggled: Pair<FilterRule, Boolean>? = null
        var reorderCalls = 0
        var items by mutableStateOf(
            listOf(
                RuleListItem(
                    rule = filterRule,
                    summary = "When the text contains loan…",
                    caughtLabel = "0 caught this month",
                    lastCaughtLabel = "Never",
                ),
            ),
        )

        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                RulesListScreen(
                    state = RulesListUiState(items = items),
                    onNewFilter = {},
                    onEditFilter = {},
                    onToggle = { r, enabled ->
                        toggled = r to enabled
                        items = items.map {
                            if (it.rule.id == r.id) {
                                it.copy(rule = it.rule.copy(enabled = enabled))
                            } else {
                                it
                            }
                        }
                    },
                    onReorder = { reorderCalls++ },
                )
            }
        }

        composeRule.onNodeWithText("Loan and crypto offers").assertIsDisplayed()
        // PinotSwitch uses clickable(role = Role.Switch); it does not set
        // ToggleableState, so match Role rather than isToggleable().
        val switchMatcher = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Switch,
        )
        composeRule.onNode(switchMatcher).performClick()
        composeRule.waitForIdle()

        assertNotNull(toggled)
        assertEquals("r1", toggled!!.first.id)
        assertFalse("Switch was on; click should request off", toggled!!.second)
        assertEquals(
            "Toggling a rule must never start a reorder batch",
            0,
            reorderCalls,
        )
    }
}
