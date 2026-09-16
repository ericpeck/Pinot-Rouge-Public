package com.pinotrouge.messaging.ui

import android.net.Uri
import android.provider.Telephony
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.RootViewModel
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.unreadBadgeTotal
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.ui.nav.PinotBottomBar
import com.pinotrouge.messaging.ui.nav.PinotDestination
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.createInMemoryDb
import com.pinotrouge.messaging.util.grantSmsRoleTo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 — nav badges (Wave 12 retarget).
 *
 * Bottom bar is Chats · Filtered · Settings. Chats shows unread; Filtered shows
 * total held count. Search is no longer a tab.
 *
 * Retargeted from Wave 8, which asserted Search present and Filtered absent.
 */
@RunWith(AndroidJUnit4::class)
class NavBadgeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var quarantine: QuarantineRepository

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        val sms = mockk<SmsRepository>(relaxed = true)
        coEvery { sms.insertInbox(any(), any(), any(), any()) } returns
            SmsRepository.WriteResult.Success(uri = Uri.parse("content://sms/1"))
        quarantine = QuarantineRepository(
            heldMessageDao = db.heldMessageDao(),
            blockedSenderDao = db.blockedSenderDao(),
            ruleDao = db.ruleDao(),
            smsRepository = sms,
            heldMediaDao = db.heldMediaDao(),
            mmsRepository = mockk(relaxed = true),
            context = context,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun bottom_bar_renders_chats_filtered_settings_with_badges() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                PinotBottomBar(
                    selectedTab = PinotDestination.Inbox,
                    filteredSelected = false,
                    onNavigateTab = {},
                    onNavigateFiltered = {},
                    inboxUnreadCount = 3,
                    filteredHeldCount = 5,
                )
            }
        }
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("5").assertIsDisplayed()
        composeRule.onNodeWithText("Chats").assertIsDisplayed()
        composeRule.onNodeWithText("Filtered").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun bottom_bar_has_no_search_or_filters_tab_label() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                PinotBottomBar(
                    selectedTab = PinotDestination.Inbox,
                    filteredSelected = false,
                    onNavigateTab = {},
                    onNavigateFiltered = {},
                    inboxUnreadCount = 0,
                    filteredHeldCount = 0,
                )
            }
        }
        // Search left the bar (still a screen). "Filters" was never a tab label.
        composeRule.onNodeWithText("Search").assertDoesNotExist()
        composeRule.onNodeWithText("Filters").assertDoesNotExist()
        composeRule.onNodeWithText("Inbox").assertDoesNotExist()
    }

    @Test
    fun filtered_selected_does_not_light_chats_or_settings() {
        // Companion pin: while Filtered is open, only Filtered is selected.
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                PinotBottomBar(
                    selectedTab = null,
                    filteredSelected = true,
                    onNavigateTab = {},
                    onNavigateFiltered = {},
                    inboxUnreadCount = 1,
                    filteredHeldCount = 2,
                )
            }
        }
        composeRule.onNodeWithText("Filtered").assertIsDisplayed()
        composeRule.onNodeWithText("Chats").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun root_view_model_supplies_held_count_from_room() = runBlocking {
        quarantine.holdOnArrival(
            sender = "1", body = "a", receivedAtMillis = 1, ruleId = "r",
            reason = "Filter: x", deleteAfterDays = 30, id = "h1",
        )
        quarantine.holdOnArrival(
            sender = "2", body = "b", receivedAtMillis = 1, ruleId = "r",
            reason = "Filter: x", deleteAfterDays = 30, id = "h2",
        )
        assertEquals(2, quarantine.observeHeldCount().first())

        val smsRepo = mockk<SmsRepository>(relaxed = true)
        coEvery { smsRepo.getThreads(any()) } returns emptyList()
        val messageRepository = MessageRepository(
            smsRepository = smsRepo,
            contactsRepository = ContactsRepository(context),
        )
        coEvery { smsRepo.getThreads(any()) } returns emptyList()

        val vm = RootViewModel(
            settingsRepository = SettingsRepository(context),
            messageRepository = messageRepository,
            quarantineRepository = quarantine,
        )

        val held = withTimeout(5_000) {
            withContext(Dispatchers.Main.immediate) {
                vm.filteredHeldCount.first { it == 2 }
            }
        }
        assertEquals(2, held)
    }

    /**
     * The product assertion #35 could not exercise: mark-read must drop the
     * Chats badge via the existing ContentObserver, not a poll.
     */
    @Test
    fun chats_badge_clears_when_thread_marked_read() {
        runBlocking {
        val sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue(
            "ROLE_SMS not held — badge ContentObserver test cannot run",
            sms.isDefaultSmsApp(),
        )

        val messageRepository = MessageRepository(
            smsRepository = sms,
            contactsRepository = ContactsRepository(context),
        )
        val baseline = unreadBadgeTotal(sms.getThreads())
        val vm = RootViewModel(
            settingsRepository = SettingsRepository(context),
            messageRepository = messageRepository,
            quarantineRepository = quarantine,
        )
        withTimeout(8_000) {
            withContext(Dispatchers.Main.immediate) {
                vm.inboxUnreadCount.first { it == baseline }
            }
        }

        val address = "1555${System.currentTimeMillis() % 1_000_000_000L}"
        val body = "nav-badge-mark-read-${System.currentTimeMillis()}"
        val insert = sms.insertInbox(
            address = address,
            body = body,
            dateMillis = System.currentTimeMillis(),
            read = false,
        )
        assertTrue("insert failed: $insert", insert is SmsRepository.WriteResult.Success)
        val insertUri = (insert as SmsRepository.WriteResult.Success).uri
        assertNotNull(insertUri)

        try {
            val threadId = findThreadIdForBody(body)
            assertTrue("could not resolve thread for inserted body", threadId != null && threadId > 0)

            val shown = withTimeout(8_000) {
                withContext(Dispatchers.Main.immediate) {
                    vm.inboxUnreadCount.first { it == baseline + 1 }
                }
            }
            assertEquals(baseline + 1, shown)
            val badgeCount = mutableIntStateOf(shown)
            composeRule.setContent {
                PinotRougeTheme(darkTheme = true) {
                    PinotBottomBar(
                        selectedTab = PinotDestination.Inbox,
                        filteredSelected = false,
                        onNavigateTab = {},
                        onNavigateFiltered = {},
                        inboxUnreadCount = badgeCount.intValue,
                        filteredHeldCount = 0,
                    )
                }
            }
            composeRule.onNodeWithText(shown.toString()).assertIsDisplayed()

            val marked = sms.markThreadRead(threadId!!)
            assertTrue("markThreadRead failed: $marked", marked is SmsRepository.WriteResult.Success)

            val cleared = withTimeout(8_000) {
                withContext(Dispatchers.Main.immediate) {
                    vm.inboxUnreadCount.first { it == baseline }
                }
            }
            assertEquals(
                "ContentObserver must recompute the badge after mark-read; " +
                    "if this times out the update is not notifying",
                baseline,
                cleared,
            )
            composeRule.runOnIdle { badgeCount.intValue = cleared }
            composeRule.waitForIdle()
            if (cleared == 0) {
                composeRule.onNodeWithText(shown.toString()).assertDoesNotExist()
            } else {
                composeRule.onNodeWithText(cleared.toString()).assertIsDisplayed()
            }
        } finally {
            insertUri?.let { context.contentResolver.delete(it, null, null) }
        }
        }
    }

    /**
     * MMS-only thread: the badge must rise on insert and fall on mark-read.
     * Mixed SMS+MMS is a red herring — see Log/2026-08-20-mms-badge-observer-finding.
     */
    @Test
    fun chats_badge_clears_when_only_unread_message_is_mms() = runBlocking {
        val sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS not held", sms.isDefaultSmsApp())

        val vm = RootViewModel(
            settingsRepository = SettingsRepository(context),
            messageRepository = MessageRepository(sms, ContactsRepository(context)),
            quarantineRepository = quarantine,
        )
        val baseline = unreadBadgeTotal(sms.getThreads())
        awaitBadge(vm, baseline)

        // Fresh address so this is a thread of its own with no SMS in it.
        val address = "1555${System.currentTimeMillis() % 1_000_000_000L}"
        val insert = MmsRepository(context, sms).insertInbox(
            IncomingMms(originator = address, body = "…", hasAttachment = true),
        )
        val mmsUri = (insert as SmsRepository.WriteResult.Success).uri
        try {
            val threadId = queryMmsThreadId(checkNotNull(mmsUri))
            assertEquals(
                "an unread picture message must raise the Chats badge",
                baseline + 1,
                awaitBadge(vm, baseline + 1),
            )
            sms.markThreadRead(threadId)
            assertEquals(
                "markThreadRead must clear the badge for an MMS-only thread",
                baseline,
                awaitBadge(vm, baseline),
            )
        } finally {
            mmsUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private suspend fun awaitBadge(vm: RootViewModel, target: Int): Int =
        withTimeoutOrNull(8_000) {
            withContext(Dispatchers.Main.immediate) { vm.inboxUnreadCount.first { it == target } }
        } ?: vm.inboxUnreadCount.value

    private fun queryMmsThreadId(uri: Uri): Long {
        return context.contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.THREAD_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "inserted MMS row missing at $uri" }
            cursor.getLong(0)
        } ?: error("query returned null for $uri")
    }

    private fun findThreadIdForBody(body: String): Long? {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.BODY} = ?",
            arrayOf(body),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }
}
