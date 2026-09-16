package com.pinotrouge.messaging.ui.thread

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.Telephony
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.MmsSendComposer
import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.sms.SmsSender
import com.pinotrouge.messaging.ui.media.MmsEncodeResult
import com.pinotrouge.messaging.ui.media.MmsImageEncoder
import com.pinotrouge.messaging.ui.media.MmsImageLadder
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.grantSmsRoleTo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class MmsAttachSendInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        inserted.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        // Do not hand ROLE_SMS back here. Revoking it kills this process
        // ("permissions revoked") and takes the instrumentation runner with it.
    }

    @Test
    fun attachSheet_showsGalleryAndCamera_emptyThirdCell() {
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = ThreadUiState(
                            threadId = 1,
                            title = "Maya",
                            address = "+15555550100",
                            roleHeld = true,
                            loading = false,
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                        attachOpen = true,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("attach-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Gallery").assertIsDisplayed()
        composeRule.onNodeWithText("Camera").assertIsDisplayed()
        composeRule.onNodeWithTag("attach-empty", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("File").assertDoesNotExist()
        composeRule.onNodeWithText("Location").assertDoesNotExist()
    }

    @Test
    fun tooLarge_showsInComposer_beforeAnySend() {
        val copy = context.getString(R.string.mms_too_large)
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = ThreadUiState(
                            threadId = 1,
                            title = "Maya",
                            address = "+15555550100",
                            roleHeld = true,
                            loading = false,
                            tooLarge = true,
                            sendError = copy,
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("mms-too-large").assertIsDisplayed()
        composeRule.onNodeWithText(copy).assertIsDisplayed()
    }

    @Test
    fun stagedPreview_survivesRecomposition() {
        val dir = File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "stage-rot.jpg")
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        try {
            composeRule.setContent {
                PinotRougeTheme {
                    Box(Modifier.size(360.dp, 720.dp)) {
                        ThreadScreen(
                            state = ThreadUiState(
                                threadId = 1,
                                title = "Maya",
                                address = "+15555550100",
                                roleHeld = true,
                                loading = false,
                                stagedImagePath = file.absolutePath,
                            ),
                            onBack = {},
                            onOpenBuilder = {},
                            onDraftChange = {},
                            onSend = {},
                        )
                    }
                }
            }
            composeRule.onNodeWithTag("staged-preview").assertIsDisplayed()
            composeRule.onNodeWithTag("thread-attach").performClick()
            composeRule.onNodeWithTag("staged-preview").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test
    fun encoder_fitsSmallJpeg_andRefusesTinyBudget() {
        val jpeg = solidJpeg(80, 60)
        val fit = MmsImageEncoder.encode(jpeg, budgetBytes = 300_000)
        assertTrue(fit is MmsEncodeResult.Fit)
        val fitted = fit as MmsEncodeResult.Fit
        assertEquals("image/jpeg", fitted.contentType)
        assertEquals("image_0.jpg", fitted.location)
        // Re-encoded — EXIF would have been stripped; this fixture had none.
        assertTrue(fitted.bytes.size <= 300_000)

        val refused = MmsImageEncoder.encode(jpeg, budgetBytes = 32)
        assertEquals(MmsEncodeResult.TooLarge, refused)
    }

    @Test
    fun encoder_gifUnderBudget_isUnchanged() {
        val gif = "GIF89a".toByteArray() + ByteArray(40)
        val result = MmsImageEncoder.encode(gif, budgetBytes = 1_000)
        val fit = result as MmsEncodeResult.Fit
        assertEquals("image/gif", fit.contentType)
        assertTrue(gif.contentEquals(fit.bytes))
    }

    @Test
    fun encoder_gifOverBudget_isRefused() {
        val gif = "GIF89a".toByteArray() + ByteArray(500)
        assertEquals(
            MmsEncodeResult.TooLarge,
            MmsImageEncoder.encode(gif, budgetBytes = 20),
        )
    }

    @Test
    fun sendMms_reachesSent_deletesPdu_onOkSendConf() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        val conf = okSendConf("mid-test")
        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_OK,
            sendConfPdu = conf,
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse("PDU deleted in the callback, not in finally", sent.pduFile.exists())
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, messageBox(sent.messageUri))
        assertEquals("mid-test", messageId(sent.messageUri))
        assertEquals(0x80, responseStatus(sent.messageUri))
    }

    @Test
    fun sendMms_resultOk_nullConf_doesNotMarkFailed() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_OK,
            sendConfPdu = null,
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse(sent.pduFile.exists())
        assertNotEquals(Telephony.Mms.MESSAGE_BOX_FAILED, messageBox(sent.messageUri))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, messageBox(sent.messageUri))
    }

    @Test
    fun sendMms_resultOk_emptyConf_doesNotMarkFailed() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_OK,
            sendConfPdu = byteArrayOf(),
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse(sent.pduFile.exists())
        assertNotEquals(Telephony.Mms.MESSAGE_BOX_FAILED, messageBox(sent.messageUri))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, messageBox(sent.messageUri))
    }

    @Test
    fun sendMms_nonOkResponseStatus_marksFailed() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        val conf = byteArrayOf(
            0x8C.toByte(), 0x81.toByte(),
            0x92.toByte(), 0x81.toByte(),
        )
        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_OK,
            sendConfPdu = conf,
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse(sent.pduFile.exists())
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, messageBox(sent.messageUri))
    }

    @Test
    fun sendMms_nonOkResultCode_marksFailed() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_CANCELED,
            sendConfPdu = null,
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse(sent.pduFile.exists())
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, messageBox(sent.messageUri))
    }

    @Test
    fun sendMms_timeout_doesNotMarkFailed_lateConfStillCorrects() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        sent.sender.onSendTimedOut(
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse(sent.pduFile.exists())
        assertNotEquals(Telephony.Mms.MESSAGE_BOX_FAILED, messageBox(sent.messageUri))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, messageBox(sent.messageUri))

        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_OK,
            sendConfPdu = okSendConf("mid-late"),
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, messageBox(sent.messageUri))
        assertEquals("mid-late", messageId(sent.messageUri))
        assertEquals(0x80, responseStatus(sent.messageUri))
    }

    @Test
    fun sendMms_resultOk_truncatedConf_doesNotMarkFailed() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_OK,
            sendConfPdu = byteArrayOf(0x8C.toByte()),
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse(sent.pduFile.exists())
        assertNotEquals(Telephony.Mms.MESSAGE_BOX_FAILED, messageBox(sent.messageUri))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, messageBox(sent.messageUri))
    }

    @Test
    fun sendMms_resultOk_contentTypeFirstConf_doesNotMarkFailed() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        sent.sender.onSendComplete(
            resultCode = Activity.RESULT_OK,
            sendConfPdu = byteArrayOf(0x8C.toByte(), 0x81.toByte(), 0x84.toByte()),
            pduPath = sent.pduFile.absolutePath,
            messageUri = sent.messageUri,
        )
        assertFalse(sent.pduFile.exists())
        assertNotEquals(Telephony.Mms.MESSAGE_BOX_FAILED, messageBox(sent.messageUri))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, messageBox(sent.messageUri))
    }

    @Test
    fun sendMms_handsContentUri_readableThroughProvider() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        try {
            assertEquals("content", sent.pduUri.scheme)
            assertEquals(
                PlatformMmsTransport.authority(context.packageName),
                sent.pduUri.authority,
            )
            assertTrue("PDU must still exist at send", sent.pduFile.exists())
            val expected = sent.pduFile.readBytes()
            val throughHandle = context.contentResolver.openInputStream(sent.pduUri)
                ?.use { it.readBytes() }
            assertTrue(throughHandle != null)
            assertTrue(
                "The handle the MMS service is given must open the composed PDU",
                expected.contentEquals(throughHandle!!),
            )
        } finally {
            sent.sender.onSendComplete(
                resultCode = Activity.RESULT_OK,
                sendConfPdu = okSendConf("mid-uri"),
                pduPath = sent.pduFile.absolutePath,
                messageUri = sent.messageUri,
            )
        }
    }

    @Test
    fun sendMms_grantsMmsServiceThenRevokesOnComplete() = runBlocking {
        val sent = sendPreparedMms() ?: return@runBlocking
        val installed = PlatformMmsTransport.PDU_READ_GRANTEES.filter { packageInstalled(it) }
        assertTrue(
            "emulator images ship at least one MMS-service package",
            installed.isNotEmpty(),
        )
        try {
            for (pkg in installed) {
                assertEquals(
                    "grant must be in place before send: $pkg",
                    PackageManager.PERMISSION_GRANTED,
                    uriPermission(sent.pduUri, pkg),
                )
            }
        } finally {
            sent.sender.onSendComplete(
                resultCode = Activity.RESULT_OK,
                sendConfPdu = okSendConf("mid-grant"),
                pduPath = sent.pduFile.absolutePath,
                messageUri = sent.messageUri,
            )
        }
        for (pkg in installed) {
            assertEquals(
                "grant revoked in the callback: $pkg",
                PackageManager.PERMISSION_DENIED,
                uriPermission(sent.pduUri, pkg),
            )
        }
        assertFalse(sent.pduFile.exists())
    }

    @Test
    fun sendMms_withCaption_writesTextAndImageParts() = runBlocking {
        val sent = sendPreparedMms(body = "caption") ?: return@runBlocking
        try {
            assertEquals(0, queryInt(sent.messageUri, Telephony.Mms.TEXT_ONLY))
            val types = partTypes(sent.messageUri)
            assertEquals(2, types.size)
            assertTrue(types.any { it.startsWith("text/plain") })
            assertTrue(types.any { it.startsWith("image/") })
            val imagePart = imagePartUri(sent.messageUri)!!
            val stored = context.contentResolver.openInputStream(imagePart)!!.use { it.readBytes() }
            assertTrue(sent.image.bytes.contentEquals(stored))
        } finally {
            sent.sender.onSendComplete(
                resultCode = Activity.RESULT_OK,
                sendConfPdu = okSendConf("mid-parts"),
                pduPath = sent.pduFile.absolutePath,
                messageUri = sent.messageUri,
            )
        }
    }

    @Test
    fun sendMms_withoutCaption_writesImagePartOnly() = runBlocking {
        val sent = sendPreparedMms(body = "") ?: return@runBlocking
        try {
            assertEquals(0, queryInt(sent.messageUri, Telephony.Mms.TEXT_ONLY))
            val types = partTypes(sent.messageUri)
            assertEquals(listOf("image/jpeg"), types.map { it.substringBefore(';').lowercase() })
        } finally {
            sent.sender.onSendComplete(
                resultCode = Activity.RESULT_OK,
                sendConfPdu = okSendConf("mid-nocap"),
                pduPath = sent.pduFile.absolutePath,
                messageUri = sent.messageUri,
            )
        }
    }

    private data class PreparedSend(
        val sender: SmsSender,
        val pduFile: File,
        val pduUri: Uri,
        val messageUri: Uri,
        val image: MmsSendComposer.OutboundImage,
    )

    private fun sendPreparedMms(body: String = "caption"): PreparedSend? {
        val sms = SmsRepository(context)
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS required to write Mms.Sent", sms.isDefaultSmsApp())

        val transport = RecordingTransport()
        val mms = MmsRepository(context, sms)
        val sender = SmsSender(context, sms, mms, transport)
        val jpeg = solidJpeg(32, 32)
        val encoded = MmsImageEncoder.encode(jpeg, MmsImageLadder.imageBudgetBytes(300 * 1024))
            as MmsEncodeResult.Fit
        val image = MmsSendComposer.OutboundImage(
            contentType = encoded.contentType,
            bytes = encoded.bytes,
            location = encoded.location,
        )

        val submitted = runBlocking {
            sender.sendMms(
                recipients = listOf("15555550999"),
                body = body,
                image = image,
            )
        }
        assertTrue(submitted != null && submitted.threadId != 0L)
        val pduUri = requireNotNull(transport.lastPduUri)
        assertEquals("content", pduUri.scheme)
        assertEquals(PlatformMmsTransport.authority(context.packageName), pduUri.authority)
        val pduFile = File(
            File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR),
            requireNotNull(pduUri.lastPathSegment),
        )
        assertTrue("PDU must still exist until the callback", pduFile.exists())

        val messageUri = latestSentUri()
        assertTrue(messageUri != null)
        val canonical = ContentUris.withAppendedId(
            Telephony.Mms.CONTENT_URI,
            ContentUris.parseId(messageUri!!),
        )
        inserted += canonical
        return PreparedSend(sender, pduFile, pduUri, canonical, image)
    }

    private fun okSendConf(messageId: String): ByteArray =
        byteArrayOf(
            0x8C.toByte(), 0x81.toByte(),
            0x92.toByte(), 0x80.toByte(),
            0x8B.toByte(),
        ) + messageId.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private fun messageBox(uri: Uri): Int = queryInt(uri, Telephony.Mms.MESSAGE_BOX)

    private fun messageId(uri: Uri): String? = queryString(uri, Telephony.Mms.MESSAGE_ID)

    private fun responseStatus(uri: Uri): Int = queryInt(uri, Telephony.Mms.RESPONSE_STATUS)

    private fun queryInt(uri: Uri, column: String): Int {
        return context.contentResolver.query(uri, arrayOf(column), null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            c.getInt(0)
        }
    }

    private fun queryString(uri: Uri, column: String): String? {
        return context.contentResolver.query(uri, arrayOf(column), null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }
    }

    private fun partTypes(messageUri: Uri): List<String> {
        val msgId = ContentUris.parseId(messageUri)
        return context.contentResolver.query(
            Uri.parse("content://mms/$msgId/part"),
            arrayOf(Telephony.Mms.Part.CONTENT_TYPE),
            null,
            null,
            "${Telephony.Mms.Part.SEQ} ASC",
        )!!.use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0).orEmpty())
            }
        }
    }

    private fun imagePartUri(messageUri: Uri): Uri? {
        val msgId = ContentUris.parseId(messageUri)
        return context.contentResolver.query(
            Uri.parse("content://mms/$msgId/part"),
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE),
            null,
            null,
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
            val typeIdx = c.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            while (c.moveToNext()) {
                if (c.getString(typeIdx).orEmpty().startsWith("image/")) {
                    return@use ContentUris.withAppendedId(
                        Uri.parse("content://mms/part"),
                        c.getLong(idIdx),
                    )
                }
            }
            null
        }
    }

    private fun latestSentUri(): Uri? {
        return context.contentResolver.query(
            Telephony.Mms.Sent.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            null,
            null,
            "${Telephony.Mms.DATE} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            ContentUris.withAppendedId(Telephony.Mms.Sent.CONTENT_URI, c.getLong(0))
        }
    }

    private fun solidJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun packageInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun uriPermission(uri: Uri, pkg: String): Int {
        val uid = context.packageManager.getPackageUid(pkg, 0)
        return context.checkUriPermission(
            uri,
            /* pid = */ -1,
            uid,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    /**
     * Records the send without firing the real [PendingIntent] — that
     * receiver talks to the process Hilt [SmsSender], not this instance.
     */
    private class RecordingTransport : MmsTransport {
        var lastPduUri: Uri? = null

        override fun send(
            pduUri: Uri,
            sentIntent: PendingIntent,
            sourceIntent: Intent?,
        ) {
            lastPduUri = pduUri
        }

        override fun download(
            locationUrl: String,
            targetUri: Uri,
            downloadedIntent: PendingIntent,
            sourceIntent: Intent?,
        ) = Unit

        override fun carrierMaxMessageBytes(subscriptionId: Int?): Int = 300 * 1024
    }
}
