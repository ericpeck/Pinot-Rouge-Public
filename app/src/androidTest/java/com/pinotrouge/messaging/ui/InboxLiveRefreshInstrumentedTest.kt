package com.pinotrouge.messaging.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.ArchiveRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.notify.OtpClipboard
import com.pinotrouge.messaging.sms.AndroidSmsRoleManager
import com.pinotrouge.messaging.ui.components.ConversationDeleteSession
import com.pinotrouge.messaging.ui.inbox.InboxListBody
import com.pinotrouge.messaging.ui.inbox.InboxScreen
import com.pinotrouge.messaging.ui.inbox.InboxThreadUi
import com.pinotrouge.messaging.ui.inbox.InboxUiState
import com.pinotrouge.messaging.ui.inbox.InboxViewModel
import com.pinotrouge.messaging.ui.inbox.ThreadCategory
import com.pinotrouge.messaging.ui.inbox.resolveInboxListBody
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.createInMemoryDb
import com.pinotrouge.messaging.util.grantSmsRoleTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Incoming SMS must land in the Chats list without a resume / [InboxViewModel.refresh]
 * call. The tab badge is already observer-driven; the list was pull-on-resume
 * only — see Tasks/fix-inbox-list-live-refresh.
 *
 * Follow-on: a prepended thread must be visible when the user is already at the
 * top of a scrollable list, and must not yank them if they have scrolled down —
 * see Tasks/fix-new-thread-above-scroll-anchor.
 *
 * Follow-on: [InboxViewModel.refresh] on resume must not blank a populated
 * Chats list ([Threads, Loading, Threads]) — see Tasks/fix-inbox-resume-blank.
 */
