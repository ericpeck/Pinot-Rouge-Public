package com.pinotrouge.messaging.data.telephony

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import com.pinotrouge.messaging.sms.MmsDownloadClaims
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.grantSmsRoleTo
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stub lifecycle against the real provider: write a 130, flip or delete it
 * after the retrieve-conf's transaction id has changed.
 *
 * Acquires ROLE_SMS. 0 skipped.
 */
@RunWith(AndroidJUnit4::class)
class MmsStubMatchInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sms: SmsRepository
    private lateinit var mms: MmsRepository
    private val inserted = mutableListOf<Uri>()
    private val threadIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        mms = MmsRepository(context, sms)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS not held — real-provider stub test cannot run", sms.isDefaultSmsApp())
        MmsDownloadClaims.resetForTests()
    }

    @After
    fun tearDown() {
        for (uri in inserted.asReversed()) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        for (threadId in threadIds) {
            runCatching {
                context.contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms.THREAD_ID} = ?",
                    arrayOf(threadId.toString()),
                )
            }
        }
        MmsDownloadClaims.resetForTests()
    }

    @Test
    fun insertInbox_flipsStub_whenTransactionIdDiffers() = runBlocking {
        val address = "15555550801"
        val location = "http://mmsc.example/stub-match-a"
        val threadId = insertStub(
            address = address,
            transactionId = "txn-notify",
            contentLocation = location,
        )
        val retrieve = mms.insertInbox(
            retrieveConf(
                address = address,
                transactionId = "txn-retrieve-different",
                contentLocation = location,
            ),
        )
        assertTrue("insert failed: $retrieve", retrieve is SmsRepository.WriteResult.Success)
        track((retrieve as SmsRepository.WriteResult.Success).uri)

        val types = typesInThread(threadId)
        assertEquals("must be one row, not a leftover stub plus a new retrieve", 1, types.size)
        assertEquals(MmsRepository.MESSAGE_TYPE_RETRIEVE_CONF, types.single())
        assertFalse(types.contains(MmsRepository.MESSAGE_TYPE_NOTIFICATION_IND))
    }

    @Test
    fun insertInbox_flipsStub_whenTransactionIdHasTrailingSpace() = runBlocking {
        val address = "15555550802"
        val location = "http://mmsc.example/stub-match-space"
        val threadId = insertStub(
            address = address,
            transactionId = "1w774QoKutEP9fzXV54nbA",
            contentLocation = location,
        )
        val retrieve = mms.insertInbox(
            retrieveConf(
                address = address,
                transactionId = "1w774QoKutEP9fzXV54nbA ",
                contentLocation = location,
            ),
        )
        assertTrue("insert failed: $retrieve", retrieve is SmsRepository.WriteResult.Success)
        track((retrieve as SmsRepository.WriteResult.Success).uri)

        val types = typesInThread(threadId)
        assertEquals(1, types.size)
        assertEquals(MmsRepository.MESSAGE_TYPE_RETRIEVE_CONF, types.single())
    }

    @Test
    fun deleteNotificationStub_removes130_whenTransactionIdChanged() = runBlocking {
        val address = "15555550803"
        val location = "http://mmsc.example/stub-match-hold"
        val threadId = insertStub(
            address = address,
            transactionId = "txn-notify-hold",
            contentLocation = location,
        )
        mms.deleteNotificationStub(
            transactionId = "txn-retrieve-hold",
            contentLocation = location,
        )
        assertTrue(
            "held path must not leave a 130 stub",
            typesInThread(threadId).isEmpty(),
        )
    }

    @Test
    fun findStub_fallsBackToTransactionId_whenBothLocationsBlank() = runBlocking {
        val address = "15555550804"
        val threadId = insertStub(
            address = address,
            transactionId = "txn-only-key",
            contentLocation = null,
        )
        val retrieve = mms.insertInbox(
            retrieveConf(
                address = address,
                transactionId = "txn-only-key",
                contentLocation = null,
            ),
        )
        assertTrue("insert failed: $retrieve", retrieve is SmsRepository.WriteResult.Success)
        track((retrieve as SmsRepository.WriteResult.Success).uri)

        val types = typesInThread(threadId)
        assertEquals(1, types.size)
        assertEquals(MmsRepository.MESSAGE_TYPE_RETRIEVE_CONF, types.single())
    }

    @Test
    fun insertInbox_doesNotTouchStub_withDifferentContentLocation() = runBlocking {
        val address = "15555550805"
        val locKeep = "http://mmsc.example/stub-keep"
        val locFlip = "http://mmsc.example/stub-flip"
        val threadId = insertStub(
            address = address,
            transactionId = "txn-keep",
            contentLocation = locKeep,
        )
        insertStub(
            address = address,
            transactionId = "txn-flip",
            contentLocation = locFlip,
        )
        val retrieve = mms.insertInbox(
            retrieveConf(
                address = address,
                transactionId = "txn-retrieve-other",
                contentLocation = locFlip,
            ),
        )
        assertTrue("insert failed: $retrieve", retrieve is SmsRepository.WriteResult.Success)
        track((retrieve as SmsRepository.WriteResult.Success).uri)

        assertEquals(
            MmsRepository.MESSAGE_TYPE_NOTIFICATION_IND,
            typeForLocation(threadId, locKeep),
        )
        assertEquals(
            MmsRepository.MESSAGE_TYPE_RETRIEVE_CONF,
            typeForLocation(threadId, locFlip),
        )
    }

    @Test
    fun insertInbox_redelivery_doesNotDuplicateFlippedRow() = runBlocking {
        val address = "15555550806"
        val location = "http://mmsc.example/stub-redeliver"
        val threadId = insertStub(
            address = address,
            transactionId = "txn-redeliver",
            contentLocation = location,
        )
        val first = retrieveConf(
            address = address,
            transactionId = "txn-redeliver-conf",
            contentLocation = location,
        )
        val r1 = mms.insertInbox(first)
        assertTrue("first insert failed: $r1", r1 is SmsRepository.WriteResult.Success)
        track((r1 as SmsRepository.WriteResult.Success).uri)
        val r2 = mms.insertInbox(first)
        assertTrue("second insert failed: $r2", r2 is SmsRepository.WriteResult.Success)
        track((r2 as SmsRepository.WriteResult.Success).uri)

        val types = typesInThread(threadId)
        assertEquals("redelivery must not insert a second 132", 1, types.size)
        assertEquals(MmsRepository.MESSAGE_TYPE_RETRIEVE_CONF, types.single())
    }

    @Test
    fun insertInbox_flipsStub_whenRetrieveConfReportsDifferentContentLocation() = runBlocking {
        val address = "15555550807"
        val locationA = "http://mmsc.example/stub-notify-url"
        val locationB = "http://mmsc.example/stub-retrieve-echo"
        val threadId = insertStub(
            address = address,
            transactionId = "txn-shared",
            contentLocation = locationA,
        )
        val retrieve = mms.insertInbox(
            retrieveConf(
                address = address,
                transactionId = "txn-shared",
                contentLocation = locationB,
            ),
        )
        assertTrue("insert failed: $retrieve", retrieve is SmsRepository.WriteResult.Success)
        track((retrieve as SmsRepository.WriteResult.Success).uri)

        val types = typesInThread(threadId)
        assertEquals("must not leave a 130 beside a new 132", 1, types.size)
        assertEquals(MmsRepository.MESSAGE_TYPE_RETRIEVE_CONF, types.single())
        assertFalse(types.contains(MmsRepository.MESSAGE_TYPE_NOTIFICATION_IND))
    }

    @Test
    fun insertInbox_sharedLocation_differentSendersAreTwoRows_sameSenderDedups() = runBlocking {
        val location = "http://mmsc.example/shared-mmsc-url"
        val first = mms.insertInbox(
            retrieveConf(
                address = "15555550701",
                transactionId = "txn-shared-a",
                contentLocation = location,
            ),
        )
        assertTrue("first insert failed: $first", first is SmsRepository.WriteResult.Success)
        val uriA = (first as SmsRepository.WriteResult.Success).uri
            ?: error("first insert returned null uri")
        track(uriA)
        threadIds += threadIdOf(uriA)

        val second = mms.insertInbox(
            retrieveConf(
                address = "15555550702",
                transactionId = "txn-shared-b",
                contentLocation = location,
            ),
        )
        assertTrue("second insert failed: $second", second is SmsRepository.WriteResult.Success)
        val uriB = (second as SmsRepository.WriteResult.Success).uri
            ?: error("second insert returned null uri")
        track(uriB)
        threadIds += threadIdOf(uriB)

        assertTrue("different senders at one URL must be two rows", uriA != uriB)
        assertEquals(2, idsAtLocation(location).size)

        val redelivery = mms.insertInbox(
            retrieveConf(
                address = "15555550701",
                transactionId = "txn-shared-a-retry",
                contentLocation = location,
            ),
        )
        assertTrue("redelivery failed: $redelivery", redelivery is SmsRepository.WriteResult.Success)
        val uriAgain = (redelivery as SmsRepository.WriteResult.Success).uri
        track(uriAgain)
        assertEquals("same sender at that location still one row", uriA, uriAgain)
        assertEquals(2, idsAtLocation(location).size)
    }

    @Test
    fun insertInbox_blankOriginator_redelivery_doesNotDuplicate() = runBlocking {
        val location = "http://mmsc.example/blank-from-redeliver"
        val message = retrieveConf(
            address = "",
            transactionId = "txn-blank-from",
            contentLocation = location,
            participants = listOf("15555550999"),
        )
        val first = mms.insertInbox(message)
        assertTrue("first insert failed: $first", first is SmsRepository.WriteResult.Success)
        val uriA = (first as SmsRepository.WriteResult.Success).uri
            ?: error("first insert returned null uri")
        track(uriA)
        threadIds += threadIdOf(uriA)

        val second = mms.insertInbox(message)
        assertTrue("second insert failed: $second", second is SmsRepository.WriteResult.Success)
        val uriB = (second as SmsRepository.WriteResult.Success).uri
        track(uriB)
        assertEquals("blank-From redelivery must stay one row", uriA, uriB)
        assertEquals(1, idsAtLocation(location).size)
    }

    @Test
    fun insertInbox_blankOriginator_doesNotMatchRowWithFrom() = runBlocking {
        val location = "http://mmsc.example/blank-vs-from"
        val withFrom = retrieveConf(
            address = "15555550711",
            transactionId = "txn-has-from",
            contentLocation = location,
        )
        val blank = retrieveConf(
            address = "",
            transactionId = "txn-no-from",
            contentLocation = location,
            participants = listOf("15555550999"),
        )
        val rFrom = mms.insertInbox(withFrom)
        assertTrue("with-From insert failed: $rFrom", rFrom is SmsRepository.WriteResult.Success)
        val uriFrom = (rFrom as SmsRepository.WriteResult.Success).uri
            ?: error("with-From insert returned null uri")
        track(uriFrom)
        threadIds += threadIdOf(uriFrom)

        val rBlank = mms.insertInbox(blank)
        assertTrue("blank insert failed: $rBlank", rBlank is SmsRepository.WriteResult.Success)
        val uriBlank = (rBlank as SmsRepository.WriteResult.Success).uri
            ?: error("blank insert returned null uri")
        track(uriBlank)
        threadIds += threadIdOf(uriBlank)

        assertTrue("blank From must not collapse into a row that has one", uriFrom != uriBlank)
        assertEquals(2, idsAtLocation(location).size)

        val fromAgain = mms.insertInbox(withFrom)
        assertEquals(uriFrom, (fromAgain as SmsRepository.WriteResult.Success).uri)
        val blankAgain = mms.insertInbox(blank)
        assertEquals(uriBlank, (blankAgain as SmsRepository.WriteResult.Success).uri)
        assertEquals(2, idsAtLocation(location).size)
    }

    @Test
    fun startDownload_claimsSoASecondClaimFails() = runBlocking {
        insertStub(
            address = "15555550901",
            transactionId = "txn-claim-auto",
            contentLocation = "http://mmsc.example/auto-claim",
        )
        val mmsId = ContentUris.parseId(inserted.last())
        downloadPipeline().downloadPending(mmsId)
        assertTrue("startDownload must claim", MmsDownloadClaims.isInFlight(mmsId))
        assertFalse(
            "a tile tap's claim() must fail while auto-download is in flight",
            MmsDownloadClaims.claim(mmsId),
        )
    }

    @Test
    fun notifyDownloadFailed_releasesInFlightClaim() {
        val mmsId = 9_002L
        assertTrue(MmsDownloadClaims.claim(mmsId))
        downloadPipeline().notifyDownloadFailed(mmsId)
        assertFalse(MmsDownloadClaims.isInFlight(mmsId))
        assertTrue(
            "downloadMms must be able to start again after notifyDownloadFailed",
            MmsDownloadClaims.claim(mmsId),
        )
    }

    @Test
    fun downloadPending_handsWritableFileProviderUri() = runBlocking {
        insertStub(
            address = "15555550911",
            transactionId = "txn-download-uri",
            contentLocation = "http://mmsc.example/download-uri",
        )
        val mmsId = ContentUris.parseId(inserted.last())
        var captured: Uri? = null
        downloadPipeline { captured = it }.downloadPending(mmsId)
        val uri = requireNotNull(captured)
        assertEquals("content", uri.scheme)
        assertEquals(PlatformMmsTransport.authority(context.packageName), uri.authority)
        val payload = byteArrayOf(0x8C.toByte(), 0x84.toByte(), 0x01, 0x02)
        context.contentResolver.openOutputStream(uri).use { out ->
            assertTrue("download target must be writable through FileProvider", out != null)
            out!!.write(payload)
        }
        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(payload.contentEquals(read))
        uri.lastPathSegment?.let { name ->
            File(File(context.cacheDir, PlatformMmsTransport.DOWNLOAD_CACHE_DIR), name).delete()
        }
        PlatformMmsTransport.revokePduAccess(
            context,
            uri,
            PlatformMmsTransport.PDU_DOWNLOAD_FLAGS,
        )
    }

    private suspend fun insertStub(
        address: String,
        transactionId: String?,
        contentLocation: String?,
    ): Long {
        val result = mms.insertNotificationStub(
            IncomingMms(
                originator = address,
                body = "",
                transactionId = transactionId,
                contentLocation = contentLocation,
                messageType = MmsPduDecoder.TYPE_NOTIFICATION_IND,
            ),
        )
        assertTrue("stub insert failed: $result", result is SmsRepository.WriteResult.Success)
        val uri = (result as SmsRepository.WriteResult.Success).uri
            ?: error("stub insert returned null uri")
        track(uri)
        return threadIdOf(uri).also { threadIds += it }
    }

    private fun retrieveConf(
        address: String,
        transactionId: String?,
        contentLocation: String?,
        participants: List<String> = emptyList(),
    ): IncomingMms = IncomingMms(
        originator = address,
        participants = participants,
        body = "caption",
        hasPhoto = true,
        hasAnyPart = true,
        parts = listOf(
            MmsPart(
                contentType = "image/jpeg",
                bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01),
            ),
        ),
        transactionId = transactionId,
        contentLocation = contentLocation,
        messageType = MmsPduDecoder.TYPE_RETRIEVE_CONF,
    )

    private fun downloadPipeline(
        onDownload: ((Uri) -> Unit)? = null,
    ): IncomingMessagePipeline = IncomingMessagePipeline(
        ruleEngine = DefaultRuleEngine(),
        ruleRepository = mockk(relaxed = true),
        quarantineRepository = mockk(relaxed = true),
        smsRepository = sms,
        mmsRepository = mms,
        contactsRepository = mockk(relaxed = true),
        settingsRepository = SettingsRepository(context),
        notificationHelper = mockk(relaxed = true),
        blockedSenderDao = mockk(relaxed = true),
        mmsTransport = object : MmsTransport {
            override fun send(
                pduUri: Uri,
                sentIntent: PendingIntent,
                sourceIntent: Intent?,
            ) = Unit

            override fun download(
                locationUrl: String,
                targetUri: Uri,
                downloadedIntent: PendingIntent,
                sourceIntent: Intent?,
            ) {
                onDownload?.invoke(targetUri)
            }

            override fun carrierMaxMessageBytes(subscriptionId: Int?): Int = 300 * 1024
        },
        context = context,
    )

    private fun track(uri: Uri?) {
        if (uri != null) inserted += uri
    }

    private fun threadIdOf(uri: Uri): Long {
        return context.contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.THREAD_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        } ?: error("no THREAD_ID for $uri")
    }

    private fun typesInThread(threadId: Long): List<Int> {
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms.MESSAGE_TYPE),
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_TYPE)
            buildList {
                while (cursor.moveToNext()) add(cursor.getInt(idx))
            }
        }.orEmpty()
    }

    private fun typeForLocation(threadId: Long, location: String): Int? {
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.CONTENT_LOCATION),
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            null,
        )?.use { cursor ->
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_TYPE)
            val locIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.CONTENT_LOCATION)
            while (cursor.moveToNext()) {
                if (cursor.getString(locIdx)?.trim() == location) {
                    return@use cursor.getInt(typeIdx)
                }
            }
            null
        }
    }

    private fun idsAtLocation(location: String): List<Long> {
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.CONTENT_LOCATION),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
            val locIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.CONTENT_LOCATION)
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(locIdx)?.trim() == location) {
                        add(cursor.getLong(idIdx))
                    }
                }
            }
        }.orEmpty()
    }
}
