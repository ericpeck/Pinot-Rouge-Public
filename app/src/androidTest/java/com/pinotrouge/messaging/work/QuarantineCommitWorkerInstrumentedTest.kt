package com.pinotrouge.messaging.work

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.util.createInMemoryDb
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Tier 2 — [QuarantineCommitWorker] via work-testing. Worker is a thin shell
 * over [QuarantineRepository.commitExpired]; we assert the outcomes that
 * production code promised for hybrid quarantine.
 */
@RunWith(AndroidJUnit4::class)
class QuarantineCommitWorkerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var quarantine: QuarantineRepository
    private lateinit var rules: RuleRepository
    private lateinit var sms: SmsRepository
    private val filedRead = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        sms = mockk()
        coEvery { sms.insertInbox(any(), any(), any(), any()) } coAnswers {
            val read: Boolean = arg(3)
            filedRead += read
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
        filedRead.clear()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun worker_deletes_expired_with_delete_action() = runBlocking {
        rules.save(rule("r-del", setOf(Action.HOLD, Action.DELETE)))
        seedExpired(ruleId = "r-del", id = "h1")
        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(quarantine.observeHeld().first().isEmpty())
        assertTrue(filedRead.isEmpty())
    }

    @Test
    fun worker_files_expired_without_delete_as_read() = runBlocking {
        rules.save(rule("r-file", setOf(Action.HOLD)))
        seedExpired(ruleId = "r-file", id = "h2")
        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(quarantine.observeHeld().first().isEmpty())
        assertEquals(listOf(true), filedRead)
    }

    @Test
    fun worker_retains_row_and_succeeds_when_provider_fails() = runBlocking {
        rules.save(rule("r-skip", setOf(Action.HOLD)))
        coEvery { sms.insertInbox(any(), any(), any(), any()) } returns
            SmsRepository.WriteResult.RoleNotHeld
        seedExpired(ruleId = "r-skip", id = "h3")
        val result = runWorker()
        // Always success so the next daily run retries skipped rows.
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, quarantine.observeHeld().first().size)
        assertEquals("h3", quarantine.observeHeld().first().single().id)
    }

    private suspend fun seedExpired(ruleId: String, id: String) {
        val past = 1_000L
        quarantine.holdOnArrival(
            sender = "18445550192",
            body = "body-$id",
            receivedAtMillis = past,
            ruleId = ruleId,
            reason = "Filter: test",
            deleteAfterDays = 1,
            heldAtMillis = past,
            id = id,
        )
        // Sanity: expired relative to "now" used inside worker (System.currentTimeMillis).
        assertTrue(past + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis())
    }

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder.from(context, QuarantineCommitWorker::class.java)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker {
                        return QuarantineCommitWorker(
                            appContext,
                            workerParameters,
                            quarantine,
                        )
                    }
                },
            )
            .build()
        return worker.startWork().get()
    }

    private fun rule(id: String, actions: Set<Action>) = Rule(
        id = id,
        name = id,
        order = 0,
        match = MatchMode.ANY,
        conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "x")),
        actions = actions,
    )
}
