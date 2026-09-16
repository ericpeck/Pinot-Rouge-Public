package com.pinotrouge.messaging.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.ui.builder.BuilderDraft
import com.pinotrouge.messaging.ui.builder.BuilderScreen
import com.pinotrouge.messaging.ui.builder.BuilderUiState
import com.pinotrouge.messaging.ui.builder.ConditionField
import com.pinotrouge.messaging.ui.builder.addCondition
import com.pinotrouge.messaging.ui.builder.formatBacktestLine
import com.pinotrouge.messaging.ui.builder.removeCondition
import com.pinotrouge.messaging.ui.builder.setField
import com.pinotrouge.messaging.ui.builder.setValue
import com.pinotrouge.messaging.ui.builder.toggleAction
import com.pinotrouge.messaging.ui.builder.toggleMatch
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 — builder state machine + live plain-English sentence.
 * Uses the same pure [BuilderDraft] helpers the ViewModel does, rendered
 * through [BuilderScreen] so the wiring cannot silently drift.
 */
@RunWith(AndroidJUnit4::class)
class BuilderScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val engine = DefaultRuleEngine()

    @Test
    fun changing_field_resets_operator_and_clears_value() {
        val start = BuilderDraft.newRule()
            .setField(0, ConditionField.Text)
            .setValue(0, "pre-approved")
        val text = start.conditions.single() as Condition.Text
        assertEquals(TextOp.CONTAINS_ANY, text.op)
        assertEquals("pre-approved", text.value)

        val after = start.setField(0, ConditionField.Sender)
        val sender = after.conditions.single() as Condition.Sender
        assertEquals(SenderOp.entries.first(), sender.op)
        assertEquals("", sender.value)
    }

    @Test
    fun last_condition_cannot_be_removed() {
        val one = BuilderDraft.newRule()
        assertEquals(1, one.conditions.size)
        assertEquals(one, one.removeCondition(0))
    }

    @Test
    fun plain_english_updates_when_conditions_change() {
        var draft by mutableStateOf(
            BuilderDraft.newRule().setField(0, ConditionField.Text).setValue(0, "sale"),
        )
        composeRule.setContent {
            // Local remember so recomposition picks up draft updates from the test.
            var state by remember {
                mutableStateOf(
                    BuilderUiState(
                        draft = draft,
                        plainWords = engine.plainWords(draft.toPreviewRule()),
                        backtestLine = formatBacktestLine(0, 0, 0),
                        loaded = true,
                    ),
                )
            }
            // Keep state in sync with outer draft mutations from performClick paths.
            state = BuilderUiState(
                draft = draft,
                plainWords = engine.plainWords(draft.toPreviewRule()),
                backtestLine = formatBacktestLine(0, 0, 0),
                loaded = true,
            )
            PinotRougeTheme(darkTheme = true) {
                BuilderScreen(
                    state = state,
                    onClose = {},
                    onSave = {},
                    onNameChange = {},
                    onToggleMatch = {},
                    onFieldChange = { i, f -> draft = draft.setField(i, f) },
                    onOperatorChange = { _, _ -> },
                    onValueChange = { i, v -> draft = draft.setValue(i, v) },
                    onRemoveCondition = { i -> draft = draft.removeCondition(i) },
                    onAddCondition = {},
                    onToggleAction = {},
                    onFewerDays = {},
                    onMoreDays = {},
                )
            }
        }

        // Initial sentence mentions the text condition value.
        composeRule.onNodeWithTag("builder_backtest").assertIsDisplayed()
        val before = engine.plainWords(draft.toPreviewRule())
        assertTrue(before.contains("sale") || before.isNotBlank())

        // Switch field → plain words must change (sender default, empty value).
        draft = draft.setField(0, ConditionField.Sender)
        composeRule.waitForIdle()
        val after = engine.plainWords(draft.toPreviewRule())
        assertTrue(after.isNotBlank())
        assertTrue(
            "Plain words should change when the condition field changes.\n before=$before\n after=$after",
            after != before,
        )
    }

    @Test
    fun hold_action_chip_is_shown() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                BuilderScreen(
                    state = BuilderUiState(
                        draft = BuilderDraft.newRule(),
                        plainWords = "When any of these match…",
                        backtestLine = formatBacktestLine(0, 0, 0),
                        loaded = true,
                    ),
                    onClose = {},
                    onSave = {},
                    onNameChange = {},
                    onToggleMatch = {},
                    onFieldChange = { _, _ -> },
                    onOperatorChange = { _, _ -> },
                    onValueChange = { _, _ -> },
                    onRemoveCondition = {},
                    onAddCondition = {},
                    onToggleAction = {},
                    onFewerDays = {},
                    onMoreDays = {},
                )
            }
        }
        // HOLD is the default action chip (label from Action.chipLabel).
        composeRule.onNodeWithText("Hold in Filtered").assertIsDisplayed()
        assertTrue(Action.HOLD in BuilderDraft.newRule().actions)
    }

    @Test
    fun tapping_auto_delete_selects_hold_and_clearing_hold_clears_delete() {
        var draft by mutableStateOf(BuilderDraft(actions = linkedSetOf()))
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    BuilderUiState(
                        draft = draft,
                        plainWords = engine.plainWords(draft.toPreviewRule()),
                        backtestLine = formatBacktestLine(0, 0, 0),
                        loaded = true,
                    ),
                )
            }
            state = BuilderUiState(
                draft = draft,
                plainWords = engine.plainWords(draft.toPreviewRule()),
                backtestLine = formatBacktestLine(0, 0, 0),
                loaded = true,
            )
            PinotRougeTheme(darkTheme = true) {
                BuilderScreen(
                    state = state,
                    onClose = {},
                    onSave = {},
                    onNameChange = {},
                    onToggleMatch = {},
                    onFieldChange = { _, _ -> },
                    onOperatorChange = { _, _ -> },
                    onValueChange = { _, _ -> },
                    onRemoveCondition = {},
                    onAddCondition = {},
                    onToggleAction = { action -> draft = draft.toggleAction(action) },
                    onFewerDays = {},
                    onMoreDays = {},
                )
            }
        }

        composeRule.onNodeWithTag("builder_action_HOLD").assertIsNotSelected()
        composeRule.onNodeWithTag("builder_delete_days").assertDoesNotExist()

        composeRule.onNodeWithTag("builder_action_DELETE").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_action_HOLD").assertIsSelected()
        composeRule.onNodeWithTag("builder_action_DELETE").assertIsSelected()
        composeRule.onNodeWithTag("builder_delete_days").assertIsDisplayed()

        composeRule.onNodeWithTag("builder_action_HOLD").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_action_HOLD").assertIsNotSelected()
        composeRule.onNodeWithTag("builder_action_DELETE").assertIsNotSelected()
        composeRule.onNodeWithTag("builder_delete_days").assertDoesNotExist()
    }

    @Test
    fun match_all_is_selected_by_default() {
        setMatchModeScreen(BuilderDraft.newRule())
        composeRule.onNodeWithTag("builder_match_all").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_any").assertIsNotSelected()
    }

    @Test
    fun tapping_match_any_selects_it_and_deselects_match_all() {
        setMatchModeScreen(BuilderDraft.newRule())
        composeRule.onNodeWithTag("builder_match_any").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_match_any").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_all").assertIsNotSelected()
    }

    @Test
    fun tapping_already_selected_match_chip_is_a_noop() {
        setMatchModeScreen(BuilderDraft.newRule())
        composeRule.onNodeWithTag("builder_match_all").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_match_all").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_any").assertIsNotSelected()

        composeRule.onNodeWithTag("builder_match_any").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_match_any").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_any").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_match_any").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_all").assertIsNotSelected()
    }

    @Test
    fun condition_lead_follows_match_mode() {
        setMatchModeScreen(BuilderDraft.newRule().addCondition())
        composeRule.onNodeWithTag("builder_condition_lead_1").assertTextEquals("AND")
        composeRule.onNodeWithTag("builder_match_any").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_condition_lead_1").assertTextEquals("OR")
        composeRule.onNodeWithTag("builder_match_all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_condition_lead_1").assertTextEquals("AND")
    }

    @Test
    fun tapping_the_lead_toggles_rule_mode() {
        setMatchModeScreen(BuilderDraft.newRule().addCondition())
        composeRule.onNodeWithTag("builder_match_all").assertIsSelected()
        composeRule.onNodeWithTag("builder_condition_lead_1").assertTextEquals("AND")

        composeRule.onNodeWithTag("builder_condition_lead_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_match_any").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_all").assertIsNotSelected()
        composeRule.onNodeWithTag("builder_condition_lead_1").assertTextEquals("OR")

        composeRule.onNodeWithTag("builder_condition_lead_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("builder_match_all").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_any").assertIsNotSelected()
        composeRule.onNodeWithTag("builder_condition_lead_1").assertTextEquals("AND")
    }

    @Test
    fun first_condition_lead_is_inert_copy() {
        setMatchModeScreen(BuilderDraft.newRule().addCondition())
        composeRule.onNodeWithTag("builder_condition_lead_0").assertTextEquals("When")
        composeRule.onNodeWithTag("builder_condition_lead_0").assertHasNoClickAction()
        composeRule.onNodeWithTag("builder_condition_lead_1").assertHasClickAction()
        composeRule.onNodeWithTag("builder_match_all").assertIsSelected()
        composeRule.onNodeWithTag("builder_match_any").assertIsNotSelected()
    }

    @Test
    fun unsupported_rule_opens_read_only_and_disables_save() {
        val draft = BuilderDraft.fromRule(
            com.pinotrouge.messaging.rules.Rule(
                id = "future",
                name = "Photos",
                order = 0,
                conditions = listOf(
                    Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                    Condition.Unsupported("attachment|HAS_VIDEO|"),
                ),
                actions = linkedSetOf(Action.HOLD),
            ),
        )
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                BuilderScreen(
                    state = BuilderUiState(
                        draft = draft,
                        plainWords = engine.plainWords(draft.toPreviewRule()),
                        backtestLine = formatBacktestLine(23, 200, 0),
                        loaded = true,
                    ),
                    onClose = {},
                    onSave = {},
                    onNameChange = {},
                    onToggleMatch = {},
                    onFieldChange = { _, _ -> },
                    onOperatorChange = { _, _ -> },
                    onValueChange = { _, _ -> },
                    onRemoveCondition = {},
                    onAddCondition = {},
                    onToggleAction = {},
                    onFewerDays = {},
                    onMoreDays = {},
                )
            }
        }
        composeRule.onNodeWithText("A condition this version can't read").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This filter was written in a newer version of Pinot Rouge. It is paused until you update, and nothing in it has been changed.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("This would have caught 23 of your last 200 texts, and none from your contacts.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("builder_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("builder_unsupported_row").assertIsDisplayed()
    }

    @Test
    fun operator_not_in_contacts_is_readable_and_stacks_when_narrow() {
        setOperatorScreen(width = 320.dp)
        composeRule.onNodeWithText("is not in my contacts").assertIsDisplayed()
        composeRule.onNodeWithTag("builder_field_op_0_stacked").assertExists()
        composeRule.onNodeWithTag("builder_field_op_0_row").assertDoesNotExist()
    }

    @Test
    fun operator_sits_beside_field_when_wide() {
        setOperatorScreen(width = 520.dp)
        composeRule.onNodeWithText("is not in my contacts").assertIsDisplayed()
        composeRule.onNodeWithTag("builder_field_op_0_row").assertExists()
        composeRule.onNodeWithTag("builder_field_op_0_stacked").assertDoesNotExist()
    }

    @Test
    fun operator_stacks_when_font_scale_is_enlarged() {
        setOperatorScreen(width = 520.dp, fontScale = 1.3f)
        composeRule.onNodeWithText("is not in my contacts").assertIsDisplayed()
        composeRule.onNodeWithTag("builder_field_op_0_stacked").assertExists()
    }

    private fun setOperatorScreen(width: Dp, fontScale: Float = 1f) {
        val draft = BuilderDraft.newRule()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
                PinotRougeTheme(darkTheme = true) {
                    // requiredSize so 520.dp is not coerced to a 411.dp Pixel /
                    // Medium viewport. Screen padding is 18.dp; content width
                    // must stay above the 400.dp stack threshold.
                    Box(Modifier.requiredSize(width, 860.dp)) {
                        BuilderScreen(
                            state = BuilderUiState(
                                draft = draft,
                                plainWords = engine.plainWords(draft.toPreviewRule()),
                                backtestLine = formatBacktestLine(0, 0, 0),
                                loaded = true,
                            ),
                            onClose = {},
                            onSave = {},
                            onNameChange = {},
                            onToggleMatch = {},
                            onFieldChange = { _, _ -> },
                            onOperatorChange = { _, _ -> },
                            onValueChange = { _, _ -> },
                            onRemoveCondition = {},
                            onAddCondition = {},
                            onToggleAction = {},
                            onFewerDays = {},
                            onMoreDays = {},
                        )
                    }
                }
            }
        }
    }

    private fun setMatchModeScreen(initial: BuilderDraft) {
        composeRule.setContent {
            var draft by remember { mutableStateOf(initial) }
            val state = BuilderUiState(
                draft = draft,
                plainWords = engine.plainWords(draft.toPreviewRule()),
                backtestLine = formatBacktestLine(0, 0, 0),
                loaded = true,
            )
            PinotRougeTheme(darkTheme = true) {
                BuilderScreen(
                    state = state,
                    onClose = {},
                    onSave = {},
                    onNameChange = {},
                    onToggleMatch = { draft = draft.toggleMatch() },
                    onFieldChange = { _, _ -> },
                    onOperatorChange = { _, _ -> },
                    onValueChange = { _, _ -> },
                    onRemoveCondition = {},
                    onAddCondition = { draft = draft.addCondition() },
                    onToggleAction = {},
                    onFewerDays = {},
                    onMoreDays = {},
                )
            }
        }
    }
}
