package com.pinotrouge.messaging.data.telephony

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.grantSmsRoleTo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins [SmsRepository] snippet precedence: undownloaded (type 130) wins,
 * then a real snippet, then the picture-only fallback.
 *
 * Acquires ROLE_SMS. 0 skipped.
 */
@RunWith(AndroidJUnit4::class)
class SmsRepositoryDownloadSnippetInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sms: SmsRepository
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS not held — real-provider snippet test cannot run", sms.isDefaultSmsApp())
    }

    @After
    fun tearDown() {
        for (uri in inserted.asReversed()) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test
    fun getThreads_downloadSnippet_whenType130AndBlankSnippet() = runBlocking {
        val address = "15555550901"
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        inserted += insertNotificationPdu(threadId, address)
        val row = thread(threadId)
        assertEquals(context.getString(R.string.mms_download), row.snippet)
        assertEquals("Download", row.snippet)
    }

    @Test
    fun getThreads_downloadSnippet_winsOverLaterSmsSnippet() = runBlocking {
        val address = "15555550902"
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        inserted += insertNotificationPdu(threadId, address)
        val smsBody = "later-sms-${System.currentTimeMillis()}"
        val smsInsert = sms.insertInbox(
            address = address,
            body = smsBody,
            dateMillis = System.currentTimeMillis(),
            read = true,
        )
        assertTrue("SMS insert failed: $smsInsert", smsInsert is SmsRepository.WriteResult.Success)
        inserted += (smsInsert as SmsRepository.WriteResult.Success).uri
            ?: error("SMS insert returned null uri")
        val row = thread(threadId)
        // #207's edge case: a type-130 row still wins over a real snippet in
        // the same thread. Intended; do not invert it here.
        assertEquals(context.getString(R.string.mms_download), row.snippet)
        assertEquals("Download", row.snippet)
    }

    @Test
    fun getThreads_photoFallback_whenBlankSnippetNo130() = runBlocking {
        val address = "15555550903"
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        inserted += insertRetrieveConf(threadId, address, textOnly = 0)
        val row = thread(threadId)
        assertEquals(SmsRepository.PHOTO_SNIPPET_FALLBACK, row.snippet)
    }

    @Test
    fun getThreads_leavesBlankSnippet_whenNo130AndNoPhoto() = runBlocking {
        val address = "15555550904"
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        inserted += insertRetrieveConf(threadId, address, textOnly = 1)
        val row = thread(threadId)
        assertTrue(
            "blank TEXT_ONLY snippet must not pick up Download or (Photo); was ${row.snippet}",
            row.snippet.isNullOrBlank(),
        )
    }

    @Test
    fun getThreads_keepsRealSnippet_whenNo130() = runBlocking {
        val address = "15555550905"
        val body = "real-snippet-${System.currentTimeMillis()}"
        val smsInsert = sms.insertInbox(
            address = address,
            body = body,
            dateMillis = System.currentTimeMillis(),
            read = true,
        )
        assertTrue("SMS insert failed: $smsInsert", smsInsert is SmsRepository.WriteResult.Success)
        inserted += (smsInsert as SmsRepository.WriteResult.Success).uri
            ?: error("SMS insert returned null uri")
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val row = thread(threadId)
        assertEquals(body, row.snippet)
    }

    private suspend fun thread(threadId: Long): SmsThread {
        val row = sms.getThreads(limit = 200).find { it.threadId == threadId }
        return checkNotNull(row) { "thread $threadId must appear in Chats" }
    }

    private fun insertNotificationPdu(threadId: Long, address: String): Uri =
        insertMmsPdu(
            threadId = threadId,
            address = address,
            messageType = MmsRepository.MESSAGE_TYPE_NOTIFICATION_IND,
            textOnly = 1,
        )

    private fun insertRetrieveConf(threadId: Long, address: String, textOnly: Int): Uri =
        insertMmsPdu(
            threadId = threadId,
            address = address,
            messageType = MmsRepository.MESSAGE_TYPE_RETRIEVE_CONF,
            textOnly = textOnly,
        )

    private fun insertMmsPdu(
        threadId: Long,
        address: String,
        messageType: Int,
        textOnly: Int,
    ): Uri {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, now / 1000L)
            put(Telephony.Mms.DATE_SENT, now / 1000L)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, messageType)
            put(Telephony.Mms.MMS_VERSION, 0x12)
            put(Telephony.Mms.MESSAGE_CLASS, "personal")
            put(Telephony.Mms.SUBJECT, "")
            put(Telephony.Mms.TEXT_ONLY, textOnly)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
        }
        val uri = context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values)
            ?: error("MMS pdu insert returned null")
        val msgId = ContentUris.parseId(uri)
        val addr = ContentValues().apply {
            put(Telephony.Mms.Addr.MSG_ID, msgId)
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, 0x89)
            put(Telephony.Mms.Addr.CHARSET, 106)
        }
        context.contentResolver.insert(Uri.parse("content://mms/$msgId/addr"), addr)
        return uri
    }
}
