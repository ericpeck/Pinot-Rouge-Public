package com.pinotrouge.messaging.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mixed-thread identity in composition: colliding provider `_id`s must both
 * render, LazyColumn keys must be strings, and batch-select must hit the MMS.
 */
@RunWith(AndroidJUnit4::class)
class ThreadScreenIdentityInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun collidingIds_bothRender_andSelectingMmsDoesNotSelectSms() {
        val sms = ThreadBubble(
            ref = MessageRef.sms(42),
            body = "plain text 42",
            isOutgoing = false,
            date = 1,
        )
        val mms = ThreadBubble(
            ref = MessageRef.mms(42),
            body = "picture subject",
            isOutgoing = false,
            date = 2,
            subject = "picture subject",
            showPhotoPlaceholder = true,
        )
        var selected by mutableStateOf<Set<MessageRef>>(emptySet())
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = ThreadUiState(
                            threadId = 1,
                            title = "Identity",
                            address = "+15555550100",
                            messages = listOf(sms, mms),
                            roleHeld = true,
                            loading = false,
                            selectionActive = selected.isNotEmpty(),
                            selectedMessageIds = selected,
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                        onStartSelection = { selected = setOf(it) },
                        onToggleSelection = { ref ->
                            selected = if (ref in selected) selected - ref else selected + ref
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("thread-bubble-sms:42").assertIsDisplayed()
        composeRule.onNodeWithTag("thread-bubble-mms:42").assertIsDisplayed()
        composeRule.onNodeWithTag("mms-placeholder-mms:42", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("plain text 42").assertIsDisplayed()
        composeRule.onNodeWithText("picture subject").assertIsDisplayed()

        composeRule.onNodeWithTag("thread-bubble-mms:42").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(setOf(MessageRef.mms(42)), selected)
        assertEquals(false, MessageRef.sms(42) in selected)
    }
}
