package com.pinotrouge.messaging.ui.thread

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.MmsSendComposer
import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.di.ApplicationScope
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import com.pinotrouge.messaging.sms.SmsRoleManager
import com.pinotrouge.messaging.sms.SmsSender
import com.pinotrouge.messaging.ui.components.ConversationDeleteSession
import com.pinotrouge.messaging.ui.media.MmsImageLadder
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.grantSmsRoleTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The finding as the user meets it: a sent photo is a tile in the thread,
 * not a caption-only (or empty) bubble.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SentPhotoTileInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var contactsRepository: ContactsRepository
    @Inject lateinit var smsRepository: SmsRepository
    @Inject lateinit var mmsRepository: MmsRepository
    @Inject lateinit var mmsTransport: MmsTransport
    @Inject lateinit var incomingMessagePipeline: IncomingMessagePipeline
    @Inject lateinit var smsSender: SmsSender
    @Inject lateinit var smsRoleManager: SmsRoleManager
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var conversationDeleteSession: ConversationDeleteSession

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private lateinit var context: Context
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        hiltRule.inject()
        ThreadViewModel.resetPartsReadGate()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(smsRepository, held = true)
        assumeTrue("ROLE_SMS required to write Mms.Sent", smsRepository.isDefaultSmsApp())
    }

    @After
    fun tearDown() {
        ThreadViewModel.resetPartsReadGate()
        inserted.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test
    fun sentPhoto_threadViewModel_hasPhotoTile_notPlaceholder() = runBlocking {
        val image = MmsSendComposer.OutboundImage(
            contentType = "image/jpeg",
            bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAA.toByte(), 0xBB.toByte()),
            location = MmsImageLadder.locationFor("image/jpeg"),
        )
        val result = mmsRepository.insertSent(
            recipients = listOf("15555550981"),
            body = "",
            image = image,
        )
        assertTrue("insertSent: $result", result is SmsRepository.WriteResult.Success)
        val raw = (result as SmsRepository.WriteResult.Success).uri!!
        val canonical = ContentUris.withAppendedId(
            Telephony.Mms.CONTENT_URI,
            ContentUris.parseId(raw),
        )
        inserted += canonical
        val threadId = mmsRepository.threadIdFor(listOf("15555550981"))
        assertTrue(threadId != 0L)

        val vm = ThreadViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(ThreadViewModel.ARG_THREAD_ID to threadId.toString()),
            ),
            messageRepository = messageRepository,
            contactsRepository = contactsRepository,
            smsRepository = smsRepository,
            mmsRepository = mmsRepository,
            mmsTransport = mmsTransport,
            incomingMessagePipeline = incomingMessagePipeline,
            smsSender = smsSender,
            smsRoleManager = smsRoleManager,
            notificationHelper = notificationHelper,
            conversationDeleteSession = conversationDeleteSession,
            context = context,
            applicationScope = applicationScope,
        )
        // Staged refresh emits the MMS bubble (placeholder, loading=false)
        // before tiles. Wait for the photo tile, not merely the bubble.
        val state = withTimeout(15_000) {
            vm.uiState.first {
                !it.loading &&
                    it.messages.any { b ->
                        b.ref.kind == MessageRef.Kind.MMS &&
                            b.tiles.any { tile -> tile.kind == MmsTileKind.Photo }
                    }
            }
        }
        val bubble = state.messages.single { it.ref.kind == MessageRef.Kind.MMS }
        assertEquals(1, bubble.tiles.size)
        assertEquals(MmsTileKind.Photo, bubble.tiles.single().kind)
        assertFalse(bubble.showPhotoPlaceholder)
        assertTrue(bubble.body.isBlank())
    }
}
