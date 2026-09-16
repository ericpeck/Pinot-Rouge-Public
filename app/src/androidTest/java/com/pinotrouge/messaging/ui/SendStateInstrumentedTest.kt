package com.pinotrouge.messaging.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import com.pinotrouge.messaging.sms.MmsSendEvent
import com.pinotrouge.messaging.sms.MmsSendVerdict
import com.pinotrouge.messaging.sms.MmsSubmitResult
import com.pinotrouge.messaging.sms.SmsRoleManager
import com.pinotrouge.messaging.sms.SmsSender
import com.pinotrouge.messaging.sms.SmsSubmitResult
import com.pinotrouge.messaging.ui.compose.ComposeViewModel
import com.pinotrouge.messaging.ui.thread.ThreadViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inverted 2026-09-15 review probes 2 and 3: live role authorizes send,
 * unrelated MMS completion does not clear this draft, returned thread id is used.
 */
@RunWith(AndroidJUnit4::class)
class SendStateInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun grantingRole_enablesComposeSend() = runBlocking {
        val role = MutableStateFlow(false)
        val manager = mockk<SmsRoleManager>(relaxed = true)
        every { manager.isRoleHeld() } answers { role.value }
        every { manager.roleHeld } returns role
        val sender = mockk<SmsSender>(relaxed = true)
        every { sender.sendCompletions } returns MutableSharedFlow()
        coEvery { sender.send(any(), any()) } returns SmsSubmitResult.Submitted(
            Uri.parse("content://sms/1"),
        )
        val vm = withContext(Dispatchers.Main) {
            ComposeViewModel(context, sender, manager, SavedStateHandle())
        }
        val collectJob = launch(Dispatchers.Main) { vm.uiState.collect { } }
        try {
            role.value = true
            withTimeout(5_000) { vm.uiState.first { it.roleHeld } }
            withContext(Dispatchers.Main) {
                vm.onRecipientChange("15555550123")
                vm.onBodyChange("review only")
                vm.send()
            }
            withTimeout(5_000) { vm.uiState.first { !it.sending } }
            assertTrue(vm.uiState.value.roleHeld)
            assertNotEquals(ComposeViewModel.DISABLED_REASON, vm.uiState.value.sendError)
            coVerify(exactly = 1) { sender.send(any(), any()) }
        } finally {
            collectJob.cancel()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun unrelatedMmsCompletion_doesNotClearThisDraft_usesReturnedThreadId() = runBlocking {
        val role = mockk<SmsRoleManager>(relaxed = true)
        every { role.isRoleHeld() } returns true
        every { role.roleHeld } returns MutableStateFlow(true)
        val sms = mockk<SmsRepository>(relaxed = true)
        every { sms.observeSmsChanges() } returns flowOf(Unit)
        coEvery { sms.getThreadParticipantAddresses(any()) } returns listOf("15555550123")
        coEvery { sms.markThreadRead(any()) } returns SmsRepository.WriteResult.Success(null)
        val messages = mockk<MessageRepository>(relaxed = true)
        coEvery { messages.getMessages(any(), any()) } returns emptyList()
        val contacts = mockk<ContactsRepository>(relaxed = true)
        coEvery { contacts.resolveDisplayName(any()) } returns null
        val pipeline = mockk<IncomingMessagePipeline>(relaxed = true)
        every { pipeline.downloadFailures } returns MutableSharedFlow()
        val events = MutableSharedFlow<MmsSendEvent>()
        val sender = mockk<SmsSender>(relaxed = true)
        every { sender.sendCompletions } returns events
        val pendingUri = Uri.parse("content://mms/1")
        coEvery { sender.sendMms(any(), any(), any(), any()) } returns MmsSubmitResult(
            threadId = 999L,
            messageUri = pendingUri,
        )
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vm = withContext(Dispatchers.Main) {
            ThreadViewModel(
                SavedStateHandle(mapOf("threadId" to "123")),
                messages,
                contacts,
                sms,
                mockk(relaxed = true),
                mockk(relaxed = true),
                pipeline,
                sender,
                role,
                mockk<NotificationHelper>(relaxed = true),
                mockk(relaxed = true),
                context,
                appScope,
            )
        }
        val collectJob = launch(Dispatchers.Main) { vm.uiState.collect { } }
        try {
            withTimeout(5_000) { vm.uiState.first { !it.loading && it.sendAddresses.isNotEmpty() } }
            withContext(Dispatchers.Main) { vm.addParticipant("15555550456") }
            withTimeout(5_000) { vm.uiState.first { it.isGroup } }
            withContext(Dispatchers.Main) {
                vm.onDraftChange("Pending in this thread")
                vm.send()
            }
            withTimeout(5_000) {
                vm.uiState.first { it.sending && it.threadId == 999L }
            }
            withTimeout(5_000) { events.subscriptionCount.first { it > 0 } }
            events.emit(MmsSendEvent(Uri.parse("content://mms/888888"), MmsSendVerdict.Sent))
            delay(200)
            assertTrue("Unrelated completion must not finish this send", vm.uiState.value.sending)
            assertEquals("Pending in this thread", vm.uiState.value.draft)
            assertEquals(999L, vm.uiState.value.threadId)
            events.emit(MmsSendEvent(pendingUri, MmsSendVerdict.Sent))
            withTimeout(5_000) { vm.uiState.first { !it.sending } }
            assertEquals("", vm.uiState.value.draft)
        } finally {
            collectJob.cancel()
            vm.viewModelScope.cancel()
            appScope.cancel()
        }
    }
}
