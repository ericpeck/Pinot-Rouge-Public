package com.pinotrouge.messaging.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.MmsSendComposer
import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import com.pinotrouge.messaging.data.telephony.SmsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class MmsSendVerdict {
    Sent,
    Failed,
    Unconfirmed,
}

data class MmsSendEvent(
    val messageUri: Uri?,
    val verdict: MmsSendVerdict,
) {
    /** True unless the send is a confirmed failure — so the composer does not invite a resend. */
    val ok: Boolean get() = verdict != MmsSendVerdict.Failed
}

sealed interface SmsSubmitResult {
    /** Radio was called. Do not auto-resend; the sent callback updates the row. */
    data class Submitted(val messageUri: Uri) : SmsSubmitResult

    /** Outbox insert failed or the radio was never called. Safe to retry. */
    data class FailedBeforeSubmit(val cause: Throwable? = null) : SmsSubmitResult
}

data class MmsSubmitResult(
    val threadId: Long,
    val messageUri: Uri,
)

/**
 * Outgoing SMS and MMS.
 *
 * - One-to-one text uses [SmsManager.sendTextMessage] / multipart, writes
 *   [Telephony.Sms.Outbox] first, and moves the row to Sent or Failed from
 *   the sent-result callback.
 * - Multi-recipient text, and any message with a photo, uses
 *   [MmsTransport.send] (carrier MMSC via the **platform** — this app adds
 *   no HTTP client).
 *
 * Provider writes return [SmsRepository.WriteResult]. A failed insert before
 * the radio is retry-safe; after the radio, the send still counts as attempted.
 */
