package com.pinotrouge.messaging.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MessageRef
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
 * Thread union + [MessageRef] identity against the real provider.
 *
 * Acquires ROLE_SMS. ROLE_SMS-not-held coverage stays in
 * [SmsRepositoryDeleteInstrumentedTest], which must not grant the role.
 */
@RunWith(AndroidJUnit4::class)
class SmsRepositoryThreadUnionInstrumentedTest {

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
    fun mixedThread_returnsBothTransportsInDateOrder() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val address = "15555550311"
            val smsBody = "union-sms-${System.currentTimeMillis()}"
            val t0 = System.currentTimeMillis() - 5_000L
            val smsInsert = sms.insertInbox(
                address = address,
                body = smsBody,
                dateMillis = t0,
                read = true,
            )
            assertTrue("SMS insert failed: $smsInsert", smsInsert is SmsRepository.WriteResult.Success)
            val smsUri = (smsInsert as SmsRepository.WriteResult.Success).uri
            assertNotNull(smsUri)
            val threadId = findSmsThreadId(smsBody)
            assertTrue(threadId != null && threadId > 0)

            val mms = MmsRepository(context, sms)
            val mmsInsert = mms.insertInbox(
                IncomingMms(
                    originator = address,
                    body = "caption",
                    subject = "union-subject",
                    receivedAtMillis = t0 + 2_000L,
                    hasAttachment = true,
                ),
            )
            assertTrue("MMS insert failed: $mmsInsert", mmsInsert is SmsRepository.WriteResult.Success)
            val mmsUri = (mmsInsert as SmsRepository.WriteResult.Success).uri
            assertNotNull(mmsUri)

