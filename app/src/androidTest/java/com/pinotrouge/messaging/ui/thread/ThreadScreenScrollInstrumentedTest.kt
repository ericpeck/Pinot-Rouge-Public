package com.pinotrouge.messaging.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finding 10: a new incoming while reading history must not yank the list
 * to the bottom. Own send and the "New texts" chip still jump down.
 */
@RunWith(AndroidJUnit4::class)
class ThreadScreenScrollInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialLoad_showsLatestMessage() {
        setThread(bubbles(COUNT))
        composeRule.onNodeWithText("msg-${COUNT - 1}").assertIsDisplayed()
        composeRule.onNodeWithTag("thread_new_texts").assertDoesNotExist()
    }

    @Test
    fun incomingWhileReading_keepsPlace_andShowsNewTextsChip() {
        var messages by mutableStateOf(bubbles(COUNT))
        setThread { messages }
        scrollToOldest()
        composeRule.onNodeWithText("msg-0").assertIsDisplayed()

        messages = messages + ThreadBubble(
            ref = MessageRef.sms(COUNT.toLong()),
            body = "incoming-new",
            isOutgoing = false,
            date = COUNT.toLong(),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("msg-0").assertIsDisplayed()
        composeRule.onNodeWithTag("thread_new_texts").assertIsDisplayed()
        composeRule.onNodeWithText("New texts").assertIsDisplayed()
        composeRule.onNodeWithText("incoming-new").assertDoesNotExist()
    }

    @Test
    fun newTextsChip_jumpsToLatest() {
        var messages by mutableStateOf(bubbles(COUNT))
        setThread { messages }
        scrollToOldest()
        messages = messages + ThreadBubble(
            ref = MessageRef.sms(COUNT.toLong()),
            body = "incoming-new",
            isOutgoing = false,
            date = COUNT.toLong(),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("thread_new_texts").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("incoming-new").assertIsDisplayed()
        composeRule.onNodeWithTag("thread_new_texts").assertDoesNotExist()
    }

    @Test
    fun ownSend_scrollsToLatest_withoutChip() {
        var messages by mutableStateOf(bubbles(COUNT))
        setThread { messages }
        scrollToOldest()
        composeRule.onNodeWithText("msg-0").assertIsDisplayed()

        messages = messages + ThreadBubble(
            ref = MessageRef.sms(COUNT.toLong()),
            body = "own-send",
            isOutgoing = true,
            date = COUNT.toLong(),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("own-send").assertIsDisplayed()
        composeRule.onNodeWithTag("thread_new_texts").assertDoesNotExist()
    }

    private fun setThread(messages: List<ThreadBubble>) {
        setThread { messages }
    }

    private fun setThread(messages: () -> List<ThreadBubble>) {
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ThreadScreen(
                        state = ThreadUiState(
                            threadId = 1,
                            title = "Scroll",
                            address = "+15555550100",
                            messages = messages(),
                            roleHeld = true,
                            loading = false,
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                    )
                }
            }
        }
    }

    private fun scrollToOldest() {
        repeat(16) {
            val visible = composeRule.onAllNodesWithText("msg-0").fetchSemanticsNodes()
            if (visible.isNotEmpty()) return
            composeRule.onNodeWithTag("thread_message_list").performTouchInput { swipeDown() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("msg-0").assertIsDisplayed()
    }

    companion object {
        private const val COUNT = 24

        private fun bubbles(count: Int): List<ThreadBubble> =
            (0 until count).map { i ->
                ThreadBubble(
                    ref = MessageRef.sms(i.toLong()),
                    body = "msg-$i",
                    isOutgoing = false,
                    date = i.toLong(),
                )
            }
    }
}