@Singleton
class SmsSender(
    private val context: Context,
    private val smsRepository: SmsRepository,
    private val mmsRepository: MmsRepository,
    private val mmsTransport: MmsTransport,
    private val smsRadio: SmsRadio,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        smsRepository: SmsRepository,
        mmsRepository: MmsRepository,
        mmsTransport: MmsTransport,
    ) : this(
        context,
        smsRepository,
        mmsRepository,
        mmsTransport,
        PlatformSmsRadio(context),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, InFlight>()
    private val smsPartTrackers = ConcurrentHashMap<String, BooleanArray>()
    private val completions = MutableSharedFlow<MmsSendEvent>(extraBufferCapacity = 16)

    val sendCompletions: SharedFlow<MmsSendEvent> = completions.asSharedFlow()

    /**
     * One-to-one SMS. Inserts Outbox first; sent-result callbacks move the row.
     */
    suspend fun send(address: String, body: String): SmsSubmitResult = withContext(Dispatchers.IO) {
        if (address.isBlank() || body.isBlank()) {
            return@withContext SmsSubmitResult.FailedBeforeSubmit()
        }
        when (val inserted = smsRepository.insertOutbox(
            address = address,
            body = body,
            dateMillis = System.currentTimeMillis(),
        )) {
            is SmsRepository.WriteResult.RoleNotHeld -> {
                Log.e(TAG, "send SMS: ROLE_SMS not held")
                SmsSubmitResult.FailedBeforeSubmit()
            }
            is SmsRepository.WriteResult.Failed -> {
                Log.e(TAG, "send SMS: Outbox insert failed", inserted.cause)
                SmsSubmitResult.FailedBeforeSubmit(inserted.cause)
            }
            is SmsRepository.WriteResult.Success -> {
                val messageUri = inserted.uri
                    ?: return@withContext SmsSubmitResult.FailedBeforeSubmit(
                        IllegalStateException("SMS outbox insert returned null uri"),
                    )
                try {
                    val parts = smsRadio.divideMessage(body)
                    if (parts.isEmpty()) {
                        runCatching { context.contentResolver.delete(messageUri, null, null) }
                        return@withContext SmsSubmitResult.FailedBeforeSubmit()
                    }
                    if (parts.size == 1) {
                        smsRadio.sendTextMessage(
                            destinationAddress = address,
                            scAddress = null,
                            text = body,
                            sentIntent = smsSentIntent(messageUri, partIndex = 0, partCount = 1),
                            deliveryIntent = null,
                        )
                    } else {
                        val sentIntents = ArrayList<PendingIntent>(parts.size)
                        for (i in parts.indices) {
                            sentIntents.add(
                                smsSentIntent(messageUri, partIndex = i, partCount = parts.size),
                            )
                        }
                        smsRadio.sendMultipartTextMessage(
                            destinationAddress = address,
                            scAddress = null,
                            parts = parts,
                            sentIntents = sentIntents,
                            deliveryIntents = null,
                        )
                    }
                    SmsSubmitResult.Submitted(messageUri)
                } catch (t: Throwable) {
                    Log.e(TAG, "send SMS radio failed", t)
                    runCatching { context.contentResolver.delete(messageUri, null, null) }
                    SmsSubmitResult.FailedBeforeSubmit(t)
                }
            }
        }
    }

    /**
     * MMS via the platform MMSC. Text-only group messages, or a photo to one
     * or more recipients. [image] is already ladder-encoded — this method
     * does not compress.
     *
     * @return thread id and the outgoing message URI when the platform
     *   accepted the send; completion (Send.conf) arrives later on
     *   [sendCompletions]. Null on a local failure before the radio call.
     *   Does not delete the PDU file here.
     */
    suspend fun sendMms(
        recipients: List<String>,
        body: String,
        image: MmsSendComposer.OutboundImage? = null,
        subscriptionId: Int? = null,
    ): MmsSubmitResult? = withContext(Dispatchers.IO) {
        val addresses = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (addresses.isEmpty()) return@withContext null
        if (image == null && body.isBlank()) return@withContext null
        try {
            val pdu = MmsSendComposer.composeSendReq(addresses, body, image)
            val pduFile = writePduFile(pdu)
            val pduUri = PlatformMmsTransport.contentUriFor(context, pduFile)
            when (val result = mmsRepository.insertSent(addresses, body, image)) {
                is SmsRepository.WriteResult.Success -> {
                    val messageUri = result.uri ?: run {
                        pduFile.delete()
                        Log.e(TAG, "send MMS: Sent insert returned null uri")
                        return@withContext null
                    }
                    val sentIntent = sentPendingIntent(pduFile, messageUri)
                    val source = Intent().apply {
                        if (subscriptionId != null) {
                            putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subscriptionId)
                        }
                    }
                    val timeout = scope.launch {
                        delay(SEND_TIMEOUT_MS)
                        onSendTimedOut(
                            pduPath = pduFile.absolutePath,
                            messageUri = messageUri,
                        )
                    }
                    inFlight[pduFile.absolutePath] = InFlight(pduFile, pduUri, messageUri, timeout)
                    grantPduRead(pduUri)
                    mmsTransport.send(pduUri, sentIntent, source)
                    MmsSubmitResult(
                        threadId = mmsRepository.threadIdFor(addresses),
                        messageUri = messageUri,
                    )
                }
                is SmsRepository.WriteResult.RoleNotHeld -> {
                    pduFile.delete()
                    Log.e(TAG, "send MMS: ROLE_SMS not held")
                    null
                }
                is SmsRepository.WriteResult.Failed -> {
                    pduFile.delete()
                    Log.e(TAG, "send MMS: Sent insert failed", result.cause)
                    null
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "send MMS failed", t)
            null
        }
    }

    fun onSendComplete(
        resultCode: Int,
        sendConfPdu: ByteArray?,
        pduPath: String?,
        messageUri: Uri?,
    ) {
        completeSend(
            resultCode = resultCode,
            sendConfPdu = sendConfPdu,
            pduPath = pduPath,
            messageUri = messageUri,
            timedOut = false,
        )
    }

    /**
     * Process-scoped 120s wait with no conf. That is unknown, not failed —
     * a late conf still corrects the row via [messageUri] on the intent.
     */
    @VisibleForTesting
    fun onSendTimedOut(pduPath: String?, messageUri: Uri?) {
        completeSend(
            resultCode = Activity.RESULT_CANCELED,
            sendConfPdu = null,
            pduPath = pduPath,
            messageUri = messageUri,
            timedOut = true,
        )
    }

    private fun completeSend(
        resultCode: Int,
        sendConfPdu: ByteArray?,
        pduPath: String?,
        messageUri: Uri?,
        timedOut: Boolean,
    ) {
        val flight = pduPath?.let { inFlight.remove(it) }
        flight?.timeoutJob?.cancel()
        val confBytes = sendConfPdu
        val conf = confBytes?.let { MmsSendComposer.parseSendConf(it) }
        val verdict = when {
            timedOut -> MmsSendVerdict.Unconfirmed
            resultCode != Activity.RESULT_OK -> MmsSendVerdict.Failed
            conf == null || conf.responseStatus == null -> MmsSendVerdict.Unconfirmed
            conf.ok -> MmsSendVerdict.Sent
            else -> MmsSendVerdict.Failed
        }
        when (verdict) {
            MmsSendVerdict.Failed -> Log.e(
                TAG,
                "MMS send failed resultCode=$resultCode responseStatus=${conf?.responseStatus}",
            )
            MmsSendVerdict.Unconfirmed -> {
                val confState = when {
                    timedOut -> "timeout"
                    confBytes == null || confBytes.isEmpty() -> "missing"
                    conf?.responseStatus == null -> "unreadable"
                    else -> "unavailable"
                }
                Log.w(
                    TAG,
                    "MMS send verdict unavailable resultCode=$resultCode timedOut=$timedOut conf=$confState",
                )
            }
            MmsSendVerdict.Sent -> Unit
        }
        val uri = messageUri ?: flight?.messageUri
        if (uri != null) {
            runCatching {
                updateOutgoing(uri, verdict, conf?.messageId, conf?.responseStatus)
            }
        }
        val pduFile = flight?.pduFile ?: pduPath?.let { File(it) }
        val pduUri = flight?.pduUri ?: pduFile
            ?.takeIf { it.exists() }
            ?.let { runCatching { PlatformMmsTransport.contentUriFor(context, it) }.getOrNull() }
        pduUri?.let { revokePduRead(it) }
        pduFile?.let { runCatching { it.delete() } }
        completions.tryEmit(MmsSendEvent(uri, verdict))
    }

    private fun grantPduRead(pduUri: Uri) {
        for (pkg in PlatformMmsTransport.PDU_READ_GRANTEES) {
            runCatching {
                context.grantUriPermission(
                    pkg,
                    pduUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun revokePduRead(pduUri: Uri) {
        runCatching {
            context.revokeUriPermission(pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        for (pkg in PlatformMmsTransport.PDU_READ_GRANTEES) {
            runCatching {
                context.revokeUriPermission(
                    pkg,
                    pduUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun writePduFile(pdu: ByteArray): File {
        val dir = File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "send-${System.nanoTime()}.pdu")
        file.writeBytes(pdu)
        return file
    }

    private fun sentPendingIntent(pduFile: File, messageUri: Uri?): PendingIntent {
        val code = requestCodes.incrementAndGet()
        val complete = Intent(context, MmsSendReceiver::class.java).apply {
            action = MmsSendReceiver.ACTION_SENT
            putExtra(MmsSendReceiver.EXTRA_PDU_PATH, pduFile.absolutePath)
            if (messageUri != null) {
                putExtra(MmsSendReceiver.EXTRA_MESSAGE_URI, messageUri.toString())
            }
            identifier = "mms-send-$code"
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            complete,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun updateOutgoing(
        uri: Uri,
        verdict: MmsSendVerdict,
        messageId: String?,
        responseStatus: Int?,
    ) {
        if (verdict == MmsSendVerdict.Unconfirmed) return
        val values = ContentValues().apply {
            put(
                Telephony.Mms.MESSAGE_BOX,
                if (verdict == MmsSendVerdict.Sent) {
                    Telephony.Mms.MESSAGE_BOX_SENT
                } else {
                    Telephony.Mms.MESSAGE_BOX_FAILED
                },
            )
            if (!messageId.isNullOrBlank()) {
                put(Telephony.Mms.MESSAGE_ID, messageId)
            }
            if (responseStatus != null) {
                put(Telephony.Mms.RESPONSE_STATUS, responseStatus)
            }
        }
        context.contentResolver.update(uri, values, null, null)
    }

    /**
     * Per-part sent result. Any failure fails the whole message. Not a
     * delivery receipt.
     */
    fun onSmsPartResult(
        messageUri: Uri?,
        partIndex: Int,
        partCount: Int,
        resultCode: Int,
    ) {
        if (messageUri == null || partCount <= 0) return
        val key = messageUri.toString()
        if (resultCode != Activity.RESULT_OK) {
            smsPartTrackers.remove(key)
            updateOutgoingSms(messageUri, sent = false, errorCode = resultCode)
            return
        }
        if (partCount == 1) {
            smsPartTrackers.remove(key)
            updateOutgoingSms(messageUri, sent = true)
            return
        }
        val tracker = smsPartTrackers.getOrPut(key) { BooleanArray(partCount) }
        synchronized(tracker) {
            if (partIndex in tracker.indices) {
                tracker[partIndex] = true
            }
            if (tracker.all { it }) {
                smsPartTrackers.remove(key)
                updateOutgoingSms(messageUri, sent = true)
            }
        }
    }

    private fun smsSentIntent(messageUri: Uri, partIndex: Int, partCount: Int): PendingIntent {
        val code = requestCodes.incrementAndGet()
        val complete = Intent(context, SmsSentReceiver::class.java).apply {
            action = SmsSentReceiver.ACTION_SENT
            putExtra(SmsSentReceiver.EXTRA_MESSAGE_URI, messageUri.toString())
            putExtra(SmsSentReceiver.EXTRA_PART_INDEX, partIndex)
            putExtra(SmsSentReceiver.EXTRA_PART_COUNT, partCount)
            identifier = "sms-sent-$code"
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            complete,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun updateOutgoingSms(uri: Uri, sent: Boolean, errorCode: Int? = null) {
        val values = ContentValues().apply {
            put(
                Telephony.Sms.TYPE,
                if (sent) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED,
            )
            if (sent) {
                put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            }
            if (errorCode != null) {
                put(Telephony.Sms.ERROR_CODE, errorCode)
            }
        }
        runCatching {
            context.contentResolver.update(
                uri,
                values,
                "${Telephony.Sms.TYPE}=?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString()),
            )
        }
    }

    private data class InFlight(
        val pduFile: File,
        val pduUri: Uri,
        val messageUri: Uri?,
        val timeoutJob: Job,
    )

    private companion object {
        const val TAG = "SmsSender"
        @VisibleForTesting
        const val SEND_TIMEOUT_MS = 120_000L
        val requestCodes = AtomicInteger(1)
    }
}
