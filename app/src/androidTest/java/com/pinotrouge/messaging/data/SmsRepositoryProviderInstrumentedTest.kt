package com.pinotrouge.messaging.data

import android.content.Context
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.grantSmsRoleTo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-provider insert, deleteThread, and markThreadRead coverage.
 *
 * Acquires ROLE_SMS via UiAutomation before each test. If acquisition
 * fails (a real device, a locked-down image), [assumeTrue] skips the
 * test rather than recording a pass that asserted nothing.
 *
 * Does not revoke the role in-process. Handing ROLE_SMS to another
 * package tears down UiAutomation (`System.exit`) and aborts the rest
 * of the connected suite. After `:app:connectedDebugAndroidTest`, hand
 * it back with:
 * `adb shell cmd role add-role-holder android.app.role.SMS com.google.android.apps.messaging`
 *
 * ROLE_SMS-not-held coverage lives in [SmsRepositoryDeleteInstrumentedTest],
 * which must not acquire the role.
 */
@RunWith(AndroidJUnit4::class)
class SmsRepositoryProviderInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sms: SmsRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
    }

    @Test
    fun deleteThread_roundTrip_whenRoleHeld() {
        assumeTrue(
            "ROLE_SMS not held — real-provider test cannot run",
            sms.isDefaultSmsApp(),
        )
        runBlocking {
            val address = "15555550199"
            val body = "batch-delete-instrumented-${System.currentTimeMillis()}"
            val insert = sms.insertInbox(
                address = address,
                body = body,
                dateMillis = System.currentTimeMillis(),
                read = true,
            )
            assertTrue("insert failed: $insert", insert is SmsRepository.WriteResult.Success)
            val insertUri = (insert as SmsRepository.WriteResult.Success).uri
            assertNotNull("insertInbox must not report Success with a null uri", insertUri)

            val threadId = findThreadIdForBody(body)
            assertTrue("could not resolve thread for inserted body", threadId != null && threadId > 0)

            val delete = sms.deleteThread(threadId!!)
            assertTrue("deleteThread failed: $delete", delete is SmsRepository.WriteResult.Success)

            val remaining = countMessagesWithBody(body)
            assertEquals(0, remaining)
        }
    }

    @Test
    fun insertInbox_whenRoleHeld_returnsSuccessWithResolvableUri() {
        assumeTrue(
            "ROLE_SMS not held — real-provider test cannot run",
            sms.isDefaultSmsApp(),
        )
        runBlocking {
            val address = "15555550201"
            val body = "null-insert-inbox-${System.currentTimeMillis()}"
            val insert = sms.insertInbox(
                address = address,
                body = body,
                dateMillis = System.currentTimeMillis(),
                read = true,
            )
            if (insert !is SmsRepository.WriteResult.Success) {
                fail("insertInbox expected Success, got $insert")
                return@runBlocking
            }
            val uri = insert.uri
            assertNotNull("insertInbox Success must carry a non-null uri", uri)
            val resolvedBody = queryBody(checkNotNull(uri))
            assertEquals(body, resolvedBody)

            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun markThreadRead_setsReadAndSeen_andSecondCallIsNoOp() {
        assumeTrue(
            "ROLE_SMS not held — real-provider test cannot run",
            sms.isDefaultSmsApp(),
        )
        runBlocking {
            val address = "15555550203"
            val body = "mark-thread-read-${System.currentTimeMillis()}"
            val insert = sms.insertInbox(
                address = address,
                body = body,
                dateMillis = System.currentTimeMillis(),
                read = false,
            )
            if (insert !is SmsRepository.WriteResult.Success) {
                fail("insertInbox expected Success, got $insert")
                return@runBlocking
            }
            val insertUri = insert.uri
            assertNotNull("insertInbox Success must carry a non-null uri", insertUri)

            val threadId = findThreadIdForBody(body)
            assertTrue("could not resolve thread for inserted body", threadId != null && threadId > 0)

            val flagsBefore = queryReadAndSeen(checkNotNull(insertUri))
            assertEquals("unread insert must land READ=0", 0, flagsBefore.first)
            assertEquals("unread insert must land SEEN=0", 0, flagsBefore.second)

            val marked = sms.markThreadRead(threadId!!)
            assertTrue("markThreadRead failed: $marked", marked is SmsRepository.WriteResult.Success)
            val markedSuccess = marked as SmsRepository.WriteResult.Success
            assertEquals(
                "update has no URI; Success(uri = null) matches the delete paths",
                null,
                markedSuccess.uri,
            )

            val flagsAfter = queryReadAndSeen(insertUri)
            assertEquals("READ must be 1 after markThreadRead", 1, flagsAfter.first)
            assertEquals("SEEN must be 1 after markThreadRead", 1, flagsAfter.second)

            val again = sms.markThreadRead(threadId)
            assertTrue(
                "second markThreadRead must be a no-op Success, not a throw: $again",
                again is SmsRepository.WriteResult.Success,
            )
            val flagsAgain = queryReadAndSeen(insertUri)
            assertEquals(1, flagsAgain.first)
            assertEquals(1, flagsAgain.second)

            context.contentResolver.delete(insertUri, null, null)
        }
    }

    @Test
    fun markThreadRead_setsMmsReadAndSeen_whenUnreadPictureMessageInThread() {
        assumeTrue(
            "ROLE_SMS not held — real-provider test cannot run",
            sms.isDefaultSmsApp(),
        )
        runBlocking {
            val address = "15555550204"
            val smsBody = "mark-thread-read-mms-sms-${System.currentTimeMillis()}"
            val smsInsert = sms.insertInbox(
                address = address,
                body = smsBody,
                dateMillis = System.currentTimeMillis(),
                read = false,
            )
            if (smsInsert !is SmsRepository.WriteResult.Success) {
                fail("insertInbox expected Success, got $smsInsert")
                return@runBlocking
            }
            val smsUri = smsInsert.uri
            assertNotNull("insertInbox Success must carry a non-null uri", smsUri)

            val threadId = findThreadIdForBody(smsBody)
            assertTrue("could not resolve thread for inserted body", threadId != null && threadId > 0)

            val mms = MmsRepository(context, sms)
            val mmsInsert = mms.insertInbox(
                IncomingMms(
                    originator = address,
                    body = "mark-thread-read-mms-${System.currentTimeMillis()}",
                    hasAttachment = true,
                ),
            )
            if (mmsInsert !is SmsRepository.WriteResult.Success) {
                fail("MmsRepository.insertInbox expected Success, got $mmsInsert")
                return@runBlocking
            }
            val mmsUri = mmsInsert.uri
            assertNotNull("insertInbox Success must carry a non-null uri", mmsUri)

            try {
                val mmsThreadId = queryMmsThreadId(checkNotNull(mmsUri))
                assertEquals(
                    "SMS and MMS must land in the same conversation",
                    threadId,
                    mmsThreadId,
                )

                val smsBefore = queryReadAndSeen(checkNotNull(smsUri))
                assertEquals("unread SMS insert must land READ=0", 0, smsBefore.first)
                assertEquals("unread SMS insert must land SEEN=0", 0, smsBefore.second)

                val mmsBefore = queryMmsReadAndSeen(mmsUri)
                assertEquals("unread MMS insert must land READ=0", 0, mmsBefore.first)
                assertEquals("unread MMS insert must land SEEN=0", 0, mmsBefore.second)

                val marked = sms.markThreadRead(threadId!!)
                assertTrue(
                    "markThreadRead failed: $marked",
                    marked is SmsRepository.WriteResult.Success,
                )

                val smsAfter = queryReadAndSeen(smsUri)
                assertEquals("SMS READ must be 1 after markThreadRead", 1, smsAfter.first)
                assertEquals("SMS SEEN must be 1 after markThreadRead", 1, smsAfter.second)

                val mmsAfter = queryMmsReadAndSeen(mmsUri)
                assertEquals("MMS READ must be 1 after markThreadRead", 1, mmsAfter.first)
                assertEquals("MMS SEEN must be 1 after markThreadRead", 1, mmsAfter.second)
            } finally {
                smsUri?.let { context.contentResolver.delete(it, null, null) }
                mmsUri?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test
    fun insertSent_whenRoleHeld_returnsSuccessWithResolvableUri() {
        assumeTrue(
            "ROLE_SMS not held — real-provider test cannot run",
            sms.isDefaultSmsApp(),
        )
        runBlocking {
            val address = "15555550202"
            val body = "null-insert-sent-${System.currentTimeMillis()}"
            val insert = sms.insertSent(
                address = address,
                body = body,
                dateMillis = System.currentTimeMillis(),
            )
            if (insert !is SmsRepository.WriteResult.Success) {
                fail("insertSent expected Success, got $insert")
                return@runBlocking
            }
            val uri = insert.uri
            assertNotNull("insertSent Success must carry a non-null uri", uri)
            val resolvedBody = queryBody(checkNotNull(uri))
            assertEquals(body, resolvedBody)

            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun queryReadAndSeen(uri: android.net.Uri): Pair<Int, Int> {
        return context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.READ, Telephony.Sms.SEEN),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "inserted row missing at $uri" }
            cursor.getInt(0) to cursor.getInt(1)
        } ?: error("query returned null for $uri")
    }

    private fun queryMmsReadAndSeen(uri: android.net.Uri): Pair<Int, Int> {
        return context.contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.READ, Telephony.Mms.SEEN),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "inserted MMS row missing at $uri" }
            cursor.getInt(0) to cursor.getInt(1)
        } ?: error("query returned null for $uri")
    }

    private fun queryMmsThreadId(uri: android.net.Uri): Long {
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

    private fun queryBody(uri: android.net.Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.BODY),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun findThreadIdForBody(body: String): Long? {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY),
            "${Telephony.Sms.BODY} = ?",
            arrayOf(body),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun countMessagesWithBody(body: String): Int {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.BODY} = ?",
            arrayOf(body),
            null,
        )?.use { it.count } ?: 0
    }
}
