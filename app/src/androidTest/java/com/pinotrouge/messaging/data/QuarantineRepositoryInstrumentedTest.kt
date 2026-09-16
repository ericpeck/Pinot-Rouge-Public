package com.pinotrouge.messaging.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.telephony.MmsPart
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.data.room.TransportKind
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.util.createInMemoryDb
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tier 2 — quarantine table from [[03 - Filter Rule Spec]] against real Room.
 * Mirrors [QuarantineSemanticsTest] decisions on a real database.
 */
@RunWith(AndroidJUnit4::class)
class QuarantineRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var quarantine: QuarantineRepository
    private lateinit var rules: RuleRepository
    private lateinit var sms: SmsRepository

    private val delivered = mutableListOf<Pair<String, Boolean>>() // body to read flag

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        sms = mockk()
        coEvery { sms.insertInbox(any(), any(), any(), any()) } coAnswers {
            val body: String = secondArg()
            val read: Boolean = arg(3)
            delivered += (body to read)
            SmsRepository.WriteResult.Success(uri = Uri.parse("content://sms/1"))
        }
        quarantine = QuarantineRepository(
            heldMessageDao = db.heldMessageDao(),
            blockedSenderDao = db.blockedSenderDao(),
            ruleDao = db.ruleDao(),
            smsRepository = sms,
            heldMediaDao = db.heldMediaDao(),
            mmsRepository = mockk(relaxed = true),
            context = context,
        )
        rules = RuleRepository(db.ruleDao(), db.ruleStatsDao())
        delivered.clear()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleting_a_rule_leaves_held_messages_row_for_row() = runBlocking {
        // feat/rule-deletion: rule delete must not touch held_messages.
        val rule = Rule(
            id = "r-del",
            name = "Loan and crypto offers",
            order = 0,
            match = MatchMode.ANY,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "loan")),
            actions = setOf(Action.HOLD),
        )
        rules.save(rule)
        quarantine.holdOnArrival(
            sender = "18445550192",
            body = "PRE-APPROVED loan",
            receivedAtMillis = 1_000L,
            ruleId = rule.id,
            reason = "Filter: Loan and crypto offers",
            deleteAfterDays = 30,
            heldAtMillis = 2_000L,
            id = "h-keep",
        )
        quarantine.holdOnArrival(
            sender = "18445550193",
            body = "crypto wallet",
            receivedAtMillis = 1_100L,
            ruleId = rule.id,
            reason = "Filter: Loan and crypto offers",
            deleteAfterDays = 30,
            heldAtMillis = 2_100L,
            id = "h-keep-2",
        )
        val before = quarantine.observeHeld().first()
        assertEquals(2, before.size)

        rules.delete(rule.id)

        val after = quarantine.observeHeld().first()
        assertEquals(before.size, after.size)
        assertEquals(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        after.forEach { row ->
            assertEquals(rule.id, row.ruleId)
            assertEquals("Filter: Loan and crypto offers", row.reason)
        }
        assertTrue(rules.getRules().isEmpty())
    }

    @Test
    fun hold_on_arrival_does_not_touch_telephony() = runBlocking {
        quarantine.holdOnArrival(
            sender = "18445550192",
            body = "held body",
            receivedAtMillis = 1_000L,
            ruleId = "r1",
            reason = "Filter: test",
            deleteAfterDays = 30,
            heldAtMillis = 2_000L,
            id = "h1",
        )
        assertTrue(delivered.isEmpty())
        assertEquals(1, quarantine.observeHeld().first().size)
        assertEquals("h1", quarantine.observeHeld().first().single().id)
        val expires = quarantine.observeHeld().first().single().expiresAt
        assertEquals(2_000L + TimeUnit.DAYS.toMillis(30), expires)
    }

    @Test
    fun move_to_inbox_inserts_unread_and_deletes_row() = runBlocking {
        quarantine.holdOnArrival(
            sender = "1", body = "b", receivedAtMillis = 1, ruleId = "r",
            reason = "x", deleteAfterDays = 30, id = "h-move",
        )
        val result = quarantine.moveToInbox("h-move")
        assertTrue(result is SmsRepository.WriteResult.Success)
        assertEquals(listOf("b" to false), delivered) // unread
        assertTrue(quarantine.observeHeld().first().isEmpty())
    }

    @Test
    fun move_to_inbox_keeps_row_when_provider_fails() = runBlocking {
        // Pins the silent-loss shape: a Failed insert must not delete the last copy.
        // A Success(uri = null) insert can no longer be produced by SmsRepository.insertInbox.
        coEvery { sms.insertInbox(any(), any(), any(), any()) } returns
            SmsRepository.WriteResult.Failed(RuntimeException("nope"))
        quarantine.holdOnArrival(
            sender = "1", body = "b", receivedAtMillis = 1, ruleId = "r",
            reason = "x", deleteAfterDays = 30, id = "h-fail",
        )
        quarantine.moveToInbox("h-fail")
        val remaining = quarantine.observeHeld().first()
        assertEquals(1, remaining.size)
        assertEquals(null, remaining.single().restoreClaimedAt)
        assertEquals(null, remaining.single().restoreUri)
    }

    @Test
    fun block_sender_removes_held_and_adds_blocklist() = runBlocking {
        quarantine.holdOnArrival(
            sender = "18445550192", body = "b", receivedAtMillis = 1, ruleId = "r",
            reason = "x", deleteAfterDays = 30, id = "h-block",
        )
        quarantine.blockSender("h-block", blockedAtMillis = 9L)
        assertTrue(quarantine.observeHeld().first().isEmpty())
        assertTrue(quarantine.isBlocked("18445550192"))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun delete_held_and_delete_all_leave_telephony_alone() = runBlocking {
        quarantine.holdOnArrival(
            sender = "1", body = "a", receivedAtMillis = 1, ruleId = "r",
            reason = "x", deleteAfterDays = 30, id = "h1",
        )
        quarantine.holdOnArrival(
            sender = "2", body = "b", receivedAtMillis = 1, ruleId = "r",
            reason = "x", deleteAfterDays = 30, id = "h2",
        )
        quarantine.deleteHeld("h1")
        assertEquals(1, quarantine.observeHeld().first().size)
        quarantine.deleteAllHeld()
        assertTrue(quarantine.observeHeld().first().isEmpty())
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun commit_expired_with_delete_action_hard_deletes() = runBlocking {
        rules.save(ruleWith(actions = setOf(Action.HOLD, Action.DELETE), id = "r-del"))
        val past = 1_000L
        quarantine.holdOnArrival(
            sender = "1", body = "gone", receivedAtMillis = past, ruleId = "r-del",
            reason = "x", deleteAfterDays = 1, heldAtMillis = past, id = "h-exp-del",
        )
        // Force expired: now far past expiresAt
        val summary = quarantine.commitExpired(nowMillis = past + TimeUnit.DAYS.toMillis(2))
        assertEquals(1, summary.deleted)
        assertEquals(0, summary.filed)
        assertEquals(0, summary.skipped)
        assertTrue(quarantine.observeHeld().first().isEmpty())
        assertTrue("DELETE expiry must not write Telephony", delivered.isEmpty())
    }

    @Test
    fun commit_expired_without_delete_files_as_read() = runBlocking {
        rules.save(ruleWith(actions = setOf(Action.HOLD, Action.SILENCE), id = "r-file"))
        val past = 1_000L
        quarantine.holdOnArrival(
            sender = "1", body = "file me", receivedAtMillis = past, ruleId = "r-file",
            reason = "x", deleteAfterDays = 1, heldAtMillis = past, id = "h-exp-file",
        )
        val summary = quarantine.commitExpired(nowMillis = past + TimeUnit.DAYS.toMillis(2))
        assertEquals(0, summary.deleted)
        assertEquals(1, summary.filed)
        assertEquals(0, summary.skipped)
        assertEquals(listOf("file me" to true), delivered) // already read
        assertTrue(quarantine.observeHeld().first().isEmpty())
    }

    @Test
    fun commit_expired_provider_fail_retains_row_and_counts_skipped() = runBlocking {
        // Pins expiry: a Failed insert is skipped, not filed, and the Room row survives.
        rules.save(ruleWith(actions = setOf(Action.HOLD), id = "r-skip"))
        coEvery { sms.insertInbox(any(), any(), any(), any()) } returns
            SmsRepository.WriteResult.Failed(IllegalStateException("provider"))
        val past = 1_000L
        quarantine.holdOnArrival(
            sender = "1", body = "keep", receivedAtMillis = past, ruleId = "r-skip",
            reason = "x", deleteAfterDays = 1, heldAtMillis = past, id = "h-skip",
        )
        val summary = quarantine.commitExpired(nowMillis = past + TimeUnit.DAYS.toMillis(2))
        assertEquals(0, summary.deleted)
        assertEquals(0, summary.filed)
        assertEquals(1, summary.skipped)
        assertEquals(1, quarantine.observeHeld().first().size)
        assertEquals("h-skip", quarantine.observeHeld().first().single().id)
    }

    @Test
    fun not_yet_expired_rows_are_left_alone() = runBlocking {
        rules.save(ruleWith(actions = setOf(Action.HOLD, Action.DELETE), id = "r-fresh"))
        val now = 1_000_000L
        quarantine.holdOnArrival(
            sender = "1", body = "fresh", receivedAtMillis = now, ruleId = "r-fresh",
            reason = "x", deleteAfterDays = 30, heldAtMillis = now, id = "h-fresh",
        )
        val summary = quarantine.commitExpired(nowMillis = now + 1_000L)
        assertEquals(0, summary.deleted + summary.filed + summary.skipped)
        assertEquals(1, quarantine.observeHeld().first().size)
    }

    @Test
    fun concurrentRestoreWritesTheSameHeldSmsOnce() = runBlocking {
        val entered = AtomicInteger()
        coEvery { sms.insertInbox(any(), any(), any(), any()) } coAnswers {
            entered.incrementAndGet()
            delay(80)
            SmsRepository.WriteResult.Success(
                uri = Uri.parse("content://sms/${entered.get()}"),
            )
        }
        quarantine.holdOnArrival(
            sender = "15555550123",
            body = "review only",
            receivedAtMillis = 1L,
            ruleId = "r",
            reason = "review",
            deleteAfterDays = 30,
            id = "review-race",
        )
        withTimeout(10_000) {
            listOf(
                async { quarantine.moveToInbox("review-race") },
                async { quarantine.moveToInbox("review-race") },
            ).awaitAll()
        }
        assertEquals("Concurrent restores must persist the message once", 1, entered.get())
        assertTrue(quarantine.observeHeld().first().isEmpty())
    }

    @Test
    fun concurrentExpiryAndMoveToInboxWriteOnce() = runBlocking {
        rules.save(ruleWith(actions = setOf(Action.HOLD), id = "r-overlap"))
        val entered = AtomicInteger()
        coEvery { sms.insertInbox(any(), any(), any(), any()) } coAnswers {
            entered.incrementAndGet()
            delay(80)
            SmsRepository.WriteResult.Success(
                uri = Uri.parse("content://sms/${entered.get()}"),
            )
        }
        val past = 1_000L
        quarantine.holdOnArrival(
            sender = "1",
            body = "overlap",
            receivedAtMillis = past,
            ruleId = "r-overlap",
            reason = "x",
            deleteAfterDays = 1,
            heldAtMillis = past,
            id = "h-overlap",
        )
        withTimeout(10_000) {
            listOf(
                async { quarantine.moveToInbox("h-overlap") },
                async {
                    quarantine.commitExpired(nowMillis = past + TimeUnit.DAYS.toMillis(2))
                },
            ).awaitAll()
        }
        assertEquals(1, entered.get())
        assertTrue(quarantine.observeHeld().first().isEmpty())
    }

    @Test
    fun move_to_inbox_failed_mms_insert_keeps_media_and_clears_claim() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)
        val mms = mockk<com.pinotrouge.messaging.data.telephony.MmsRepository>()
        coEvery { mms.insertInbox(any(), any()) } returns
            SmsRepository.WriteResult.Failed(RuntimeException("nope"))
        val local = QuarantineRepository(
            heldMessageDao = db.heldMessageDao(),
            blockedSenderDao = db.blockedSenderDao(),
            ruleDao = db.ruleDao(),
            smsRepository = sms,
            heldMediaDao = db.heldMediaDao(),
            mmsRepository = mms,
            context = context,
        )
        local.holdOnArrival(
            sender = "15555550999",
            body = "",
            receivedAtMillis = 1L,
            ruleId = "r",
            reason = "x",
            deleteAfterDays = 30,
            id = "h-keep-media",
            parts = listOf(MmsPart(contentType = "image/jpeg", bytes = jpeg)),
            transportKind = TransportKind.MMS,
        )
        val dir = File(File(context.filesDir, QuarantineRepository.HELD_MEDIA_DIR), "h-keep-media")
        assertTrue(dir.isDirectory)
        local.moveToInbox("h-keep-media")
        val remaining = local.observeHeld().first()
        assertEquals(1, remaining.size)
        assertEquals(null, remaining.single().restoreClaimedAt)
        assertEquals(null, remaining.single().restoreUri)
        assertTrue(dir.exists())
        assertArrayEquals(jpeg, local.heldMedia("h-keep-media").single().file.readBytes())
    }

    @Test
    fun deleteHeld_and_blockSender_skip_claimed_row() = runBlocking {
        quarantine.holdOnArrival(
            sender = "18445550192",
            body = "b",
            receivedAtMillis = 1L,
            ruleId = "r",
            reason = "x",
            deleteAfterDays = 30,
            id = "h-claimed-del",
        )
        db.heldMessageDao().claimRestore("h-claimed-del", 9L)
        quarantine.deleteHeld("h-claimed-del")
        assertEquals("h-claimed-del", quarantine.observeHeld().first().single().id)

        quarantine.blockSender("h-claimed-del", blockedAtMillis = 9L)
        assertEquals("h-claimed-del", quarantine.observeHeld().first().single().id)
        assertTrue("claimed block must not write the blocklist", !quarantine.isBlocked("18445550192"))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun deleteAllHeld_keeps_claimed_row_and_media() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42)
        quarantine.holdOnArrival(
            sender = "1", body = "free", receivedAtMillis = 1, ruleId = "r",
            reason = "x", deleteAfterDays = 30, id = "h-free",
        )
        quarantine.holdOnArrival(
            sender = "15555550999",
            body = "",
            receivedAtMillis = 1L,
            ruleId = "r",
            reason = "x",
            deleteAfterDays = 30,
            id = "h-claimed-all",
            parts = listOf(MmsPart(contentType = "image/jpeg", bytes = jpeg)),
            transportKind = TransportKind.MMS,
        )
        db.heldMessageDao().claimRestore("h-claimed-all", 9L)
        quarantine.deleteAllHeld()
        val remaining = quarantine.observeHeld().first()
        assertEquals(listOf("h-claimed-all"), remaining.map { it.id })
        val refs = quarantine.heldMedia("h-claimed-all")
        assertEquals(1, refs.size)
        assertArrayEquals(jpeg, refs.single().file.readBytes())
    }

    @Test
    fun moveToInbox_with_restoreUri_deletes_without_second_insert() = runBlocking {
        quarantine.holdOnArrival(
            sender = "1", body = "done", receivedAtMillis = 1, ruleId = "r",
            reason = "x", deleteAfterDays = 30, id = "h-done",
        )
        db.heldMessageDao().claimRestore("h-done", 1L)
        db.heldMessageDao().setRestoreUri("h-done", "content://sms/99")
        val result = quarantine.moveToInbox("h-done")
        assertTrue(result is SmsRepository.WriteResult.Success)
        assertEquals(
            Uri.parse("content://sms/99"),
            (result as SmsRepository.WriteResult.Success).uri,
        )
        assertTrue("must not insert again", delivered.isEmpty())
        assertTrue(quarantine.observeHeld().first().isEmpty())
    }

    @Test
    fun moveToInbox_missing_row_is_success_noop() = runBlocking {
        val result = quarantine.moveToInbox("no-such")
        assertTrue(result is SmsRepository.WriteResult.Success)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun commit_expired_delete_skips_claimed_row() = runBlocking {
        rules.save(ruleWith(actions = setOf(Action.HOLD, Action.DELETE), id = "r-del-claim"))
        val past = 1_000L
        quarantine.holdOnArrival(
            sender = "1", body = "keep", receivedAtMillis = past, ruleId = "r-del-claim",
            reason = "x", deleteAfterDays = 1, heldAtMillis = past, id = "h-exp-claim",
        )
        db.heldMessageDao().claimRestore("h-exp-claim", 9L)
        val summary = quarantine.commitExpired(nowMillis = past + TimeUnit.DAYS.toMillis(2))
        assertEquals(0, summary.deleted)
        assertEquals(0, summary.filed)
        assertEquals("h-exp-claim", quarantine.observeHeld().first().single().id)
    }

    @Test
    fun hold_writes_bytes_then_room_and_delete_removes_directory() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)
        quarantine.holdOnArrival(
            sender = "15555550999",
            body = "",
            receivedAtMillis = 1L,
            ruleId = "r",
            reason = "x",
            deleteAfterDays = 30,
            id = "h-media",
            parts = listOf(MmsPart(contentType = "image/jpeg", bytes = jpeg)),
            transportKind = TransportKind.MMS,
        )
        val refs = quarantine.heldMedia("h-media")
        assertEquals(1, refs.size)
        assertEquals("image/jpeg", refs.single().contentType)
        assertArrayEquals(jpeg, refs.single().file.readBytes())
        assertFalse(refs.single().file.name.contains("jpeg", ignoreCase = false) && refs.single().file.name.contains("/"))
        val dir = File(File(context.filesDir, QuarantineRepository.HELD_MEDIA_DIR), "h-media")
        assertTrue(dir.isDirectory)

        quarantine.deleteHeld("h-media")
        assertTrue(quarantine.heldMedia("h-media").isEmpty())
        assertFalse(dir.exists())
    }

    @Test
    fun sweep_orphans_deletes_dirs_without_held_row() = runBlocking {
        val orphan = File(File(context.filesDir, QuarantineRepository.HELD_MEDIA_DIR), "no-row")
        orphan.mkdirs()
        File(orphan, "dead").writeBytes(byteArrayOf(1, 2, 3))
        quarantine.sweepOrphans()
        assertFalse(orphan.exists())
    }

    private fun ruleWith(actions: Set<Action>, id: String) = Rule(
        id = id,
        name = id,
        order = 0,
        match = MatchMode.ANY,
        conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "x")),
        actions = actions,
    )
}
