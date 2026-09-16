package com.pinotrouge.messaging.ui.builder

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.ui.components.FilterSaveToastSession
import com.pinotrouge.messaging.util.createInMemoryDb
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the *upstream* half of `fix/save-toast-unreachable`.
 *
 * [FilterSaveToastInstrumentedTest] guards the Filters host. This class asserts
 * that [BuilderViewModel.save] posts [saveToastMessage] into
 * [FilterSaveToastSession] — the line whose absence is the original defect.
 *
 * Pattern: real Room + real repos over [createInMemoryDb], `mockk(relaxed = true)`
 * for telephony — same shape as [com.pinotrouge.messaging.ui.NavBadgeInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class BuilderSaveToastInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var session: FilterSaveToastSession
    private lateinit var viewModel: BuilderViewModel

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        session = FilterSaveToastSession()
        val sms = mockk<SmsRepository>(relaxed = true)
        coEvery { sms.insertInbox(any(), any(), any(), any()) } returns
            SmsRepository.WriteResult.Success(uri = Uri.parse("content://sms/1"))
        val contacts = mockk<ContactsRepository>(relaxed = true)
        viewModel = BuilderViewModel(
            ruleRepository = RuleRepository(db.ruleDao(), db.ruleStatsDao()),
            quarantineRepository = QuarantineRepository(
                heldMessageDao = db.heldMessageDao(),
                blockedSenderDao = db.blockedSenderDao(),
                ruleDao = db.ruleDao(),
                smsRepository = sms,
                heldMediaDao = db.heldMediaDao(),
                mmsRepository = mockk(relaxed = true),
                context = context,
            ),
            messageRepository = MessageRepository(sms, contacts),
            settingsRepository = SettingsRepository(context),
            ruleEngine = DefaultRuleEngine(),
            smsRepository = sms,
            filterSaveToastSession = session,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun save_posts_confirmation_to_session() = runBlocking {
        assertNull(session.message.value)

        viewModel.setName("Loan and crypto offers")
        var saved = false
        viewModel.save { saved = true }

        withTimeout(5_000) {
            while (session.message.value == null || !saved) {
                delay(20)
            }
        }

        assertEquals(
            saveToastMessage("Loan and crypto offers"),
            session.message.value,
        )
    }

    @Test
    fun save_blank_name_uses_untitled_copy() = runBlocking {
        var saved = false
        viewModel.save { saved = true }

        withTimeout(5_000) {
            while (session.message.value == null || !saved) {
                delay(20)
            }
        }

        assertEquals(
            saveToastMessage(UNTITLED_FILTER_NAME),
            session.message.value,
        )
    }
}
