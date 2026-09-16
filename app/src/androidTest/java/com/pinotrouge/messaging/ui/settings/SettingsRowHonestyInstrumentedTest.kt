package com.pinotrouge.messaging.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.data.prefs.AppSettings
import com.pinotrouge.messaging.ui.onboarding.OnboardingContent
import com.pinotrouge.messaging.ui.onboarding.OnboardingUiState
import com.pinotrouge.messaging.ui.onboarding.StarterPackKey
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave 15 B3 — Settings / onboarding toggles are one merged switch node
 * with state; the dead "Send read receipts" row is gone.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRowHonestyInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings_toggle_rows_are_one_switch_node_with_state() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = false) {
                SettingsScreen(
                    state = SettingsUiState(
                        settings = AppSettings(darkTheme = false),
                        isDefaultSmsApp = true,
                    ),
                    onToggleHeld = {},
                    onToggleContacts = {},
                    onToggleOtp = {},
                    onToggleDark = {},
                    onRequestRole = {},
                )
            }
        }

        val expected = listOf(
            "Download pictures automatically" to true,
            "Download while roaming" to false,
            "Weekly digest of held messages" to true,
            "Never filter my contacts" to true,
            "Show copy button for codes" to true,
            "Dark theme" to false,
        )
        expected.forEach { (name, on) ->
            composeRule.onAllNodesWithText(name).assertCountEquals(1)
            val node = composeRule.onNodeWithText(name)
            node.assert(hasRoleSwitch())
            node.assert(hasClickAction())
            if (on) node.assertIsOn() else node.assertIsOff()
        }

        // Six remaining toggles, not inner switch node per row.
        composeRule.onAllNodes(hasRoleSwitch(), useUnmergedTree = true)
            .assertCountEquals(expected.size)
        composeRule.onAllNodes(hasRoleSwitch())
            .assertCountEquals(expected.size)
    }

    @Test
    fun settings_toggle_rows_flip_from_label_and_from_switch() {
        var settings by mutableStateOf(AppSettings(neverFilterContacts = true))
        composeRule.setContent {
            PinotRougeTheme(darkTheme = false) {
                SettingsScreen(
                    state = SettingsUiState(
                        settings = settings,
                        isDefaultSmsApp = true,
                    ),
                    onToggleHeld = { settings = settings.copy(holdByDefault = it) },
                    onToggleContacts = {
                        settings = settings.copy(neverFilterContacts = it)
                    },
                    onToggleOtp = { settings = settings.copy(preserveOtps = it) },
                    onToggleDark = { settings = settings.copy(darkTheme = it) },
                    onRequestRole = {},
                )
            }
        }

        val name = "Never filter my contacts"
        composeRule.onNodeWithText(name).performScrollTo()
        composeRule.onNodeWithText(name).assertIsOn()

        composeRule.onNodeWithText(name).performTouchInput {
            val pos = percentOffset(x = 0.12f, y = 0.5f)
            down(pos)
            up()
        }
        composeRule.waitForIdle()
        assertFalse(settings.neverFilterContacts)
        composeRule.onNodeWithText(name).assertIsOff()

        composeRule.onNodeWithText(name).performTouchInput {
            val pos = percentOffset(x = 0.94f, y = 0.5f)
            down(pos)
            up()
        }
        composeRule.waitForIdle()
        assertTrue(settings.neverFilterContacts)
        composeRule.onNodeWithText(name).assertIsOn()
    }

    @Test
    fun send_read_receipts_row_is_gone() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = false) {
                SettingsScreen(
                    state = SettingsUiState(
                        settings = AppSettings(),
                        isDefaultSmsApp = true,
                    ),
                    onToggleHeld = {},
                    onToggleContacts = {},
                    onToggleOtp = {},
                    onToggleDark = {},
                    onRequestRole = {},
                )
            }
        }
        composeRule.onNodeWithText("Send read receipts").assertDoesNotExist()
        composeRule.onNodeWithText("Let people see when you have read a message")
            .assertDoesNotExist()
    }

    @Test
    fun replay_first_run_setup_row_is_gone() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = false) {
                SettingsScreen(
                    state = SettingsUiState(
                        settings = AppSettings(),
                        isDefaultSmsApp = true,
                    ),
                    onToggleHeld = {},
                    onToggleContacts = {},
                    onToggleOtp = {},
                    onToggleDark = {},
                    onRequestRole = {},
                )
            }
        }
        composeRule.onNodeWithText("Replay first-run setup").assertDoesNotExist()
    }

    @Test
    fun onboarding_pack_rows_are_one_switch_node_with_state() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = false) {
                OnboardingContent(
                    state = OnboardingUiState(step = 3),
                    onGetStarted = {},
                    onContinueRole = {},
                    onNotNow = {},
                    onImportContinue = {},
                    onTogglePack = {},
                    onFinish = {},
                    onPickPinot = {},
                    onPickMessages = {},
                    onCancelRole = {},
                    onConfirmRole = {},
                )
            }
        }

        val expected = listOf(
            "Links from strangers" to true,
            "Promotions and sales" to true,
            "Loans and crypto" to true,
            "Quiet hours" to false,
        )
        expected.forEach { (name, on) ->
            composeRule.onAllNodesWithText(name).assertCountEquals(1)
            val node = composeRule.onNodeWithText(name)
            node.assert(hasRoleSwitch())
            node.assert(hasClickAction())
            if (on) node.assertIsOn() else node.assertIsOff()
        }

        composeRule.onAllNodes(hasRoleSwitch(), useUnmergedTree = true)
            .assertCountEquals(expected.size)
        composeRule.onAllNodes(hasRoleSwitch())
            .assertCountEquals(expected.size)
        assertEquals(4, StarterPackKey.entries.size)
    }

    private fun hasRoleSwitch(): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
}
