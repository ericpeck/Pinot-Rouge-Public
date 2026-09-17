package com.pinotrouge.messaging.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionManager
import android.util.Log
import com.pinotrouge.messaging.data.telephony.MmsPduDecoder
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Completes an [com.pinotrouge.messaging.data.telephony.MmsTransport.download] request.
 * Parses the downloaded Retrieve-Conf PDU and runs [IncomingMessagePipeline.handleMms].
 *
 * Registered in the manifest without export — only our own [android.app.PendingIntent] fires it.
 * The platform still delivers this after the ~2-minute timeout; an empty file leaves the
 * 130 stub in place.
 */
class MmsDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NOTIFYRESP_SENT) {
            cleanupSharedPdu(context, intent)
            return
        }
        if (intent.action != ACTION_DOWNLOADED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val contentUri = intent.getStringExtra(EXTRA_CONTENT_URI)?.let(Uri::parse)
        val originatorHint = intent.getStringExtra(EXTRA_ORIGINATOR)
        val receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, System.currentTimeMillis())
        val transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
        val contentLocation = intent.getStringExtra(EXTRA_CONTENT_LOCATION)
        val mmsId = intent.getLongExtra(EXTRA_MMS_ID, 0L)
        val resultCode = resultCode
        val sourceCopy = Intent().apply {
            if (intent.hasExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)) {
                putExtra(
                    SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                    intent.getIntExtra(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                    ),
                )
            }
        }

        scope.launch(Dispatchers.IO) {
            val entry = runCatching {
                EntryPointAccessors.fromApplication(appContext, SmsEntryPoint::class.java)
            }.getOrNull()
            try {
                if (entry == null) {
                    Log.e(TAG, "MmsDownloadReceiver: entry point unavailable; leaving stub")
                    return@launch
                }
                val pipeline = entry.incomingMessagePipeline()
                fun fail(reason: String) {
                    Log.e(TAG, reason)
                    runCatching { pipeline.notifyDownloadFailed(mmsId) }
                }
                if (contentUri == null) {
                    fail("Download complete with no content URI; leaving stub")
                    return@launch
                }
                if (resultCode != android.app.Activity.RESULT_OK) {
                    fail("MMS download failed resultCode=$resultCode; leaving 130 stub")
                    return@launch
                }
                val pdu = readBytes(appContext, contentUri)
                if (pdu == null || pdu.isEmpty()) {
                    fail("Downloaded MMS file empty; leaving 130 stub")
                    return@launch
                }
                val parsed = MmsPduDecoder.toIncomingMms(pdu, receivedAt)
                if (parsed == null) {
                    fail("Could not parse retrieved PDU; leaving 130 stub")
                    return@launch
                }
                val message = parsed.copy(
                    originator = if (parsed.originator == "unknown" && !originatorHint.isNullOrBlank()) {
                        originatorHint
                    } else {
                        parsed.originator
                    },
                    transactionId = parsed.transactionId ?: transactionId,
                    // Lookup key is the URL the retrieve was made to (the
                    // notification's), not whatever the retrieve-conf echoes.
                    // Stored on the flipped row: that same notification URL
                    // when we have it; parsed only if the extra was blank.
                    contentLocation = contentLocation?.takeIf { it.isNotBlank() }
                        ?: parsed.contentLocation,
                    subscriptionId = parsed.subscriptionId ?: subscriptionIdFrom(sourceCopy),
                )
                pipeline.handleMms(message)
                pipeline.sendNotifyResp(message.transactionId, sourceCopy)
            } catch (t: Throwable) {
                Log.e(TAG, "MmsDownloadReceiver failed; leaving stub error=${t.javaClass.simpleName}")
                runCatching {
                    entry?.incomingMessagePipeline()?.notifyDownloadFailed(mmsId)
                }
            } finally {
                cleanupSharedPdu(appContext, intent)
                pending.finish()
            }
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "MmsDownloadReceiver"
        const val ACTION_DOWNLOADED = "com.pinotrouge.messaging.action.MMS_DOWNLOADED"
        const val ACTION_NOTIFYRESP_SENT = "com.pinotrouge.messaging.action.MMS_NOTIFYRESP_SENT"
        const val EXTRA_CONTENT_URI = "content_uri"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_ORIGINATOR = "originator"
        const val EXTRA_RECEIVED_AT = "received_at"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_CONTENT_LOCATION = "content_location"
        const val EXTRA_MMS_ID = "mms_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun subscriptionIdFrom(intent: Intent): Int? {
            if (!intent.hasExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)) return null
            val value = intent.getIntExtra(
                SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            )
            return value.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }

        internal fun cleanupSharedPdu(context: Context, intent: Intent) {
            val contentUri = intent.getStringExtra(EXTRA_CONTENT_URI)?.let(Uri::parse)
            val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
            contentUri?.let {
                PlatformMmsTransport.revokePduAccess(
                    context,
                    it,
                    PlatformMmsTransport.PDU_DOWNLOAD_FLAGS,
                )
            }
            filePath?.let { runCatching { File(it).delete() } }
        }
    }
}
