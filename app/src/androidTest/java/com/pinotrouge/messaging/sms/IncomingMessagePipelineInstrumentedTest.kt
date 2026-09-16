package com.pinotrouge.messaging.sms

import android.net.Uri
import android.telephony.SmsMessage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.util.createInMemoryDb
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 1 — IncomingMessagePipeline against real in-memory Room + controlled
 * fakes. No SMS role required.
 *
 * Regression targets:
 * - WriteResult discarded → silent message loss (notify without persist)
 * - Blocked sender dropped instead of held
 * - HOLD path still writing / notifying
 * - Multipart not coalesced before evaluation
 */
@RunWith(AndroidJUnit4::class)
class IncomingMessagePipelineInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var ruleRepository: RuleRepository
    private lateinit var quarantineRepository: QuarantineRepository
    private lateinit var smsRepository: SmsRepository
    private lateinit var mmsRepository: MmsRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var pipeline: IncomingMessagePipeline

    private val notifyCalls = mutableListOf<Triple<String, String, Boolean>>()
    private val deliverCalls = mutableListOf<DeliverCall>()

    private data class DeliverCall(
        val address: String,
        val body: String,
        val dateMillis: Long,
        val read: Boolean,
    )

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        ruleRepository = RuleRepository(db.ruleDao(), db.ruleStatsDao())
        smsRepository = mockk()
        mmsRepository = mockk(relaxed = true)
        notificationHelper = mockk(relaxed = true)
        contactsRepository = mockk()
        coEvery { contactsRepository.isKnownContact(any()) } returns false
        coEvery { notificationHelper.notifyIncoming(any(), any(), any()) } coAnswers {
            notifyCalls += Triple(firstArg(), secondArg(), thirdArg())
        }
        // Default deliver: success (role held / provider ok).
        coEvery {
            smsRepository.insertInbox(any(), any(), any(), any())
        } coAnswers {
            deliverCalls += DeliverCall(
                address = firstArg(),
                body = secondArg(),
                dateMillis = thirdArg(),
                read = arg(3),
            )
            SmsRepository.WriteResult.Success(uri = Uri.parse("content://sms/1"))
        }
        coEvery { mmsRepository.insertInbox(any(), any()) } returns
            SmsRepository.WriteResult.Success(uri = Uri.parse("content://mms/1"))

        quarantineRepository = QuarantineRepository(
            heldMessageDao = db.heldMessageDao(),
            blockedSenderDao = db.blockedSenderDao(),
            ruleDao = db.ruleDao(),
            smsRepository = smsRepository,
            heldMediaDao = db.heldMediaDao(),
            mmsRepository = mmsRepository,
            context = context,
        )

        pipeline = IncomingMessagePipeline(
            ruleEngine = DefaultRuleEngine(),
            ruleRepository = ruleRepository,
            quarantineRepository = quarantineRepository,
            smsRepository = smsRepository,
            mmsRepository = mmsRepository,
            contactsRepository = contactsRepository,
            settingsRepository = SettingsRepository(context),
            notificationHelper = notificationHelper,
            blockedSenderDao = db.blockedSenderDao(),
            mmsTransport = mockk(relaxed = true),
            context = context,
        )
        notifyCalls.clear()
        deliverCalls.clear()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun allow_path_delivers_to_provider_and_notifies() = runBlocking {
        pipeline.handleParts(parts(sender = "18445550192", body = "lunch tomorrow?"))

        assertEquals(1, deliverCalls.size)
        assertEquals("lunch tomorrow?", deliverCalls.single().body)
        assertEquals(false, deliverCalls.single().read)
        assertEquals(1, notifyCalls.size)
        assertEquals(false, notifyCalls.single().third) // not silent
        assertTrue(quarantineRepository.observeHeld().first().isEmpty())
    }

    @Test
    fun hold_match_writes_room_not_provider_and_does_not_notify() = runBlocking {
        ruleRepository.save(holdRule(bodyNeedle = "offer"))

        pipeline.handleParts(parts(sender = "18445550192", body = "Special offer inside"))

        assertTrue("HOLD must not write Telephony", deliverCalls.isEmpty())
        assertTrue("Held messages must not notify", notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertEquals("Special offer inside", held.single().body)
        assertEquals("r-hold", held.single().ruleId)
    }

    @Test
    fun matched_without_hold_still_delivers_with_silent_notification() = runBlocking {
        ruleRepository.save(
            Rule(
                id = "r-sil",
                name = "Silence quiet",
                order = 0,
                match = MatchMode.ANY,
                conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "quiet")),
                actions = setOf(Action.SILENCE),
            ),
        )

        pipeline.handleParts(parts(body = "please be quiet now"))

        assertEquals(1, deliverCalls.size)
        assertEquals(1, notifyCalls.size)
        assertTrue(notifyCalls.single().third) // silent
        assertTrue(quarantineRepository.observeHeld().first().isEmpty())
    }

    /**
     * **The silent-message-loss regression.** If one test earns this suite,
     * it is this one: a failed provider write must not notify, and the body
     * must land in held_messages so nothing is destroyed.
     */
    @Test
    fun failed_provider_write_does_not_notify_and_retains_in_room() = runBlocking {
        coEvery {
            smsRepository.insertInbox(any(), any(), any(), any())
        } returns SmsRepository.WriteResult.Failed(RuntimeException("provider down"))

        pipeline.handleParts(parts(body = "plain message, no matching rule"))

        assertTrue("Must never notify for an unfiled message", notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertEquals(IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED, held.single().reason)
        assertEquals("plain message, no matching rule", held.single().body)
        // Verify notifyIncoming was not invoked at all (even via other paths).
        coVerify(exactly = 0) { notificationHelper.notifyIncoming(any(), any(), any()) }
    }

    @Test
    fun role_not_held_write_does_not_notify_and_retains() = runBlocking {
        coEvery {
            smsRepository.insertInbox(any(), any(), any(), any())
        } returns SmsRepository.WriteResult.RoleNotHeld

        pipeline.handleParts(parts(body = "hello from the void"))

        assertTrue(notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertEquals(IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED, held.single().reason)
    }

    @Test
    fun blocked_sender_is_held_with_blocked_reason_not_dropped() = runBlocking {
        // Pre-block the sender the way the Filtered screen does.
        db.blockedSenderDao().upsert(
            com.pinotrouge.messaging.data.room.BlockedSenderEntity(
                sender = SenderIds.normalize("18445550192"),
                blockedAt = 1_700_000_000_000L,
            ),
        )

        pipeline.handleParts(parts(sender = "18445550192", body = "still trying"))

        assertTrue("Blocked path must not write Telephony", deliverCalls.isEmpty())
        assertTrue("Blocked path must not notify", notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertEquals(IncomingMessagePipeline.REASON_BLOCKED_SENDER, held.single().reason)
        assertEquals("still trying", held.single().body)
        assertEquals(IncomingMessagePipeline.RULE_ID_SYSTEM, held.single().ruleId)
    }

    @Test
    fun multipart_segments_coalesce_before_evaluation() = runBlocking {
        ruleRepository.save(
            Rule(
                id = "r3",
                name = "Loan",
                order = 0,
                match = MatchMode.ANY,
                conditions = listOf(
                    Condition.Text(TextOp.MATCHES_REGEX, "(pre-?approved|no credit check)"),
                ),
                actions = setOf(Action.HOLD),
            ),
        )
        // Split so neither segment alone matches the full phrase — only the
        // joined body does. This is the multipart regression.
        val p1 = part(sender = "18445550192", body = "PRE-APPROVED for 5000 dollars - no ")
        val p2 = part(sender = "18445550192", body = "credit check required")

        pipeline.handleParts(arrayOf(p1, p2))

        assertTrue(deliverCalls.isEmpty())
        assertTrue(notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertTrue(held.single().body.contains("no credit check"))
        assertEquals("r3", held.single().ruleId)
    }

    /**
     * Filter Rule Spec §1: group with any saved contact bypasses every rule.
     */
    @Test
    fun mms_group_with_one_known_contact_bypasses_rules() = runBlocking {
        ruleRepository.save(holdRule(bodyNeedle = "offer"))
        coEvery { contactsRepository.isKnownContact("15551111") } returns false
        coEvery { contactsRepository.isKnownContact("15552222") } returns true // Mom

        val mmsDelivered = mutableListOf<IncomingMms>()
        coEvery { mmsRepository.insertInbox(any(), any()) } coAnswers {
            mmsDelivered += firstArg<IncomingMms>()
            SmsRepository.WriteResult.Success(uri = Uri.parse("content://mms/1"))
        }

        pipeline.handleMms(
            IncomingMms(
                originator = "15551111",
                participants = listOf("15552222"),
                body = "Special offer inside",
                receivedAtMillis = 1L,
            ),
        )

        assertEquals(1, mmsDelivered.size)
        assertTrue("Must not hold when any participant is a contact", notifyCalls.isNotEmpty() || mmsDelivered.isNotEmpty())
        assertTrue(quarantineRepository.observeHeld().first().isEmpty())
        assertEquals(1, notifyCalls.size)
    }

    @Test
    fun mms_group_all_unknown_still_holds_on_match() = runBlocking {
        ruleRepository.save(holdRule(bodyNeedle = "offer"))
        coEvery { contactsRepository.isKnownContact(any()) } returns false

        pipeline.handleMms(
            IncomingMms(
                originator = "15551111",
                participants = listOf("15553333"),
                body = "Special offer inside",
                receivedAtMillis = 1L,
            ),
        )

        coVerify(exactly = 0) { mmsRepository.insertInbox(any(), any()) }
        assertTrue(notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertEquals("Special offer inside", held.single().body)
    }

    /**
     * Pipeline-fallback leak through the real handleParts catch: hold succeeds
     * in Room, then the stats write throws. The message must stay in
     * held_messages and must **not** be written to Telephony or notified.
     */
    @Test
    fun hold_then_stats_write_fails_stays_in_room_not_telephony() = runBlocking {
        val realRules = RuleRepository(db.ruleDao(), db.ruleStatsDao())
        realRules.save(holdRule(bodyNeedle = "PRE-APPROVED"))
        val throwingRules = spyk(realRules)
        coEvery { throwingRules.recordCatch(any(), any()) } throws
            RuntimeException("stats write failed")

        val throwingPipeline = IncomingMessagePipeline(
            ruleEngine = DefaultRuleEngine(),
            ruleRepository = throwingRules,
            quarantineRepository = quarantineRepository,
            smsRepository = smsRepository,
            mmsRepository = mmsRepository,
            contactsRepository = contactsRepository,
            settingsRepository = SettingsRepository(context),
            notificationHelper = notificationHelper,
            blockedSenderDao = db.blockedSenderDao(),
            mmsTransport = mockk(relaxed = true),
            context = context,
        )

        throwingPipeline.handleParts(
            parts(
                sender = "18445550192",
                body = "PRE-APPROVED for 5000 dollars - no credit check",
            ),
        )

        assertTrue("HOLD must not write Telephony even when stats fail", deliverCalls.isEmpty())
        assertTrue("Must not notify a held message", notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertEquals(
            "PRE-APPROVED for 5000 dollars - no credit check",
            held.single().body,
        )
        coVerify(exactly = 0) { notificationHelper.notifyIncoming(any(), any(), any()) }
        coVerify(exactly = 0) { smsRepository.insertInbox(any(), any(), any(), any()) }
    }

    @Test
    fun mms_failed_write_does_not_notify() = runBlocking {
        coEvery { mmsRepository.insertInbox(any(), any()) } returns
            SmsRepository.WriteResult.Failed(RuntimeException("mms provider"))

        pipeline.handleMms(
            IncomingMms(
                originator = "15551111",
                body = "hello mms",
                receivedAtMillis = 1L,
            ),
        )

        assertTrue(notifyCalls.isEmpty())
        val held = quarantineRepository.observeHeld().first()
        assertEquals(1, held.size)
        assertEquals(IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED, held.single().reason)
    }

    // —— helpers ——

    private fun holdRule(bodyNeedle: String) = Rule(
        id = "r-hold",
        name = "Hold offers",
        order = 0,
        match = MatchMode.ANY,
        conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, bodyNeedle)),
        actions = linkedSetOf(Action.HOLD, Action.SILENCE),
    )

    private fun parts(
        sender: String = "18445550192",
        body: String = "hello",
        at: Long = 1_700_000_000_000L,
    ): Array<SmsMessage> = arrayOf(part(sender, body, at))

    private fun part(
        sender: String,
        body: String,
        at: Long = 1_700_000_000_000L,
    ): SmsMessage {
        val message = mockk<SmsMessage>()
        every { message.displayOriginatingAddress } returns sender
        every { message.originatingAddress } returns sender
        every { message.messageBody } returns body
        every { message.timestampMillis } returns at
        return message
    }
}
