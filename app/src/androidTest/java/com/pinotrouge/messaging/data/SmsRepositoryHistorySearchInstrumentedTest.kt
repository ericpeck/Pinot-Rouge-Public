package com.pinotrouge.messaging.data

import android.content.Context
import android.os.SystemClock
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Findings 7 and 8: thread/inbox pages, archive-by-id, sent SMS + MMS caption search.
 *
 * Acquires ROLE_SMS. Does not revoke in-process.
 */
@RunWith(AndroidJUnit4::class)
class SmsRepositoryHistorySearchInstrumentedTest {

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
    fun getMessagesForThread_newestPage_thenOlderPage() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val address = "15555550911"
            val t0 = System.currentTimeMillis() - 60_000L
            val bodies = (0 until 5).map { i ->
                "hist-page-$i-${t0 + i}"
            }
            val uris = bodies.mapIndexed { i, body ->
                val insert = sms.insertInbox(
                    address = address,
                    body = body,
                    dateMillis = t0 + i * 1_000L,
                    read = true,
                )
                assertTrue("insert $i failed: $insert", insert is SmsRepository.WriteResult.Success)
                (insert as SmsRepository.WriteResult.Success).uri
            }
            val threadId = findThreadIdForBody(bodies.last())
            assertNotNull(threadId)
            try {
                val newest = sms.getMessagesForThread(threadId!!, limit = 2)
                assertEquals(
                    "newest page must be the two latest, oldest-first",
                    listOf(bodies[3], bodies[4]),
                    newest.map { it.body },
                )
                val oldestOnPage = newest.minOf { it.date }
                val older = sms.getMessagesForThread(
                    threadId,
                    limit = 2,
                    beforeDateMillis = oldestOnPage,
                )
                assertEquals(
                    listOf(bodies[1], bodies[2]),
                    older.map { it.body },
                )
                val newestIds = newest.map { it.ref }.toSet()
                assertTrue(
                    "older page must not repeat the newest page",
                    older.none { it.ref in newestIds },
                )
            } finally {
                threadId?.let { sms.deleteThread(it) }
                uris.forEach { uri -> uri?.let { context.contentResolver.delete(it, null, null) } }
            }
        }
    }

    @Test
    fun getThreadsByIds_findsThreadMissingFromNewestSlice() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val oldBody = "hist-archive-old-${System.currentTimeMillis()}"
            val newBody = "hist-archive-new-${System.currentTimeMillis()}"
            val oldInsert = sms.insertInbox(
                address = "15555550912",
                body = oldBody,
                dateMillis = System.currentTimeMillis() - 86_400_000L,
                read = true,
            )
            assertTrue(oldInsert is SmsRepository.WriteResult.Success)
            val oldThread = findThreadIdForBody(oldBody)
            assertNotNull(oldThread)
            // simple=true conversations sort by Threads.DATE DESC (AOSP ignores
            // caller sort). That column is last-write at second precision, not
            // Sms.DATE — two inserts in the same second tie, and the lower _id
            // (the "old" row) wins newest-1.
            SystemClock.sleep(1_100)
            val newInsert = sms.insertInbox(
                address = "15555550913",
                body = newBody,
                dateMillis = System.currentTimeMillis(),
                read = true,
            )
            assertTrue(newInsert is SmsRepository.WriteResult.Success)
            val newThread = findThreadIdForBody(newBody)
            assertNotNull(newThread)
            try {
                val newestOne = sms.getThreads(limit = 1)
                assertFalse(
                    "old thread must not be required to sit in the newest-1 slice " +
                        "(old=$oldThread new=$newThread newest=${newestOne.map { it.threadId to it.date }})",
                    newestOne.any { it.threadId == oldThread },
                )
                val byId = sms.getThreadsByIds(listOf(oldThread!!))
                assertEquals(1, byId.size)
                assertEquals(oldThread, byId.single().threadId)
                val olderPage = sms.getThreadsOlderThan(
                    beforeDateMillis = checkNotNull(
                        newestOne.firstOrNull()?.date ?: (System.currentTimeMillis() + 1),
                    ),
                    limit = 500,
                )
                assertTrue(
                    "DATE < newest must be able to reach the older conversation",
                    olderPage.any { it.threadId == oldThread } ||
                        sms.getThreadsByIds(listOf(oldThread)).isNotEmpty(),
                )
            } finally {
                oldThread?.let { sms.deleteThread(it) }
                newThread?.let { sms.deleteThread(it) }
            }
        }
    }

    @Test
    fun searchMessageBodies_findsSentSmsThatRecentInboxMisses() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val phrase = "unique-sent-search-${System.currentTimeMillis()}"
            val insert = sms.insertSent(
                address = "15555550914",
                body = phrase,
                dateMillis = System.currentTimeMillis() - 10_000L,
            )
            assertTrue("insertSent failed: $insert", insert is SmsRepository.WriteResult.Success)
            val uri = (insert as SmsRepository.WriteResult.Success).uri
            try {
                val inbox = sms.getRecentInbox(limit = 400)
                assertTrue(
                    "getRecentInbox stays SMS-inbox-only",
                    inbox.none { it.body == phrase },
                )
                val hits = sms.searchMessageBodies(phrase)
                assertTrue(
                    "sent SMS body must be searchable: $hits",
                    hits.values.any { it.body.contains(phrase) },
                )
            } finally {
                uri?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test
    fun searchMessageBodies_findsMmsCaptionThatRecentInboxMisses() {
        assumeTrue("ROLE_SMS not held — real-provider test cannot run", sms.isDefaultSmsApp())
        runBlocking {
            val caption = "unique-mms-caption-${System.currentTimeMillis()}"
            val address = "15555550915"
            val mms = MmsRepository(context, sms)
            val insert = mms.insertInbox(
                IncomingMms(
                    originator = address,
                    body = caption,
                    hasAttachment = true,
                ),
            )
            assertTrue("MMS insert failed: $insert", insert is SmsRepository.WriteResult.Success)
            val uri = (insert as SmsRepository.WriteResult.Success).uri
            try {
                val inbox = sms.getRecentInbox(limit = 400)
                assertTrue(
                    "MMS caption is not an SMS inbox row",
                    inbox.none { it.body?.contains(caption) == true },
                )
                val hits = sms.searchMessageBodies(caption)
                assertTrue(
                    "MMS text/plain caption must be searchable: $hits",
                    hits.values.any { it.body.contains(caption) },
                )
            } finally {
                uri?.let { context.contentResolver.delete(it, null, null) }
            }
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
}
