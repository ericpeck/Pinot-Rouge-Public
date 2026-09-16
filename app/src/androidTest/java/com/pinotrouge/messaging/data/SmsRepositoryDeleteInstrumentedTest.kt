package com.pinotrouge.messaging.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.data.telephony.SmsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider delete path when this class does not acquire ROLE_SMS.
 *
 * These three tests assert [SmsRepository.WriteResult.RoleNotHeld] when the
 * role is not held, and the complementary write result if a prior run left
 * it in place. They must keep covering both branches — this class therefore
 * never grants the role.
 *
 * Real-provider insert/delete coverage lives in
 * [SmsRepositoryProviderInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class SmsRepositoryDeleteInstrumentedTest {

    private lateinit var sms: SmsRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
    }

    @Test
    fun deleteThread_whenRoleNotHeld_returnsRoleNotHeld() = runBlocking {
        if (sms.isDefaultSmsApp()) {
            val result = sms.deleteThread(threadId = 9_999_999L)
            assertTrue(
                "Expected Success or Failed when role held, got $result",
                result is SmsRepository.WriteResult.Success ||
                    result is SmsRepository.WriteResult.Failed,
            )
            return@runBlocking
        }
        val result = sms.deleteThread(threadId = 1L)
        assertEquals(SmsRepository.WriteResult.RoleNotHeld, result)
    }

    @Test
    fun deleteMessages_whenRoleNotHeld_returnsRoleNotHeld() = runBlocking {
        if (sms.isDefaultSmsApp()) {
            val result = sms.deleteMessages(listOf(MessageRef.sms(9_999_998L)))
            assertTrue(
                result is SmsRepository.WriteResult.Success ||
                    result is SmsRepository.WriteResult.Failed,
            )
            return@runBlocking
        }
        val result = sms.deleteMessages(
            listOf(MessageRef.sms(1L), MessageRef.sms(2L), MessageRef.sms(3L)),
        )
        assertEquals(SmsRepository.WriteResult.RoleNotHeld, result)
    }

    @Test
    fun deleteMessages_emptyList_isRoleGated() = runBlocking {
        val result = sms.deleteMessages(emptyList())
        if (sms.isDefaultSmsApp()) {
            assertTrue(result is SmsRepository.WriteResult.Success)
        } else {
            assertEquals(SmsRepository.WriteResult.RoleNotHeld, result)
        }
    }
}
