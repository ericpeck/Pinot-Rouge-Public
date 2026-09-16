package com.pinotrouge.messaging.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.ui.inbox.InboxScreen
import com.pinotrouge.messaging.ui.inbox.InboxUiState
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 — preview-mode banner is driven by [InboxUiState.roleHeld].
 * Role not held → banner visible; role held → absent.
 */
@RunWith(AndroidJUnit4::class)
class InboxPreviewBannerInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun role_not_held_shows_filtering_banner() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                InboxScreen(
                    state = InboxUiState(
                        isLoading = false,
                        roleHeld = false,
                        canReadMessages = true,
                    ),
                    onOpenThread = {},
                    onSelectChip = {},
                    onOpenSearch = {},
                    onCopyOtp = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Filtering is not running").assertIsDisplayed()
        composeRule.onNodeWithText("Make it default").assertIsDisplayed()
    }

    @Test
    fun role_held_hides_filtering_banner() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                InboxScreen(
                    state = InboxUiState(
                        isLoading = false,
                        roleHeld = true,
                        canReadMessages = true,
                    ),
                    onOpenThread = {},
                    onSelectChip = {},
                    onOpenSearch = {},
                    onCopyOtp = { _, _ -> },
                )
            }
        }
        composeRule.onAllNodesWithText("Filtering is not running").assertCountEquals(0)
        composeRule.onAllNodesWithText("Make it default").assertCountEquals(0)
    }

    @Test
    fun no_read_permission_is_not_empty_inbox() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                InboxScreen(
                    state = InboxUiState(
                        isLoading = false,
                        roleHeld = false,
                        canReadMessages = false,
                    ),
                    onOpenThread = {},
                    onSelectChip = {},
                    onOpenSearch = {},
                    onCopyOtp = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Can't read your messages").assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").assertIsDisplayed()
        composeRule.onAllNodesWithText("No messages yet").assertCountEquals(0)
    }
}
