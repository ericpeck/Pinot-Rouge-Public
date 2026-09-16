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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MmsSentOrphanInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sms: SmsRepository
    private lateinit var mms: MmsRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        mms = MmsRepository(context, sms)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS required to write Mms.Sent", sms.isDefaultSmsApp())
        MmsRepository.onImagePartWrite = null
    }

    @After
    fun tearDown() {
        MmsRepository.onImagePartWrite = null
    }

    @Test
    fun insertSent_partWriteFailure_leavesNoRowOrAddrOrPart() = runBlocking {
        val recipients = listOf("1555555${(System.nanoTime() % 10_000).toString().padStart(4, '0')}")
        val cause = IOException("forced part write failure")
        var msgId = -1L
        MmsRepository.onImagePartWrite = { id, _ ->
            msgId = id
            throw cause
        }
        val result = mms.insertSent(
            recipients = recipients,
            body = "",
            image = outboundJpeg(),
        )
        assertTrue("insertSent must still return Failed: $result", result is SmsRepository.WriteResult.Failed)
        assertSame(cause, (result as SmsRepository.WriteResult.Failed).cause)
        assertTrue("part write must have started so addr rows existed to roll back", msgId > 0L)

        val threadId = mms.threadIdFor(recipients)
        assertEquals(0, sentRowsForThread(threadId))
        assertEquals(0, childCount("content://mms/$msgId/addr"))
        assertEquals(0, childCount("content://mms/$msgId/part"))
        assertEquals(
            0,
            context.contentResolver.query(
                ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, msgId),
                arrayOf(Telephony.Mms._ID),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) 1 else 0 } ?: 0,
        )
    }

    private fun outboundJpeg(): MmsSendComposer.OutboundImage {
        val type = "image/jpeg"
        return MmsSendComposer.OutboundImage(
            contentType = type,
            bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01),
            location = MmsImageLadder.locationFor(type),
        )
    }

    private fun sentRowsForThread(threadId: Long): Int {
        return context.contentResolver.query(
            Telephony.Mms.Sent.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            null,
        )?.use { it.count } ?: 0
    }

    private fun childCount(uri: String): Int {
        return context.contentResolver.query(Uri.parse(uri), arrayOf("_id"), null, null, null)
            ?.use { it.count } ?: 0
    }
}
