package com.pinotrouge.messaging.ui.rules

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.ui.builder.saveToastMessage
import com.pinotrouge.messaging.ui.components.FilterSaveToastSession
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for `fix/save-toast-unreachable`.
 *
 * The Filters list hosts the save confirmation. Without that host (or without
 * the session carrying the message across navigation), the toast never appears.
 */
@RunWith(AndroidJUnit4::class)
class FilterSaveToastInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun session_holds_message_and_rules_list_renders_it() {
        val session = FilterSaveToastSession()
        val text = saveToastMessage("Loan and crypto offers")
        session.show(text)
        assertEquals(text, session.message.value)

        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                RulesListScreen(
                    state = RulesListUiState(toastMessage = session.message.value),
                    onNewFilter = {},
                    onEditFilter = {},
                    onToggle = { _, _ -> },
                    onDismissToast = { session.clear() },
                )
            }
        }
        composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()

        composeRule.runOnIdle { session.clear() }
        assertNull(session.message.value)
    }
}
