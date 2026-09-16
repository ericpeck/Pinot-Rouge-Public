package com.pinotrouge.messaging.ui.thread

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.data.telephony.MmsPart
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.di.ApplicationScope
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import com.pinotrouge.messaging.sms.SmsRoleManager
import com.pinotrouge.messaging.sms.SmsSender
import com.pinotrouge.messaging.ui.components.ConversationDeleteSession
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.grantSmsRoleTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opening a thread used to hold the header and every text bubble hostage
 * until every MMS part had been read. These tests pin the staged refresh:
 * title and SMS first, tiles per message, cache on the next refresh.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ThreadOpenStagedRefreshInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

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
    private val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    @Before
    fun setUp() {
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(smsRepository, held = true)
        assumeTrue("ROLE_SMS required to seed the thread", smsRepository.isDefaultSmsApp())
        contactsRepository.clearCache()
        contactsRepository.queryOverride = { true to CONTACT_NAME }
        ThreadViewModel.resetPartsReadGate()
        gates.clear()
    }

    @After
    fun tearDown() {
        gates.values.forEach { it.complete(Unit) }
        ThreadViewModel.resetPartsReadGate()
        contactsRepository.queryOverride = null
        contactsRepository.clearCache()
        inserted.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test
    fun titleAndSmsText_visibleWhilePartsStillLatched() {
        val address = uniqueAddress("01")
        val smsBody = "sms-before-tiles-${System.nanoTime()}"
        val (threadId, mmsId) = runBlocking {
            insertSms(address, smsBody)
            val mmsId = insertPhotoMms(address, caption = "caption-blocked")
            mmsRepository.threadIdFor(listOf(address)) to mmsId
        }
        installBlockingGate()
        val vm = viewModel(threadId)
        setThreadContent(vm)

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(CONTACT_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(CONTACT_NAME).assertIsDisplayed()
        assertEquals(
            "title must not wait on getParts",
            0,
            ThreadViewModel.partsReadCount.get(),
        )

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(smsBody).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(smsBody).assertIsDisplayed()
        assertEquals(
            "SMS body comes from getMessages; getParts must still be parked",
            0,
            ThreadViewModel.partsReadCount.get(),
        )
        composeRule.onNode(photoTileMatcher(mmsId)).assertDoesNotExist()
        composeRule.onNodeWithText("caption-blocked").assertDoesNotExist()
    }

    @Test
    fun firstTileAppears_whileSecondStillPending() {
        val address = uniqueAddress("02")
        val (threadId, idA, idB) = runBlocking {
            val a = insertPhotoMms(address, caption = "caption-a")
            val b = insertPhotoMms(address, caption = "caption-b")
            Triple(mmsRepository.threadIdFor(listOf(address)), a, b)
        }
        installBlockingGate()
        val vm = viewModel(threadId)
        setThreadContent(vm)

        awaitGates(2)
        assertEquals("both MMS must be parked together, not serial", 2, gates.size)
        assertEquals(0, ThreadViewModel.partsReadCount.get())

        gates.getValue(idA).complete(Unit)
        awaitPhoto(vm, idA)
        assertFalse(hasPhoto(vm.uiState.value, idB))
        assertEquals(1, ThreadViewModel.partsReadCount.get())
        composeRule.onNode(photoTileMatcher(idA)).assertIsDisplayed()
        composeRule.onNode(photoTileMatcher(idB)).assertDoesNotExist()

        gates.getValue(idB).complete(Unit)
        awaitPhoto(vm, idB)
        assertEquals(2, ThreadViewModel.partsReadCount.get())
        composeRule.onNode(photoTileMatcher(idB)).assertIsDisplayed()
    }

    @Test
    fun secondRefresh_doesNotRereadCachedParts() = runBlocking {
        val address = uniqueAddress("03")
        val mmsId = insertPhotoMms(address, caption = "cached-caption")
        val threadId = mmsRepository.threadIdFor(listOf(address))
        val vm = viewModel(threadId)
        withTimeout(TIMEOUT_MS) {
            vm.uiState.first { state -> hasPhoto(state, mmsId) }
        }
        val before = ThreadViewModel.partsReadCount.get()
        assertTrue("first open must have read parts", before > 0)
        vm.refresh(announceLoading = false)
        withTimeout(TIMEOUT_MS) {
            vm.uiState.first { state -> hasPhoto(state, mmsId) }
        }
        // Give a cache miss time to increment if the skip is broken.
        repeat(40) {
            kotlinx.coroutines.delay(50)
            assertEquals(
                "cached visuals must not call getParts again",
                before,
                ThreadViewModel.partsReadCount.get(),
            )
        }
    }

    @Test
    fun fullyLoaded_smsCaptionAndPhoto_sameAsToday() {
        val address = uniqueAddress("04")
        val smsBody = "regression-sms-${System.nanoTime()}"
        val caption = "regression-caption"
        val (threadId, mmsId) = runBlocking {
            insertSms(address, smsBody, dateMillis = System.currentTimeMillis() - 2_000L)
            val mmsId = insertPhotoMms(address, caption = caption)
            mmsRepository.threadIdFor(listOf(address)) to mmsId
        }
        val vm = viewModel(threadId)
        setThreadContent(vm)
        awaitPhoto(vm, mmsId)
        val state = vm.uiState.value
        val sms = state.messages.single { it.ref.kind == MessageRef.Kind.SMS }
        val mms = state.messages.single { it.ref.kind == MessageRef.Kind.MMS }
        assertEquals(CONTACT_NAME, state.title)
        assertEquals(smsBody, sms.body)
        assertEquals(caption, mms.body)
        assertEquals(1, mms.tiles.size)
        assertEquals(MmsTileKind.Photo, mms.tiles.single().kind)
        assertTrue(!mms.showPhotoPlaceholder)
        assertTrue("SMS then MMS in date order", sms.date <= mms.date)
        composeRule.onNodeWithText(CONTACT_NAME).assertIsDisplayed()
        composeRule.onNodeWithText(smsBody).assertIsDisplayed()
        composeRule.onNodeWithText(caption).assertIsDisplayed()
        composeRule.onNode(photoTileMatcher(mmsId)).assertIsDisplayed()
        composeRule.onNodeWithTag("mms-placeholder-mms:$mmsId").assertDoesNotExist()
    }

    @Test
    fun timeToFirstNonBlank_twentyPhotoMessages() = runBlocking {
        val address = uniqueAddress("20")
        repeat(PHOTO_COUNT) { i ->
            insertPhotoMms(address, caption = "cap-$i")
        }
        val threadId = mmsRepository.threadIdFor(listOf(address))
        val t0 = System.nanoTime()
        val vm = viewModel(threadId)
        val collectJob = launch(Dispatchers.Main.immediate) { vm.uiState.collect { } }
        try {
            withTimeout(TIMEOUT_MS) {
                vm.uiState.first { it.title == CONTACT_NAME }
            }
            val titleMs = (System.nanoTime() - t0) / 1_000_000L
            withTimeout(TILES_TIMEOUT_MS) {
                vm.uiState.first { state ->
                    val mms = state.messages.filter { it.ref.kind == MessageRef.Kind.MMS }
                    mms.size >= PHOTO_COUNT &&
                        mms.all { b -> b.tiles.any { it.kind == MmsTileKind.Photo } }
                }
            }
            val tilesMs = (System.nanoTime() - t0) / 1_000_000L
            val line =
                "thread-open after: title_ms=$titleMs tiles_ms=$tilesMs count=$PHOTO_COUNT"
            Log.i(PERF_TAG, line)
            println(line)
            assertTrue("title must appear before every tile is ready", titleMs <= tilesMs)
        } finally {
            collectJob.cancel()
        }
    }

    private fun setThreadContent(vm: ThreadViewModel) {
        composeRule.setContent {
            val state by vm.uiState.collectAsState()
            PinotRougeTheme {
                Box(Modifier.fillMaxSize()) {
                    ThreadScreen(
                        state = state,
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                    )
                }
            }
        }
    }

    private fun viewModel(threadId: Long): ThreadViewModel {
        require(threadId != 0L) { "threadId was 0" }
        return ThreadViewModel(
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
    }

    private fun photoTileMatcher(mmsId: Long): SemanticsMatcher =
        SemanticsMatcher("photo tile for $mmsId") { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" }
                .startsWith("mms-tile-photo-$mmsId-")
        }

    private fun hasPhoto(state: ThreadUiState, mmsId: Long): Boolean =
        state.messages.any { b ->
            b.ref.kind == MessageRef.Kind.MMS &&
                b.ref.id == mmsId &&
                b.tiles.any { it.kind == MmsTileKind.Photo }
        }

    private fun awaitPhoto(vm: ThreadViewModel, mmsId: Long) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!hasPhoto(vm.uiState.value, mmsId) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
        }
        assertTrue("photo tiles for $mmsId never arrived", hasPhoto(vm.uiState.value, mmsId))
    }

    private fun awaitGates(count: Int) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (gates.size < count && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
        }
        assertEquals("expected $count parked getParts calls", count, gates.size)
    }

    private fun installBlockingGate() {
        ThreadViewModel.partsReadCount.set(0)
        ThreadViewModel.partsReadGate = { id ->
            gates.getOrPut(id) { CompletableDeferred() }.await()
        }
    }

    private suspend fun insertSms(
        address: String,
        body: String,
        dateMillis: Long = System.currentTimeMillis() - 5_000L,
    ) {
        val result = smsRepository.insertInbox(
            address = address,
            body = body,
            dateMillis = dateMillis,
            read = true,
        )
        assertTrue("SMS insert: $result", result is SmsRepository.WriteResult.Success)
        inserted += (result as SmsRepository.WriteResult.Success).uri!!
    }

    private suspend fun insertPhotoMms(address: String, caption: String): Long {
        val token = "${TOKEN.incrementAndGet()}-${System.nanoTime()}"
        val result = mmsRepository.insertInbox(
            IncomingMms(
                originator = address,
                body = caption,
                receivedAtMillis = System.currentTimeMillis(),
                hasPhoto = true,
                hasAnyPart = true,
                contentLocation = "http://mms.test/thread-open/$token",
                transactionId = "tx-$token",
                parts = listOf(MmsPart(contentType = "image/jpeg", bytes = JPEG)),
            ),
        )
        assertTrue("MMS insert: $result", result is SmsRepository.WriteResult.Success)
        val uri = (result as SmsRepository.WriteResult.Success).uri!!
        val canonical = ContentUris.withAppendedId(
            Telephony.Mms.CONTENT_URI,
            ContentUris.parseId(uri),
        )
        inserted += canonical
        return ContentUris.parseId(canonical)
    }

    private fun uniqueAddress(suffix: String): String {
        val tail = (System.nanoTime() % 10_000_000L).toString().padStart(7, '0')
        return "1555$suffix$tail".take(15)
    }

    companion object {
        private const val CONTACT_NAME = "Maya Chen"
        private const val PERF_TAG = "ThreadOpenPerf"
        private const val TIMEOUT_MS = 15_000L
        private const val TILES_TIMEOUT_MS = 30_000L
        private const val PHOTO_COUNT = 20
        private val TOKEN = AtomicInteger(0)
        private val JPEG: ByteArray = run {
            val bitmap = android.graphics.Bitmap.createBitmap(
                16,
                16,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
