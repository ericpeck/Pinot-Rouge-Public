package com.pinotrouge.messaging.data.telephony

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.ui.media.MmsImageLadder
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
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

@RunWith(AndroidJUnit4::class)
class MmsSentPartInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sms: SmsRepository
    private lateinit var mms: MmsRepository
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        mms = MmsRepository(context, sms)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS required to write Mms.Sent", sms.isDefaultSmsApp())
    }

    @After
    fun tearDown() {
        inserted.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test
    fun insertSent_photoWithCaption_twoParts_textOnlyZero() = runBlocking {
        val image = outboundJpeg()
        val uri = insertSent(body = "look at this", image = image)
        val msgId = ContentUris.parseId(uri)
        assertEquals(0, textOnly(uri))
        val parts = mms.getParts(msgId)
        assertEquals(2, parts.size)
        val text = parts.single { it.contentType.startsWith("text/plain") }
        val photo = parts.single { it.contentType.startsWith("image/") }
        assertEquals("look at this", partText(text.uri))
        assertArrayEquals(image.bytes, partBytes(photo.uri))
        assertEquals(image.location, partLocation(msgId, photo.seq))
        assertEquals(image.contentType, photo.contentType)
    }

    @Test
    fun insertSent_photoWithoutCaption_imagePartOnly() = runBlocking {
        val image = outboundJpeg()
        val uri = insertSent(body = "   ", image = image)
        val msgId = ContentUris.parseId(uri)
        assertEquals(0, textOnly(uri))
        val parts = mms.getParts(msgId)
        assertEquals(1, parts.size)
        assertTrue(parts.single().contentType.startsWith("image/"))
        assertArrayEquals(image.bytes, partBytes(parts.single().uri))
        assertTrue(
            "blank caption must not leave an empty text/plain part",
            parts.none { it.contentType.startsWith("text/plain") },
        )
    }

    @Test
    fun insertSent_textOnlyGroup_oneTextPart_textOnlyOne() = runBlocking {
        val uri = insertSent(
            recipients = listOf("15555550101", "15555550102"),
            body = "group hello",
            image = null,
        )
        val msgId = ContentUris.parseId(uri)
        assertEquals(1, textOnly(uri))
        val parts = mms.getParts(msgId)
        assertEquals(1, parts.size)
        assertTrue(parts.single().contentType.startsWith("text/plain"))
        assertEquals("group hello", partText(parts.single().uri))
    }

    @Test
    fun insertSent_storesLadderBytes_notADifferentCopy() = runBlocking {
        val image = outboundJpeg(payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33))
        val uri = insertSent(body = "", image = image)
        val photo = mms.getParts(ContentUris.parseId(uri)).single()
        assertArrayEquals(
            "sender thread must show the encoded bytes that went into the PDU",
            image.bytes,
            partBytes(photo.uri),
        )
    }

    private suspend fun insertSent(
        body: String,
        image: MmsSendComposer.OutboundImage?,
        recipients: List<String> = listOf("15555550980"),
    ): Uri {
        val result = mms.insertSent(recipients, body, image)
        assertTrue("insertSent: $result", result is SmsRepository.WriteResult.Success)
        val raw = (result as SmsRepository.WriteResult.Success).uri!!
        val canonical = ContentUris.withAppendedId(
            Telephony.Mms.CONTENT_URI,
            ContentUris.parseId(raw),
        )
        inserted += canonical
        return canonical
    }

    private fun outboundJpeg(
        payload: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02),
    ): MmsSendComposer.OutboundImage {
        val type = "image/jpeg"
        return MmsSendComposer.OutboundImage(
            contentType = type,
            bytes = payload,
            location = MmsImageLadder.locationFor(type),
        )
    }

    private fun textOnly(uri: Uri): Int =
        context.contentResolver.query(uri, arrayOf(Telephony.Mms.TEXT_ONLY), null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            c.getInt(0)
        }

    private fun partBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun partText(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(Telephony.Mms.Part.TEXT), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            c.getString(0)
        }

    private fun partLocation(msgId: Long, seq: Int): String? {
        return context.contentResolver.query(
            Uri.parse("content://mms/$msgId/part"),
            arrayOf(
                Telephony.Mms.Part.SEQ,
                Telephony.Mms.Part.CONTENT_LOCATION,
                Telephony.Mms.Part.NAME,
            ),
            null,
            null,
            null,
        )?.use { c ->
            val seqIdx = c.getColumnIndexOrThrow(Telephony.Mms.Part.SEQ)
            val locIdx = c.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_LOCATION)
            val nameIdx = c.getColumnIndexOrThrow(Telephony.Mms.Part.NAME)
            while (c.moveToNext()) {
                if (c.getInt(seqIdx) != seq) continue
                return@use c.getString(locIdx) ?: c.getString(nameIdx)
            }
            null
        }
    }
}
