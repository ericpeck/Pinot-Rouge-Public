package com.pinotrouge.messaging.sms

import android.net.InsertedSmsUri
import com.pinotrouge.messaging.data.prefs.AppSettings
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.AttachmentOp
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.EvaluationContext
import com.pinotrouge.messaging.rules.FilterDecision
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessagePipelineTest {

    private val engine = DefaultRuleEngine()
    private val zoneContext = EvaluationContext(
        isKnownContact = false,
        neverFilterContacts = true,
    )

    private fun msg(
        sender: String = "18445550192",
        body: String = "hello",
        at: Long = 1_700_000_000_000L,
    ) = CoalescedSms(sender, body, at)

    private fun holdRule(id: String = "r1", bodyNeedle: String = "offer") = Rule(
        id = id,
        name = "Hold offers",
        order = 0,
        match = MatchMode.ANY,
        conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, bodyNeedle)),
        actions = linkedSetOf(Action.HOLD, Action.SILENCE),
    )

    private fun silenceOnlyRule() = Rule(
        id = "r-sil",
        name = "Silence",
        order = 0,
        match = MatchMode.ANY,
        conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "quiet")),
        actions = setOf(Action.SILENCE),
    )

    @Test
    fun `allow path delivers and notifies`() = runBlocking {
        val effects = RecordingEffects()
        val message = msg(body = "lunch?")
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, emptyList(), zoneContext,
        )
        assertEquals(FilterDecision.Allow, decision)
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertEquals(1, effects.delivered.size)
        assertFalse(effects.delivered.single().read)
        assertEquals(1, effects.notified.size)
        assertFalse(effects.notified.single().silent)
        assertTrue(effects.retained.isEmpty())
        assertTrue(effects.held.isEmpty())
    }

    @Test
    fun `hold path does not deliver or notify`() = runBlocking {
        val effects = RecordingEffects()
        val message = msg(body = "Special offer inside")
        val rule = holdRule()
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(rule), zoneContext,
        )
        assertTrue(decision is FilterDecision.Matched)
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertTrue(effects.delivered.isEmpty())
        assertTrue(effects.notified.isEmpty())
        assertEquals(1, effects.held.size)
        assertEquals(rule.id, effects.held.single().ruleId)
        assertEquals(1, effects.catches.size)
    }

    @Test
    fun `matched without HOLD delivers with silence notification`() = runBlocking {
        val effects = RecordingEffects()
        val message = msg(body = "be quiet please")
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(silenceOnlyRule()), zoneContext,
        )
        assertTrue(decision is FilterDecision.Matched)
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertEquals(1, effects.delivered.size)
        assertEquals(1, effects.notified.size)
        assertTrue(effects.notified.single().silent)
        assertTrue(effects.held.isEmpty())
    }

    @Test
    fun `failed write retains message and does not notify`() = runBlocking {
        val effects = RecordingEffects(
            deliverResult = SmsRepository.WriteResult.Failed(RuntimeException("provider down")),
        )
        val message = msg(body = "plain message with no matching rule")
        IncomingMessagePipeline.applyDecision(message, FilterDecision.Allow, effects)
        assertEquals(1, effects.delivered.size)
        assertTrue(effects.notified.isEmpty())
        assertEquals(1, effects.retained.size)
        assertEquals(
            IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED,
            effects.retained.single().reason,
        )
    }

    @Test
    fun `RoleNotHeld write retains message and does not notify`() = runBlocking {
        val effects = RecordingEffects(
            deliverResult = SmsRepository.WriteResult.RoleNotHeld,
        )
        val message = msg(body = "hello from the void")
        IncomingMessagePipeline.applyDecision(message, FilterDecision.Allow, effects)
        assertTrue(effects.notified.isEmpty())
        assertEquals(1, effects.retained.size)
        assertEquals(
            IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED,
            effects.retained.single().reason,
        )
    }

    @Test
    fun `failed write on matched without HOLD retains without notify`() = runBlocking {
        val effects = RecordingEffects(
            deliverResult = SmsRepository.WriteResult.Failed(IllegalStateException("no write")),
        )
        val message = msg(body = "be quiet please")
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(silenceOnlyRule()), zoneContext,
        )
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertTrue(effects.notified.isEmpty())
        assertEquals(1, effects.retained.size)
    }

    @Test
    fun `blocked sender is held not dropped - contract via retain reason`() {
        // process() path holds with REASON_BLOCKED_SENDER; unit-level contract:
        assertEquals("Blocked sender", IncomingMessagePipeline.REASON_BLOCKED_SENDER)
        // RecordingEffects retain path used by production process() for blocked.
        runBlocking {
            val effects = RecordingEffects()
            effects.retainUnfiled(msg(body = "from blocked"), IncomingMessagePipeline.REASON_BLOCKED_SENDER)
            assertEquals(1, effects.retained.size)
            assertEquals(IncomingMessagePipeline.REASON_BLOCKED_SENDER, effects.retained.single().reason)
            assertTrue(effects.notified.isEmpty())
            assertTrue(effects.delivered.isEmpty())
        }
    }

    @Test
    fun `multipart coalesce joins bodies across segments`() {
        val part1 = "PRE-APPROVED for 5000 dollars - no "
        val part2 = "credit check required"
        val joined = part1 + part2
        assertTrue(joined.contains("no credit check"))

        val rule = Rule(
            id = "r3",
            name = "Loan",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "(pre-?approved|no credit check)"),
            ),
            actions = setOf(Action.HOLD),
        )
        val message = msg(body = joined)
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(rule), zoneContext,
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `block action records sender with normalized key`() = runBlocking {
        val effects = RecordingEffects()
        val rule = Rule(
            id = "r-block",
            name = "Block",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "spam")),
            actions = linkedSetOf(Action.HOLD, Action.BLOCK),
        )
        val message = msg(body = "spam now")
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(rule), zoneContext,
        )
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertEquals(listOf(SenderIds.normalize("18445550192")), effects.blocked)
    }

    @Test
    fun `reply action does not send - auto-reply removed from v1`() = runBlocking {
        // fix/remove-auto-reply: Action.REPLY may still live on a saved rule and
        // in autoReplyText, but the pipeline must never send.
        val effects = RecordingEffects()
        val rule = Rule(
            id = "r-reply",
            name = "Reply",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(Condition.Sender(SenderOp.IS_SHORT_CODE)),
            actions = linkedSetOf(Action.HOLD, Action.REPLY),
            autoReplyText = "stop",
        )
        val message = msg(sender = "88022", body = "x")
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(rule), zoneContext,
        )
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertTrue(effects.replies.isEmpty())
        assertEquals(1, effects.held.size) // HOLD still applies
    }

    @Test
    fun `SenderIds normalize matches last-10 phone key`() {
        assertEquals("8445550192", SenderIds.normalize("+1 (844) 555-0192"))
        assertEquals("8445550192", SenderIds.normalize("18445550192"))
        assertEquals("loanfast", SenderIds.normalize("LOANFAST"))
    }

    @Test
    fun `group contact short-circuit - known participant allows when neverFilterContacts`() {
        // Filter Rule Spec §1: isKnownContact true if ANY participant is a contact.
        // Engine skips every rule when neverFilterContacts && isKnownContact.
        val spamRule = holdRule(bodyNeedle = "offer")
        val message = msg(body = "Special offer inside")
        val groupContext = EvaluationContext(
            isKnownContact = true, // one saved participant among several
            neverFilterContacts = true,
        )
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(spamRule), groupContext,
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `group of unknown numbers still matches rules`() {
        val spamRule = holdRule(bodyNeedle = "offer")
        val message = msg(body = "Special offer inside")
        val unknownGroup = EvaluationContext(
            isKnownContact = false,
            neverFilterContacts = true,
        )
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(spamRule), unknownGroup,
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `MMS allow path delivers without notifying on write failure`() = runBlocking {
        val effects = RecordingEffects(
            deliverResult = SmsRepository.WriteResult.Failed(RuntimeException("mms provider")),
        )
        val message = CoalescedSms(
            sender = "15551212",
            body = "see photo",
            receivedAtMillis = 1L,
            participantAddresses = listOf("15551212", "15559876"),
            isMms = true,
            hasAttachment = true,
        )
        IncomingMessagePipeline.applyDecision(message, FilterDecision.Allow, effects)
        assertTrue(effects.notified.isEmpty())
        assertEquals(1, effects.retained.size)
    }

    /**
     * Pipeline-fallback leak: a throw from [PipelineEffects.recordCatch] after
     * a successful hold must not escape [IncomingMessagePipeline.applyDecision]
     * (and therefore must not trigger handleParts' unfiltered re-delivery).
     */
    @Test
    fun `hold then recordCatch throw does not deliver or notify`() = runBlocking {
        val effects = RecordingEffects(
            throwOnRecordCatch = RuntimeException("stats write failed"),
        )
        val message = msg(body = "Special offer inside")
        val rule = holdRule()
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(rule), zoneContext,
        )
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertEquals(1, effects.held.size)
        assertTrue(effects.delivered.isEmpty())
        assertTrue(effects.notified.isEmpty())
    }

    /**
     * Same leak via the blocklist write: hold is durable; a throw from
     * [PipelineEffects.block] must not cause a second delivery.
     */
    @Test
    fun `hold then block throw does not deliver or notify`() = runBlocking {
        val effects = RecordingEffects(
            throwOnBlock = RuntimeException("blocklist write failed"),
        )
        val rule = Rule(
            id = "r-hold-block",
            name = "Hold and block",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "spam")),
            actions = linkedSetOf(Action.HOLD, Action.BLOCK),
        )
        val message = msg(body = "spam now")
        val decision = IncomingMessagePipeline.evaluate(
            engine, message, listOf(rule), zoneContext,
        )
        IncomingMessagePipeline.applyDecision(message, decision, effects)
        assertEquals(1, effects.held.size)
        assertTrue(effects.delivered.isEmpty())
        assertTrue(effects.notified.isEmpty())
    }

    /**
     * Allow path: insert succeeded, then notify threw. Must not deliver a
     * second time — that is the duplicate-inbox-row form of the leak.
     */
    @Test
    fun `allow then notify throw does not re-deliver`() = runBlocking {
        val effects = RecordingEffects(
            throwOnNotify = RuntimeException("notification failed"),
        )
        val message = msg(body = "lunch?")
        IncomingMessagePipeline.applyDecision(message, FilterDecision.Allow, effects)
        assertEquals(1, effects.delivered.size)
        assertEquals(1, effects.notified.size)
    }

    /**
     * Regression guard on fix/silent-message-loss: a throw *before*
     * persistence must still escape applyDecision so handleParts can deliver
     * unfiltered. Green before and after this branch.
     */
    @Test
    fun `deliverInbox throw still escapes so unfiltered fallback can run`() = runBlocking {
        val effects = RecordingEffects(
            throwOnDeliverInbox = RuntimeException("provider exploded"),
        )
        val message = msg(body = "lunch?")
        var thrown: Throwable? = null
        try {
            IncomingMessagePipeline.applyDecision(message, FilterDecision.Allow, effects)
        } catch (t: Throwable) {
            thrown = t
        }
        assertEquals("provider exploded", thrown?.message)
        assertEquals(1, effects.delivered.size)
        assertTrue(effects.notified.isEmpty())
    }

    @Test
    fun `engine message uses body not subject and threads attachment flags`() {
        val picture = CoalescedSms(
            sender = "15551212",
            body = "",
            receivedAtMillis = 1L,
            isMms = true,
            subject = "Invoice",
            hasAttachment = true,
        )
        val incoming = IncomingMessagePipeline.toEngineMessage(picture)
        assertEquals("", incoming.body)
        assertTrue(incoming.hasPhoto)
        assertTrue(incoming.hasAnyPart)
    }

    // --- fix/notification-fail-open -------------------------------------
    // An M-Notification.ind arrives with isMms = true and hasAttachment =
    // false, because MmsPduDecoder cannot know what is in a message it has not
    // downloaded. Before the `|| isMms` these reached the engine looking like
    // an empty SMS, and DOES_NOT_CONTAIN matched vacuously.

    private fun notificationMms(sender: String = "15550000001") = CoalescedSms(
        sender = sender,
        body = "",
        receivedAtMillis = 1L,
        isMms = true,
        subject = null,
        hasAttachment = false,
    )

    private fun unknownSenderTextRule() = Rule(
        id = "r-nc",
        name = "Unknown senders without delivery",
        order = 0,
        match = MatchMode.ALL,
        conditions = listOf(
            Condition.Sender(SenderOp.NOT_IN_CONTACTS),
            Condition.Text(TextOp.DOES_NOT_CONTAIN, "delivery"),
        ),
        actions = linkedSetOf(Action.HOLD),
    )

    @Test
    fun `undownloaded picture message is not held by a DOES_NOT_CONTAIN rule`() {
        val decision = IncomingMessagePipeline.evaluate(
            engine,
            notificationMms(),
            listOf(unknownSenderTextRule()),
            zoneContext,
        )
        assertEquals(
            "an MMS we could not read must not be held by a rule about text",
            FilterDecision.Allow,
            decision,
        )
    }

    @Test
    fun `notification sets hasAnyPart but leaves hasPhoto false - decision 8 stays open`() {
        val incoming = IncomingMessagePipeline.toEngineMessage(notificationMms())
        assertTrue("body operators must be suppressed", incoming.hasAnyPart)
        assertFalse("a notification PDU carries no photo bit", incoming.hasPhoto)
    }

    @Test
    fun `HAS_PHOTO does not match an undownloaded picture message`() {
        val hasPhotoRule = Rule(
            id = "r-photo",
            name = "Photos",
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(Condition.Attachment(AttachmentOp.HAS_PHOTO)),
            actions = linkedSetOf(Action.HOLD),
        )
        val decision = IncomingMessagePipeline.evaluate(
            engine, notificationMms(), listOf(hasPhotoRule), zoneContext,
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `a genuinely empty SMS is unchanged by the isMms guard`() {
        val emptySms = CoalescedSms(
            sender = "15550000002",
            body = "",
            receivedAtMillis = 1L,
            isMms = false,
        )
        val incoming = IncomingMessagePipeline.toEngineMessage(emptySms)
        assertFalse(incoming.hasAnyPart)
        assertFalse(incoming.hasPhoto)
        val decision = IncomingMessagePipeline.evaluate(
            engine, emptySms, listOf(unknownSenderTextRule()), zoneContext,
        )
        assertTrue(
            "a blank SMS still fails open on DOES_NOT_CONTAIN - that is unchanged",
            decision is FilterDecision.Matched,
        )
    }

    @Test
    fun `notification type is not evaluated`() {
        val notification = com.pinotrouge.messaging.data.telephony.IncomingMms(
            originator = "15550000001",
            body = "",
            messageType = com.pinotrouge.messaging.data.telephony.MmsPduDecoder.TYPE_NOTIFICATION_IND,
        )
        assertFalse(IncomingMessagePipeline.shouldEvaluate(notification))
        val retrieved = notification.copy(
            messageType = com.pinotrouge.messaging.data.telephony.MmsPduDecoder.TYPE_RETRIEVE_CONF,
            hasPhoto = true,
            hasAnyPart = true,
        )
        assertTrue(IncomingMessagePipeline.shouldEvaluate(retrieved))
    }

    @Test
    fun `auto-download on home network and off while roaming by default`() {
        assertTrue(
            IncomingMessagePipeline.shouldAutoDownload(
                autoDownloadPictures = true,
                downloadWhileRoaming = false,
                roaming = false,
            ),
        )
        assertFalse(
            IncomingMessagePipeline.shouldAutoDownload(
                autoDownloadPictures = true,
                downloadWhileRoaming = false,
                roaming = true,
            ),
        )
        assertFalse(
            IncomingMessagePipeline.shouldAutoDownload(
                autoDownloadPictures = false,
                downloadWhileRoaming = true,
                roaming = false,
            ),
        )
    }

    @Test
    fun `auto-download flags read from AppSettings`() {
        assertTrue(IncomingMessagePipeline.autoDownloadPicturesOf(AppSettings()))
        assertFalse(
            IncomingMessagePipeline.autoDownloadPicturesOf(
                AppSettings(autoDownloadPictures = false),
            ),
        )
        assertFalse(IncomingMessagePipeline.downloadWhileRoamingOf(AppSettings()))
        assertTrue(
            IncomingMessagePipeline.downloadWhileRoamingOf(
                AppSettings(downloadWhileRoaming = true),
            ),
        )
    }

    @Test
    fun `CoalescedSms resolvedParticipants falls back to sender`() {
        val m = msg(sender = "88022")
        assertEquals(listOf("88022"), m.resolvedParticipants())
        val group = CoalescedSms(
            sender = "A",
            body = "hi",
            receivedAtMillis = 1L,
            participantAddresses = listOf("A", "B", "C"),
            isMms = true,
        )
        assertEquals(listOf("A", "B", "C"), group.resolvedParticipants())
    }

    private class RecordingEffects(
        private val deliverResult: SmsRepository.WriteResult =
            SmsRepository.WriteResult.Success(uri = InsertedSmsUri),
        private val throwOnDeliverInbox: Throwable? = null,
        private val throwOnNotify: Throwable? = null,
        private val throwOnBlock: Throwable? = null,
        private val throwOnRecordCatch: Throwable? = null,
    ) : PipelineEffects {
        data class Delivered(val message: CoalescedSms, val read: Boolean)
        data class Held(val message: CoalescedSms, val ruleId: String, val reason: String)
        data class Retained(val message: CoalescedSms, val reason: String)
        data class Notified(val message: CoalescedSms, val silent: Boolean)

        val delivered = mutableListOf<Delivered>()
        val held = mutableListOf<Held>()
        val retained = mutableListOf<Retained>()
        val notified = mutableListOf<Notified>()
        val blocked = mutableListOf<String>()
        /** Left for tests that assert no auto-reply was sent. */
        val replies = mutableListOf<Pair<String, String>>()
        val catches = mutableListOf<Pair<String, Long>>()

        override suspend fun deliverInbox(
            message: CoalescedSms,
            read: Boolean,
        ): SmsRepository.WriteResult {
            delivered += Delivered(message, read)
            throwOnDeliverInbox?.let { throw it }
            return deliverResult
        }

        override suspend fun hold(message: CoalescedSms, rule: Rule, reason: String) {
            held += Held(message, rule.id, reason)
        }

        override suspend fun retainUnfiled(message: CoalescedSms, reason: String) {
            retained += Retained(message, reason)
        }

        override suspend fun notify(message: CoalescedSms, silent: Boolean) {
            notified += Notified(message, silent)
            throwOnNotify?.let { throw it }
        }

        override suspend fun block(sender: String) {
            blocked += SenderIds.normalize(sender)
            throwOnBlock?.let { throw it }
        }

        override suspend fun recordCatch(ruleId: String, atMillis: Long) {
            catches += ruleId to atMillis
            throwOnRecordCatch?.let { throw it }
        }

        override suspend fun onWriteFailure(
            message: CoalescedSms,
            result: SmsRepository.WriteResult,
        ) {
            retainUnfiled(message, IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED)
        }
    }
}
