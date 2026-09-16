package com.pinotrouge.messaging.data.telephony

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.room.TransportKind
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.createInMemoryDb
import com.pinotrouge.messaging.util.grantSmsRoleTo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MmsPartStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sms: SmsRepository
    private lateinit var mms: MmsRepository
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        mms = MmsRepository(context, sms)
        db = createInMemoryDb(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS not held — real-provider MMS part test cannot run", sms.isDefaultSmsApp())
    }

    @After
    fun tearDown() {
        for (uri in inserted.asReversed()) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        db.close()
        File(context.filesDir, QuarantineRepository.HELD_MEDIA_DIR).deleteRecursively()
    }

    @Test
    fun insertInbox_streams_bytes_readable_via_openInputStream() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x01, 0x02, 0x03)
        val insert = mms.insertInbox(
            IncomingMms(
                originator = "15555550401",
                body = "caption",
                subject = "keep-subject-separate",
                hasPhoto = true,
                hasAnyPart = true,
                parts = listOf(
                    MmsPart(contentType = "image/jpeg", bytes = jpeg),
                ),
            ),
        )
        assertTrue("insert failed: $insert", insert is SmsRepository.WriteResult.Success)
        val uri = (insert as SmsRepository.WriteResult.Success).uri!!
        inserted += uri
        val mmsId = ContentUris.parseId(uri)

        val parts = mms.getParts(mmsId)
        val image = parts.single { it.contentType.startsWith("image/") }
        assertTrue(image.byteSize > 0)
        val read = context.contentResolver.openInputStream(image.uri)!!.use { it.readBytes() }
        assertArrayEquals(jpeg, read)

        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.SUBJECT, Telephony.Mms.TEXT_ONLY),
            null,
            null,
            null,
        )!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("keep-subject-separate", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
    }

    @Test
    fun getParts_skips_and_cleans_empty_image() = runBlocking {
        val insert = mms.insertInbox(
            IncomingMms(
                originator = "15555550402",
                body = "text-only-after-cleanup",
            ),
        )
        assertTrue("insert failed: $insert", insert is SmsRepository.WriteResult.Success)
        val uri = (insert as SmsRepository.WriteResult.Success).uri!!
        inserted += uri
        val mmsId = ContentUris.parseId(uri)

        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.SEQ, 9)
            put(Telephony.Mms.Part.CONTENT_TYPE, "image/*")
            put(Telephony.Mms.Part.NAME, "attachment")
        }
        val emptyPart = context.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), values)
        assertTrue("marker insert failed", emptyPart != null)

        val parts = mms.getParts(mmsId)
        assertTrue(parts.none { it.contentType.startsWith("image/") && it.byteSize <= 0L })

        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf(Telephony.Mms.Part.CONTENT_TYPE),
            null,
            null,
            null,
        )!!.use { c ->
            var emptyImages = 0
            while (c.moveToNext()) {
                if (c.getString(0).orEmpty().startsWith("image/")) emptyImages++
            }
            assertEquals("empty image/* row must be deleted on touch", 0, emptyImages)
        }
    }

    @Test
    fun hold_then_moveToInbox_restores_mms_with_parts() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0x43, 0x44)
        val quarantine = QuarantineRepository(
            heldMessageDao = db.heldMessageDao(),
            blockedSenderDao = db.blockedSenderDao(),
            ruleDao = db.ruleDao(),
            smsRepository = sms,
            heldMediaDao = db.heldMediaDao(),
            mmsRepository = mms,
            context = context,
        )
        quarantine.holdOnArrival(
            sender = "15555550403",
            body = "held caption",
            receivedAtMillis = 1_700_000_000_000L,
            ruleId = "r-photo",
            reason = "Filter: photos",
            deleteAfterDays = 30,
            id = "held-photo-1",
            parts = listOf(MmsPart(contentType = "image/jpeg", bytes = jpeg)),
            subject = "subj",
            transportKind = TransportKind.MMS,
        )
        val refs = quarantine.heldMedia("held-photo-1")
        assertEquals(1, refs.size)
        assertEquals("image/jpeg", refs.single().contentType)
        assertArrayEquals(jpeg, refs.single().file.readBytes())
        assertTrue("must not use sender filename", refs.single().file.name != "photo.jpg")

        val restored = quarantine.moveToInbox("held-photo-1")
        assertTrue("restore failed: $restored", restored is SmsRepository.WriteResult.Success)
        val uri = (restored as SmsRepository.WriteResult.Success).uri!!
        inserted += uri
        val parts = mms.getParts(ContentUris.parseId(uri))
        val image = parts.single { it.contentType.startsWith("image/") }
        val read = context.contentResolver.openInputStream(image.uri)!!.use { it.readBytes() }
        assertArrayEquals(jpeg, read)
        assertTrue(quarantine.heldMedia("held-photo-1").isEmpty())
        assertTrue(!File(File(context.filesDir, QuarantineRepository.HELD_MEDIA_DIR), "held-photo-1").exists())
    }
}
