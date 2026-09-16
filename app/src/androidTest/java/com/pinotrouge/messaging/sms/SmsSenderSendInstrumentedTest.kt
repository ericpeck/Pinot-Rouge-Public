package com.pinotrouge.messaging.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.grantSmsRoleTo
import java.util.ArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsSenderSendInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sms: SmsRepository
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
    }

    @After
    fun tearDown() {
        inserted.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test
    fun send_blankBody_doesNotCallRadio() = runBlocking {
        val radio = RecordingSmsRadio()
        val sender = SmsSender(context, sms, MmsRepository(context, sms), NoopTransport(), radio)
        val result = sender.send("15555550123", "  ")
        assertTrue(result is SmsSubmitResult.FailedBeforeSubmit)
        assertEquals(0, radio.textSends)
        assertEquals(0, radio.multipartSends)
    }

    @Test
    fun send_insertsOutbox_thenSentCallbackMarksSent() = runBlocking {
        assumeTrue("ROLE_SMS required to write Outbox", sms.isDefaultSmsApp())
        val radio = RecordingSmsRadio()
        val sender = SmsSender(context, sms, MmsRepository(context, sms), NoopTransport(), radio)
        val body = "sms-sent-callback-${System.currentTimeMillis()}"
        val result = sender.send("15555550921", body)
        assertTrue("expected Submitted, got $result", result is SmsSubmitResult.Submitted)
        val uri = (result as SmsSubmitResult.Submitted).messageUri
        inserted += uri
        assertEquals(1, radio.textSends)
        assertNotNull(radio.lastSentIntent)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, queryType(uri))
        sender.onSmsPartResult(uri, partIndex = 0, partCount = 1, resultCode = Activity.RESULT_OK)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, queryType(uri))
    }

    @Test
    fun send_radioOffCallback_marksFailed() = runBlocking {
        assumeTrue("ROLE_SMS required to write Outbox", sms.isDefaultSmsApp())
        val radio = RecordingSmsRadio()
        val sender = SmsSender(context, sms, MmsRepository(context, sms), NoopTransport(), radio)
        val body = "sms-fail-callback-${System.currentTimeMillis()}"
        val result = sender.send("15555550922", body)
        assertTrue(result is SmsSubmitResult.Submitted)
        val uri = (result as SmsSubmitResult.Submitted).messageUri
        inserted += uri
        sender.onSmsPartResult(
            uri,
            partIndex = 0,
            partCount = 1,
            resultCode = SmsManager.RESULT_ERROR_RADIO_OFF,
        )
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, queryType(uri))
    }

    @Test
    fun send_multipartAnyPartFailure_failsWholeMessage() = runBlocking {
        assumeTrue("ROLE_SMS required to write Outbox", sms.isDefaultSmsApp())
        val radio = RecordingSmsRadio(parts = arrayListOf("one", "two"))
        val sender = SmsSender(context, sms, MmsRepository(context, sms), NoopTransport(), radio)
        val body = "sms-multipart-${System.currentTimeMillis()}"
        val result = sender.send("15555550923", body)
        assertTrue(result is SmsSubmitResult.Submitted)
        val uri = (result as SmsSubmitResult.Submitted).messageUri
        inserted += uri
        assertEquals(1, radio.multipartSends)
        assertEquals(2, radio.lastSentIntents?.size)
        sender.onSmsPartResult(
            uri,
            partIndex = 0,
            partCount = 2,
            resultCode = SmsManager.RESULT_ERROR_RADIO_OFF,
        )
        sender.onSmsPartResult(uri, partIndex = 1, partCount = 2, resultCode = Activity.RESULT_OK)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, queryType(uri))
    }

    private fun queryType(uri: Uri): Int {
        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return -1
    }

    private class RecordingSmsRadio(
        private val parts: ArrayList<String>? = null,
    ) : SmsRadio {
        var textSends = 0
        var multipartSends = 0
        var lastSentIntent: PendingIntent? = null
        var lastSentIntents: ArrayList<PendingIntent>? = null

        override fun divideMessage(text: String): ArrayList<String> =
            parts ?: arrayListOf(text)

        override fun sendTextMessage(
            destinationAddress: String,
            scAddress: String?,
            text: String,
            sentIntent: PendingIntent?,
            deliveryIntent: PendingIntent?,
        ) {
            textSends++
            lastSentIntent = sentIntent
        }

        override fun sendMultipartTextMessage(
            destinationAddress: String,
            scAddress: String?,
            parts: ArrayList<String>,
            sentIntents: ArrayList<PendingIntent>?,
            deliveryIntents: ArrayList<PendingIntent>?,
        ) {
            multipartSends++
            lastSentIntents = sentIntents
        }
    }

    private class NoopTransport : MmsTransport {
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
        ) = Unit

        override fun carrierMaxMessageBytes(subscriptionId: Int?): Int = 300 * 1024
    }
}
