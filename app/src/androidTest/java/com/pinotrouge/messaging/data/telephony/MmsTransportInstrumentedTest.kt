package com.pinotrouge.messaging.data.telephony

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MmsTransportInstrumentedTest {

    private lateinit var context: Context
    private lateinit var radio: RecordingRadio
    private lateinit var transport: PlatformMmsTransport

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        radio = RecordingRadio()
        transport = PlatformMmsTransport(radio)
    }

    @Test
    fun send_forwardsUriAndPendingIntent_defaultSubscriptionWhenExtraMissing() {
        val pdu = Uri.parse("content://com.pinotrouge.messaging.fileprovider/mms_send/a.pdu")
        val pi = pendingIntent("send")
        val source = Intent()
        transport.send(pdu, pi, source)
        assertEquals(pdu, radio.lastSendUri)
        assertEquals(pi, radio.lastSendIntent)
        assertNull(radio.lastSendSub)
    }

    @Test
    fun send_threadsSubscriptionIndexWhenPresent() {
        val pdu = Uri.parse("content://com.pinotrouge.messaging.fileprovider/mms_send/b.pdu")
        val pi = pendingIntent("send-sub")
        val source = Intent().putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 7)
        transport.send(pdu, pi, source)
        assertEquals(7, radio.lastSendSub)
        assertEquals(pdu, radio.lastSendUri)
        assertEquals(pi, radio.lastSendIntent)
    }

    @Test
    fun download_forwardsUriAndPendingIntent_defaultWhenExtraMissing() {
        val target = Uri.parse("content://com.pinotrouge.messaging.fileprovider/mms_download/c.pdu")
        val pi = pendingIntent("dl")
        transport.download("http://mmsc.example/n", target, pi, sourceIntent = null)
        assertEquals("http://mmsc.example/n", radio.lastDownloadUrl)
        assertEquals(target, radio.lastDownloadTarget)
        assertEquals(pi, radio.lastDownloadIntent)
        assertNull(radio.lastDownloadSub)
    }

    @Test
    fun carrierMaxMessageBytes_threadsSubscription() {
        assertEquals(300 * 1024, transport.carrierMaxMessageBytes(null))
        radio.lastMaxSub = -1
        assertEquals(300 * 1024, transport.carrierMaxMessageBytes(3))
        assertEquals(3, radio.lastMaxSub)
    }

    @Test
    fun contentUriFor_sendCache_openInputStreamMatchesWrittenBytes() {
        val sendDir = File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
        val payload = byteArrayOf(0x8C.toByte(), 0x80.toByte(), 0x01, 0x02, 0x03)
        val sendFile = File(sendDir, "real-handle.pdu").apply { writeBytes(payload) }
        val uri = PlatformMmsTransport.contentUriFor(context, sendFile)
        assertEquals("content", uri.scheme)
        assertEquals(PlatformMmsTransport.authority(context.packageName), uri.authority)
        val read = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertNotNull(read)
        assertTrue(
            "FileProvider handle must yield the PDU bytes, not a recorded URI",
            payload.contentEquals(read!!),
        )
        sendFile.delete()
    }

    @Test
    fun contentUriFor_downloadCache_openOutputStreamThenReadMatchesWrittenBytes() {
        val handle = PlatformMmsTransport.cachePdu(
            context,
            PlatformMmsTransport.DOWNLOAD_CACHE_DIR,
            "dl-handle.pdu",
        )
        try {
            assertEquals("content", handle.uri.scheme)
            assertEquals(PlatformMmsTransport.authority(context.packageName), handle.uri.authority)
            val payload = byteArrayOf(0x8C.toByte(), 0x84.toByte(), 0x01, 0x02, 0x03)
            context.contentResolver.openOutputStream(handle.uri).use { out ->
                assertNotNull("MMS service must be able to write the download target", out)
                out!!.write(payload)
            }
            val read = context.contentResolver.openInputStream(handle.uri)?.use { it.readBytes() }
            assertNotNull(read)
            assertTrue(
                "FileProvider download handle must round-trip bytes written by another opener",
                payload.contentEquals(read!!),
            )
        } finally {
            handle.file.delete()
        }
    }

    @Test
    fun contentUriFor_notifyResp_openInputStreamMatchesWrittenBytes() {
        val payload = MmsPduDecoder.composeNotifyResp("txn-uri-open")
        val handle = PlatformMmsTransport.cachePdu(
            context,
            PlatformMmsTransport.SEND_CACHE_DIR,
            "notifyresp-handle.pdu",
            payload,
        )
        try {
            assertEquals("content", handle.uri.scheme)
            assertEquals(PlatformMmsTransport.authority(context.packageName), handle.uri.authority)
            val read = context.contentResolver.openInputStream(handle.uri)?.use { it.readBytes() }
            assertNotNull(read)
            assertTrue(
                "FileProvider NotifyResp handle must yield the ack PDU, not a recorded URI",
                payload.contentEquals(read!!),
            )
        } finally {
            handle.file.delete()
        }
    }

    @Test
    fun createDownloadTarget_and_writeNotifyRespFile_areFileProviderUris() {
        val mms = MmsRepository(context, SmsRepository(context))
        val download = mms.createDownloadTarget()
        val notify = mms.writeNotifyRespFile("txn-repo-uri")
        try {
            assertEquals("content", download.uri.scheme)
            assertEquals("content", notify.uri.scheme)
            assertEquals(PlatformMmsTransport.authority(context.packageName), download.uri.authority)
            assertEquals(PlatformMmsTransport.authority(context.packageName), notify.uri.authority)
            val written = byteArrayOf(0x11, 0x22)
            context.contentResolver.openOutputStream(download.uri)!!.use { it.write(written) }
            val downloaded = context.contentResolver.openInputStream(download.uri)!!.use { it.readBytes() }
            assertTrue(written.contentEquals(downloaded))
            val ack = context.contentResolver.openInputStream(notify.uri)!!.use { it.readBytes() }
            assertTrue(ack.isNotEmpty())
        } finally {
            download.file.delete()
            notify.file.delete()
        }
    }

    @Test
    fun fileProvider_servesSendCache_andRejectsHeldMedia() {
        val sendDir = File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
        val sendFile = File(sendDir, "probe.pdu").apply { writeBytes(byteArrayOf(0x01, 0x02)) }
        val uri = FileProvider.getUriForFile(
            context,
            PlatformMmsTransport.authority(context.packageName),
            sendFile,
        )
        assertEquals("content", uri.scheme)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertNotNull(bytes)
        assertEquals(2, bytes!!.size)

        val heldDir = File(context.filesDir, "held_media").apply { mkdirs() }
        val held = File(heldDir, "secret.jpg").apply { writeBytes(byteArrayOf(0x09)) }
        try {
            FileProvider.getUriForFile(
                context,
                PlatformMmsTransport.authority(context.packageName),
                held,
            )
            fail("held_media must not be a FileProvider path")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun pendingIntent(action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private class RecordingRadio : MmsRadio {
        var lastSendSub: Int? = null
        var lastSendUri: Uri? = null
        var lastSendIntent: PendingIntent? = null
        var lastDownloadSub: Int? = null
        var lastDownloadUrl: String? = null
        var lastDownloadTarget: Uri? = null
        var lastDownloadIntent: PendingIntent? = null

        var lastMaxSub: Int? = null

        override fun send(subscriptionId: Int?, pduUri: Uri, sentIntent: PendingIntent) {
            lastSendSub = subscriptionId
            lastSendUri = pduUri
            lastSendIntent = sentIntent
        }

        override fun download(
            subscriptionId: Int?,
            locationUrl: String,
            targetUri: Uri,
            downloadedIntent: PendingIntent,
        ) {
            lastDownloadSub = subscriptionId
            lastDownloadUrl = locationUrl
            lastDownloadTarget = targetUri
            lastDownloadIntent = downloadedIntent
        }

        override fun carrierMaxMessageBytes(subscriptionId: Int?): Int {
            lastMaxSub = subscriptionId
            return 300 * 1024
        }
    }
}