            try {
                val messages = sms.getMessagesForThread(threadId!!, limit = 50)
                val kinds = messages.map { it.kind }
                assertTrue(
                    "thread must contain SMS and MMS, got $kinds",
                    MessageRef.Kind.SMS in kinds && MessageRef.Kind.MMS in kinds,
                )
                assertEquals(
                    "merged thread must be date-ordered",
                    messages.map { it.date },
                    messages.map { it.date }.sorted(),
                )
                val mmsRow = messages.first { it.kind == MessageRef.Kind.MMS }
                assertEquals("union-subject", mmsRow.subject)
                assertEquals(false, mmsRow.textOnly)
            } finally {
                smsUri?.let { context.contentResolver.delete(it, null, null) }
                mmsUri?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test
    fun collidingIds_deleteMmsByRef_leavesSms() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val address = "15555550312"
            val smsBody = "collide-sms-${System.currentTimeMillis()}"
            val smsInsert = sms.insertInbox(
                address = address,
                body = smsBody,
                dateMillis = System.currentTimeMillis(),
                read = true,
            )
            assertTrue("SMS insert failed: $smsInsert", smsInsert is SmsRepository.WriteResult.Success)
            val originalSmsUri = (smsInsert as SmsRepository.WriteResult.Success).uri
            assertNotNull(originalSmsUri)
            val threadId = findSmsThreadId(smsBody)!!

            val pair = forceCollidingIds(
                threadId = threadId,
                address = address,
                smsBody = smsBody,
                originalSmsUri = originalSmsUri!!,
            )
            val smsUri = pair.first
            val mmsUri = pair.second
            val sharedId = ContentUris.parseId(smsUri)
            assertEquals(
                "test must construct a colliding _id",
                sharedId,
                ContentUris.parseId(mmsUri),
            )

            try {
                val before = sms.getMessagesForThread(threadId, limit = 50)
                assertEquals(
                    "both rows must be visible before delete; without MessageRef this test is a no-op",
                    2,
                    before.size,
                )
                assertTrue(before.any { it.ref == MessageRef.sms(sharedId) })
                assertTrue(before.any { it.ref == MessageRef.mms(sharedId) })

                val deleted = sms.deleteMessages(listOf(MessageRef.mms(sharedId)))
                assertTrue("delete MMS failed: $deleted", deleted is SmsRepository.WriteResult.Success)

                val after = sms.getMessagesForThread(threadId, limit = 50)
                assertEquals(1, after.size)
                assertEquals(MessageRef.Kind.SMS, after.single().kind)
                assertEquals(sharedId, after.single().id)
                assertEquals(smsBody, after.single().body)
            } finally {
                context.contentResolver.delete(smsUri, null, null)
                context.contentResolver.delete(mmsUri, null, null)
            }
        }
    }

    @Test
    fun deleteThread_clearsSmsAndMms() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val address = "15555550313"
            val smsBody = "del-thread-sms-${System.currentTimeMillis()}"
            val smsInsert = sms.insertInbox(
                address = address,
                body = smsBody,
                dateMillis = System.currentTimeMillis(),
                read = true,
            )
            assertTrue("SMS insert failed: $smsInsert", smsInsert is SmsRepository.WriteResult.Success)
            val threadId = findSmsThreadId(smsBody)!!
            val mms = MmsRepository(context, sms)
            val mmsInsert = mms.insertInbox(
                IncomingMms(
                    originator = address,
                    body = "bye",
                    hasAttachment = true,
                ),
            )
            assertTrue("MMS insert failed: $mmsInsert", mmsInsert is SmsRepository.WriteResult.Success)

            val deleted = sms.deleteThread(threadId)
            assertTrue("deleteThread failed: $deleted", deleted is SmsRepository.WriteResult.Success)
            assertEquals(0, countSmsInThread(threadId))
            assertEquals(0, countMmsInThread(threadId))
        }
    }

    @Test
    fun getThreads_photoSnippetFallback_whenSnippetBlank() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val address = "15555550314"
            val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
            val uri = insertMinimalMmsPdu(
                threadId = threadId,
                address = address,
                subject = null,
                dateMillis = System.currentTimeMillis(),
                forcedId = null,
                textOnly = 0,
            )
            try {
                val threads = sms.getThreads(limit = 200)
                val row = threads.find { it.threadId == threadId }
                assertNotNull("picture-only thread must appear in Chats", row)
                assertEquals("(Photo)", row!!.snippet)
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    /**
     * Forces SMS and MMS provider `_id`s to the same Long. Fails the test if
     * the provider will not honour an explicit `_id` — a skip would pass
     * without [MessageRef].
     */
    private fun forceCollidingIds(
        threadId: Long,
        address: String,
        smsBody: String,
        originalSmsUri: Uri,
    ): Pair<Uri, Uri> {
        val smsId = ContentUris.parseId(originalSmsUri)
        val mmsUri = insertMinimalMmsPdu(
            threadId = threadId,
            address = address,
            subject = "collide",
            dateMillis = System.currentTimeMillis(),
            forcedId = smsId,
            textOnly = 0,
        )
        if (ContentUris.parseId(mmsUri) == smsId) {
            return originalSmsUri to mmsUri
        }
        context.contentResolver.delete(mmsUri, null, null)
        val naturalMms = insertMinimalMmsPdu(
            threadId = threadId,
            address = address,
            subject = "collide",
            dateMillis = System.currentTimeMillis(),
            forcedId = null,
            textOnly = 0,
        )
        val mmsId = ContentUris.parseId(naturalMms)
        context.contentResolver.delete(originalSmsUri, null, null)
        val forcedSms = insertSmsWithId(threadId, address, smsBody, mmsId)
        if (ContentUris.parseId(forcedSms) != mmsId) {
            context.contentResolver.delete(forcedSms, null, null)
            context.contentResolver.delete(naturalMms, null, null)
            fail(
                "provider would not honour explicit _id; cannot construct SMS/MMS collision " +
                    "(sms=$smsId mms=$mmsId)",
            )
        }
        return forcedSms to naturalMms
    }

    private fun insertSmsWithId(
        threadId: Long,
        address: String,
        body: String,
        id: Long,
    ): Uri {
        val values = ContentValues().apply {
            put(Telephony.Sms._ID, id)
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.THREAD_ID, threadId)
        }
        return context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            ?: error("forced SMS insert returned null")
    }

    private fun insertMinimalMmsPdu(
        threadId: Long,
        address: String,
        subject: String?,
        dateMillis: Long,
        forcedId: Long?,
        textOnly: Int,
    ): Uri {
        val values = ContentValues().apply {
            if (forcedId != null) put(Telephony.Mms._ID, forcedId)
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, dateMillis / 1000L)
            put(Telephony.Mms.DATE_SENT, dateMillis / 1000L)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, 0x84) // Retrieve-Conf
            put(Telephony.Mms.MMS_VERSION, 0x12)
            put(Telephony.Mms.MESSAGE_CLASS, "personal")
            put(Telephony.Mms.SUBJECT, subject.orEmpty())
            put(Telephony.Mms.TEXT_ONLY, textOnly)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
        }
        val uri = context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values)
            ?: error("MMS pdu insert returned null")
        val msgId = ContentUris.parseId(uri)
        val addr = ContentValues().apply {
            put(Telephony.Mms.Addr.MSG_ID, msgId)
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, 0x89) // From
            put(Telephony.Mms.Addr.CHARSET, 106)
        }
        context.contentResolver.insert(Uri.parse("content://mms/$msgId/addr"), addr)
        return uri
    }

    private fun findSmsThreadId(body: String): Long? {
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

    private fun countSmsInThread(threadId: Long): Int =
        count(Telephony.Sms.CONTENT_URI, Telephony.Sms.THREAD_ID, threadId)

    private fun countMmsInThread(threadId: Long): Int =
        count(Telephony.Mms.CONTENT_URI, Telephony.Mms.THREAD_ID, threadId)

    private fun count(uri: Uri, threadColumn: String, threadId: Long): Int {
        return context.contentResolver.query(
            uri,
            arrayOf("_id"),
            "$threadColumn = ?",
            arrayOf(threadId.toString()),
            null,
        )?.use { it.count } ?: 0
    }
}
