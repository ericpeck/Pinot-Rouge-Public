package com.pinotrouge.messaging.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MmsPduDecoder
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Incoming MMS entry point. Required for ROLE_SMS eligibility — **do not remove**.
 *
 * Flow:
 * 1. `goAsync()` so work can outlive [onReceive]
 * 2. Parse the WAP push PDU ([Intent] extra `"data"`)
 * 3. M-Notification.ind → [IncomingMessagePipeline.handleMmsNotification]
 *    (stub + optional carrier download via [com.pinotrouge.messaging.data.telephony.MmsTransport]).
 *    Rules and notifications wait until the message is retrieved.
 * 4. M-Retrieve.conf already in the push → [IncomingMessagePipeline.handleMms]
 */
class WapPushDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val mime = intent.type
        if (mime != null && !mime.equals(MMS_MIME, ignoreCase = true)) {
            Log.w(TAG, "Ignoring WAP_PUSH with unexpected type=$mime")
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        val pdu = intent.getByteArrayExtra("data")
        if (pdu == null || pdu.isEmpty()) {
            Log.e(TAG, "WAP_PUSH_DELIVER with empty data; nothing to file")
            pending.finish()
            return
        }
        val pduCopy = pdu.copyOf()
        val receivedAt = System.currentTimeMillis()
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
            try {
                handlePdu(appContext, pduCopy, receivedAt, sourceCopy)
            } catch (t: Throwable) {
                Log.e(TAG, "MMS handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handlePdu(
        appContext: Context,
        pdu: ByteArray,
        receivedAt: Long,
        sourceIntent: Intent,
    ) {
        val entry = EntryPointAccessors.fromApplication(appContext, SmsEntryPoint::class.java)
        val pipeline = entry.incomingMessagePipeline()

        val type = MmsPduDecoder.messageType(pdu)
        Log.i(TAG, "WAP_PUSH MMS type=${type?.toString(16) ?: "unknown"} len=${pdu.size}")

        if (type == MmsPduDecoder.TYPE_NOTIFICATION_IND) {
            val parsed = MmsPduDecoder.toIncomingMms(pdu, receivedAt)
                ?: MmsPduDecoder.parseNotification(pdu)?.originator?.let { originator ->
                    val n = MmsPduDecoder.parseNotification(pdu)!!
                    IncomingMms(
                        originator = originator,
                        body = "",
                        subject = n.subject,
                        receivedAtMillis = receivedAt,
                        transactionId = n.transactionId,
                        contentLocation = n.contentLocation,
                        expiryMillis = n.expiryMillis,
                        messageType = MmsPduDecoder.TYPE_NOTIFICATION_IND,
                        subscriptionId = subscriptionIdFromCopied(sourceIntent),
                    )
                }
            if (parsed != null) {
                val withSub = parsed.copy(subscriptionId = parsed.subscriptionId ?: subscriptionIdFromCopied(sourceIntent))
                pipeline.handleMmsNotification(withSub, sourceIntent)
                return
            }
            val location = MmsPduDecoder.parseNotification(pdu)?.contentLocation
            if (!location.isNullOrBlank()) {
                Log.w(TAG, "Notification has Content-Location but no originator; not filing")
            } else {
                Log.e(TAG, "Could not parse M-Notification.ind; message not filed")
            }
            return
        }

        val retrieved = MmsPduDecoder.toIncomingMms(pdu, receivedAt)
        if (retrieved != null) {
            val withSub = retrieved.copy(
                subscriptionId = retrieved.subscriptionId ?: subscriptionIdFromCopied(sourceIntent),
            )
            runPipeline(pipeline, withSub)
            return
        }

        Log.e(TAG, "Could not parse MMS PDU; message not filed")
    }

    private suspend fun runPipeline(pipeline: IncomingMessagePipeline, message: IncomingMms) {
        try {
            pipeline.handleMms(message)
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline failed; delivering unfiltered", t)
            runCatching { pipeline.deliverUnfiltered(message.toCoalescedPublic()) }
        }
    }

    private companion object {
        const val TAG = "WapPushDeliverReceiver"
        const val MMS_MIME = "application/vnd.wap.mms-message"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun subscriptionIdFromCopied(intent: Intent): Int? {
            if (!intent.hasExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)) return null
            val value = intent.getIntExtra(
                SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            )
            return value.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }
    }
}

/** Shared conversion for fallback paths outside the pipeline package visibility. */
private fun IncomingMms.toCoalescedPublic(): CoalescedSms = CoalescedSms(
    sender = originator,
    body = body,
    receivedAtMillis = receivedAtMillis,
    participantAddresses = allAddresses,
    isMms = true,
    subject = subject,
    hasAttachment = hasAttachment,
    hasPhoto = hasPhoto,
    hasAnyPart = hasAnyPart,
    parts = parts,
    messageType = messageType,
    transactionId = transactionId,
    contentLocation = contentLocation,
    expiryMillis = expiryMillis,
    subscriptionId = subscriptionId,
)