@RunWith(AndroidJUnit4::class)
class InboxLiveRefreshInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var appScope: CoroutineScope

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    @Test
    fun chats_list_adds_thread_on_insert_without_refresh() = runBlocking {
        val sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue(
            "ROLE_SMS not held — live list ContentObserver test cannot run",
            sms.isDefaultSmsApp(),
        )

        val contacts = ContactsRepository(context)
        val messageRepository = MessageRepository(sms, contacts)
        val vm = InboxViewModel(
            messageRepository = messageRepository,
            contactsRepository = contacts,
            settingsRepository = SettingsRepository(context),
            quarantineRepository = QuarantineRepository(
                heldMessageDao = db.heldMessageDao(),
                blockedSenderDao = db.blockedSenderDao(),
                ruleDao = db.ruleDao(),
                smsRepository = sms,
                heldMediaDao = db.heldMediaDao(),
                mmsRepository = MmsRepository(context, sms),
                context = context,
            ),
            archiveRepository = ArchiveRepository(db.archivedThreadDao()),
            otpClipboard = OtpClipboard(context),
            smsRoleManager = AndroidSmsRoleManager(context),
            smsRepository = sms,
            conversationDeleteSession = ConversationDeleteSession(
                applicationScope = appScope,
                messageRepository = messageRepository,
            ),
            applicationScope = appScope,
        )

        val collectJob = launch(Dispatchers.Main.immediate) {
            vm.uiState.collect { /* keep WhileSubscribed active */ }
        }
        try {
            val initial = awaitState(vm) { !it.isLoading }
            val baselineSize = initial.threads.size
            val baselineUnread = initial.unreadCount

            val address = "1555${System.currentTimeMillis() % 1_000_000_000L}"
            val body = "inbox-live-refresh-${System.currentTimeMillis()}"
            val insert = sms.insertInbox(
                address = address,
                body = body,
                dateMillis = System.currentTimeMillis(),
                read = false,
            )
            assertTrue("insert failed: $insert", insert is SmsRepository.WriteResult.Success)
            val insertUri = (insert as SmsRepository.WriteResult.Success).uri
            assertNotNull(insertUri)

            val spinnerFlashed = AtomicBoolean(false)
            val spinnerWatch = launch(Dispatchers.Main.immediate) {
                vm.uiState.collect { state ->
                    if (state.isLoading) spinnerFlashed.set(true)
                }
            }
            try {
                val after = awaitState(vm) { state ->
                    state.threads.any { it.matchesInserted(address, body) }
                }
                assertTrue(
                    "inbox list missing thread $address after insert without refresh(); " +
                        "threads=${after.threads.map { it.address }}",
                    after.threads.any { it.matchesInserted(address, body) },
                )
                assertEquals(
                    "unread header must rise with the new thread " +
                        "(baseline $baselineUnread, list grew from $baselineSize)",
                    baselineUnread + 1,
                    after.unreadCount,
                )
                assertFalse(
                    "quiet reload must not flash the loading spinner",
                    spinnerFlashed.get(),
                )
            } finally {
                spinnerWatch.cancel()
                insertUri?.let { context.contentResolver.delete(it, null, null) }
            }
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun refresh_does_not_blank_populated_chats_list() = runBlocking {
        val sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue(
            "ROLE_SMS not held — resume-blank ContentObserver test cannot run",
            sms.isDefaultSmsApp(),
        )

        val contacts = ContactsRepository(context)
        val messageRepository = MessageRepository(sms, contacts)
        val vm = InboxViewModel(
            messageRepository = messageRepository,
            contactsRepository = contacts,
            settingsRepository = SettingsRepository(context),
            quarantineRepository = QuarantineRepository(
                heldMessageDao = db.heldMessageDao(),
                blockedSenderDao = db.blockedSenderDao(),
                ruleDao = db.ruleDao(),
                smsRepository = sms,
                heldMediaDao = db.heldMediaDao(),
                mmsRepository = MmsRepository(context, sms),
                context = context,
            ),
            archiveRepository = ArchiveRepository(db.archivedThreadDao()),
            otpClipboard = OtpClipboard(context),
            smsRoleManager = AndroidSmsRoleManager(context),
            smsRepository = sms,
            conversationDeleteSession = ConversationDeleteSession(
                applicationScope = appScope,
                messageRepository = messageRepository,
            ),
            applicationScope = appScope,
        )

        val collectJob = launch(Dispatchers.Main.immediate) {
            vm.uiState.collect { /* keep WhileSubscribed active */ }
        }
        var insertUri: android.net.Uri? = null
        try {
            val settled = awaitState(vm) { !it.isLoading }
            if (settled.threads.isEmpty()) {
                val address = "1555${System.currentTimeMillis() % 1_000_000_000L}"
                val body = "inbox-resume-blank-${System.currentTimeMillis()}"
                val insert = sms.insertInbox(
                    address = address,
                    body = body,
                    dateMillis = System.currentTimeMillis(),
                    read = false,
                )
                assertTrue("insert failed: $insert", insert is SmsRepository.WriteResult.Success)
                insertUri = (insert as SmsRepository.WriteResult.Success).uri
                val afterInsert = awaitState(vm) { state ->
                    !state.isLoading && state.threads.any { it.matchesInserted(address, body) }
                }
                assertTrue(
                    "need at least one thread on screen before refresh(); " +
                        "threads=${afterInsert.threads.map { it.address }}",
                    afterInsert.threads.isNotEmpty(),
                )
            }

            val bodies = CopyOnWriteArrayList<InboxListBody>()
            val loadingWhilePopulated = AtomicBoolean(false)
            val watch = launch(Dispatchers.Main.immediate) {
                vm.uiState.collect { state ->
                    bodies.add(listBodyOf(state))
                    if (state.isLoading && state.threads.isNotEmpty()) {
                        loadingWhilePopulated.set(true)
                    }
                }
            }
            try {
                vm.refresh(quiet = true)
                // Capture a loading flash if one happens. loadThreads() was
                // 40–72 ms with 9 rows; 2s is headroom, not the assertion.
                withTimeoutOrNull(2_000) {
                    while (!bodies.contains(InboxListBody.Loading) &&
                        !loadingWhilePopulated.get()
                    ) {
                        delay(10)
                    }
                    true
                }
                withTimeoutOrNull(8_000) {
                    awaitState(vm) { !it.isLoading && it.threads.isNotEmpty() }
                }
                assertFalse(
                    "refresh() must not blank a populated Chats list; " +
                        "body sequence=$bodies",
                    loadingWhilePopulated.get() || bodies.contains(InboxListBody.Loading),
                )
            } finally {
                watch.cancel()
            }
        } finally {
            insertUri?.let { context.contentResolver.delete(it, null, null) }
            collectJob.cancel()
        }
    }

    @Test
    fun new_thread_is_first_visible_when_already_at_top() {
        val listState = LazyListState()
        val seed = overflowThreads(SEED_COUNT)
        val threadsState = mutableStateOf(seed)
        val newThread = overflowThread(
            id = NEW_THREAD_ID,
            name = "New arrival",
            preview = "just landed above the anchor",
        )

        composeRule.setContent {
            val threads = threadsState.value
            PinotRougeTheme(darkTheme = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(OVERFLOW_VIEWPORT_DP.dp),
                ) {
                    InboxScreen(
                        state = inboxState(threads),
                        onOpenThread = {},
                        onSelectChip = {},
                        onCopyOtp = { _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                        listState = listState,
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertTrue(
            "list must overflow the viewport so this test can see the anchor bug; " +
                "visible=${listState.layoutInfo.visibleItemsInfo.size} " +
                "total=${listState.layoutInfo.totalItemsCount}",
            listState.canScrollForward,
        )
        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)

        composeRule.runOnIdle {
            threadsState.value = listOf(newThread) + threadsState.value
        }
        composeRule.waitForIdle()

        val topKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        assertEquals(
            "new thread should be first visible when already at top of a scrollable list; " +
                "top is $topKey instead",
            newThread.threadId,
            topKey,
        )
        assertEquals(0, listState.firstVisibleItemIndex)
    }

    @Test
    fun new_thread_does_not_yank_when_scrolled_down() {
        val listState = LazyListState()
        val seed = overflowThreads(SEED_COUNT)
        val threadsState = mutableStateOf(seed)
        val newThread = overflowThread(
            id = NEW_THREAD_ID,
            name = "New arrival",
            preview = "must not yank a scrolled list",
        )

        composeRule.setContent {
            val threads = threadsState.value
            PinotRougeTheme(darkTheme = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(OVERFLOW_VIEWPORT_DP.dp),
                ) {
                    InboxScreen(
                        state = inboxState(threads),
                        onOpenThread = {},
                        onSelectChip = {},
                        onCopyOtp = { _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                        listState = listState,
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertTrue(
            "list must overflow the viewport so this test can see a yank; " +
                "visible=${listState.layoutInfo.visibleItemsInfo.size} " +
                "total=${listState.layoutInfo.totalItemsCount}",
            listState.canScrollForward,
        )

        runBlocking(Dispatchers.Main) {
            listState.scrollToItem(SCROLLED_INDEX)
        }
        composeRule.waitForIdle()

        val beforeIndex = listState.firstVisibleItemIndex
        val beforeOffset = listState.firstVisibleItemScrollOffset
        val beforeKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        assertTrue(
            "precondition: must be scrolled away from the top before insert; " +
                "index=$beforeIndex key=$beforeKey",
            beforeIndex > 0,
        )

        composeRule.runOnIdle {
            threadsState.value = listOf(newThread) + threadsState.value
        }
        composeRule.waitForIdle()

        val afterKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        assertEquals(
            "scrolled position must not jump to the new thread; " +
                "was looking at $beforeKey",
            beforeKey,
            afterKey,
        )
        assertEquals(
            "firstVisibleItemIndex should shift by the prepend, not reset to 0",
            beforeIndex + 1,
            listState.firstVisibleItemIndex,
        )
        assertEquals(beforeOffset, listState.firstVisibleItemScrollOffset)
        assertTrue(
            "must not yank a user who has scrolled down; first visible is $afterKey",
            afterKey != newThread.threadId,
        )
    }

    private suspend fun awaitState(
        vm: InboxViewModel,
        predicate: (InboxUiState) -> Boolean,
    ): InboxUiState =
        withTimeoutOrNull(8_000) {
            withContext(Dispatchers.Main.immediate) {
                vm.uiState.first { predicate(it) }
            }
        } ?: vm.uiState.value

    private fun listBodyOf(state: InboxUiState): InboxListBody = resolveInboxListBody(
        isLoading = state.isLoading,
        canReadMessages = state.canReadMessages,
        filteredEmpty = state.filteredThreads.isEmpty(),
        hasAnyThreads = state.threads.isNotEmpty(),
        pendingDeleteCount = state.pendingDeleteCount,
    )

    private fun InboxThreadUi.matchesInserted(
        address: String,
        body: String,
    ): Boolean = this.address == address || preview == body

    private fun inboxState(threads: List<InboxThreadUi>): InboxUiState = InboxUiState(
        threads = threads,
        filteredThreads = threads,
        unreadCount = threads.count { it.unread },
        isLoading = false,
        roleHeld = true,
        canReadMessages = true,
    )

    private fun overflowThreads(count: Int): List<InboxThreadUi> =
        (1..count).map { n ->
            overflowThread(
                id = n.toLong(),
                name = "Seed ${n.toString().padStart(2, '0')}",
                preview = "seeded row $n so the list overflows",
            )
        }

    private fun overflowThread(
        id: Long,
        name: String,
        preview: String,
    ): InboxThreadUi = InboxThreadUi(
        threadId = id,
        address = "+1555${id.toString().padStart(7, '0')}",
        displayName = name,
        initials = name.take(1),
        preview = preview,
        timeLabel = "9:14",
        dateMillis = 0L,
        unread = true,
        category = ThreadCategory.Other,
        otpCode = null,
    )

    companion object {
        private const val SEED_COUNT = 16
        private const val NEW_THREAD_ID = 1000L
        private const val OVERFLOW_VIEWPORT_DP = 400
        private const val SCROLLED_INDEX = 6
    }
}
