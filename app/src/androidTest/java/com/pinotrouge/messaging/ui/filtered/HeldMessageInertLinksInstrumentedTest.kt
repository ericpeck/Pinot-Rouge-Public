package com.pinotrouge.messaging.ui.filtered

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Held-message copy promises links are not tappable. Body is plain [androidx.compose.material3.Text],
 * not a link annotation — assert no click action on a URL-bearing body.
 */
@RunWith(AndroidJUnit4::class)
class HeldMessageInertLinksInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun held_body_with_url_has_no_click_action() {
        val urlBody =
            "PRE-APPROVED for 5000 dollars. Visit usps-redelivery-status.co/9182 to claim."
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                HeldMessageScreen(
                    state = HeldMessageUiState(
                        message = HeldMessageDetailUi(
                            id = "h1",
                            sender = "18445550192",
                            body = urlBody,
                            timeLabel = "10:41",
                            ruleName = "Loan and crypto offers",
                            displayReason = null,
                        ),
                        loading = false,
                    ),
                    onMoveToInbox = {},
                    onBlockSender = {},
                    onDelete = {},
                    onDismissToast = {},
                )
            }
        }
        composeRule
            .onNodeWithText(urlBody, substring = false)
            .assertExists()
            .assertHasNoClickAction()
    }
}
