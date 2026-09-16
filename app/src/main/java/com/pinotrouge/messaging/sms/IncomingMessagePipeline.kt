package com.pinotrouge.messaging.sms

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.pinotrouge.messaging.data.prefs.AppSettings
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.room.BlockedSenderDao
import com.pinotrouge.messaging.data.room.BlockedSenderEntity
import com.pinotrouge.messaging.data.room.TransportKind
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MmsPart
import com.pinotrouge.messaging.data.telephony.MmsPduDecoder
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.EvaluationContext
import com.pinotrouge.messaging.rules.FilterDecision
import com.pinotrouge.messaging.rules.IncomingMessage
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.RuleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Core incoming path: parse → coalesce → blocklist → evaluate → act.
 *
 * Write failures are [SmsRepository.WriteResult] values, **not** exceptions.
 * Callers must check the result: discarding it is how messages were lost
 * silently while notifications still fired.
 *
 * On any path where the message is not persisted to Telephony or Room, do
 * **not** notify. Prefer holding in quarantine over dropping.
 *
 * SMS and MMS share this pipeline. MMS persists via [MmsRepository]; the
 * filter still sees [IncomingMessage.sender] as the **originator** (Filter
 * Rule Spec §1). [EvaluationContext.isKnownContact] is true if **any**
 * participant is a saved contact.
 */
@Singleton
class IncomingMessagePipeline @Inject constructor(
    private val ruleEngine: RuleEngine,
    private val ruleRepository: RuleRepository,
    private val quarantineRepository: QuarantineRepository,
    private val smsRepository: SmsRepository,
    private val mmsRepository: MmsRepository,
    private val contactsRepository: ContactsRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper,
    private val blockedSenderDao: BlockedSenderDao,
    private val mmsTransport: MmsTransport,
    @param:ApplicationContext private val context: Context,
) {
    private val orphansSwept = AtomicBoolean(false)
    private val _downloadFailures = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val downloadFailures: SharedFlow<Long> = _downloadFailures.asSharedFlow()

    suspend fun handleDeliverIntent(intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        handleParts(parts)
    }

    suspend fun handleParts(parts: Array<SmsMessage>) {
        val coalesced = coalesce(parts) ?: return
        try {
            process(coalesced)
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline process threw before persistence; delivering unfiltered", t)
            deliverUnfiltered(coalesced)
        }
    }

    /**
     * MMS entry from [WapPushDeliverReceiver] / [MmsDownloadReceiver] for a
     * retrieved message. Same discipline as SMS: process, and on any throw
     * deliver unfiltered into the MMS provider.
     *
     * An `M-Notification.ind` must not reach here. If it does, return without
     * evaluating or notifying — we have not read the message yet.
     */
    suspend fun handleMms(message: IncomingMms) {
        if (!shouldEvaluate(message)) {
            Log.w(TAG, "handleMms received a notification; skipping evaluate/notify")
            return
        }
        sweepOrphansOnce()
        val coalesced = message.toCoalesced()
        try {
            process(coalesced)
        } catch (t: Throwable) {
            Log.e(TAG, "MMS pipeline process threw before persistence; delivering unfiltered", t)
            deliverUnfiltered(coalesced)
        }
    }

    /**
     * Park an `M-Notification.ind`: write the 130 stub, then either start a
     * carrier download or leave the Chats row as **Download**. Never evaluate
     * rules and never notify — the PDU has no content to judge.
     */
    suspend fun handleMmsNotification(
        message: IncomingMms,
        sourceIntent: Intent? = null,
    ) {
        sweepOrphansOnce()
        val stubId = when (val stub = mmsRepository.insertNotificationStub(message)) {
            is SmsRepository.WriteResult.Success ->
                stub.uri?.let { ContentUris.parseId(it) } ?: 0L
            is SmsRepository.WriteResult.RoleNotHeld,
            is SmsRepository.WriteResult.Failed,
            -> {
                logWriteFailure("insertNotificationStub", stub)
                return
            }
        }
        if (!autoDownloadEnabled(sourceIntent)) {
            Log.i(TAG, "Auto-download off; notification stub left in Chats")
            return
        }
        val location = message.contentLocation
        if (location.isNullOrBlank()) {
            Log.e(TAG, "Notification has no Content-Location; stub left undownloaded")
            return
        }
        startDownload(stubId, message, location, sourceIntent)
    }

    /**
     * User tapped **Download** on an undownloaded row. Starts the same
     * carrier fetch as auto-download; rules run when [MmsDownloadReceiver]
     * delivers the retrieve-conf. 17.3 owns the tile that calls this.
     */
    suspend fun downloadPending(mmsId: Long) {
        val message = mmsRepository.notificationFor(mmsId) ?: return
        val location = message.contentLocation
        if (location.isNullOrBlank()) {
            Log.e(TAG, "downloadPending: stub $mmsId has no Content-Location")
            return
        }
        startDownload(mmsId, message, location, null)
    }

    /**
     * The retrieve came back and did not work (non-OK result, empty file,
     * or unparseable PDU). Releases the in-flight claim so *Try again* can
     * start another retrieve. Does not restore the 30-second timeout.
     */
    fun notifyDownloadFailed(mmsId: Long) {
        if (mmsId <= 0L) return
        MmsDownloadClaims.markFailed(mmsId)
        _downloadFailures.tryEmit(mmsId)
    }

    /**
     * Compose and send `M-NotifyResp.ind` after retrieve + hold/provider are
     * durable. Bytes go to cache `mms_send/`, never `held_media/`.
     */
    fun sendNotifyResp(transactionId: String?, sourceIntent: Intent? = null) {
        if (transactionId.isNullOrBlank()) return
        var granted: Uri? = null
        var pduFile: File? = null
        try {
            val pdu = mmsRepository.writeNotifyRespFile(transactionId)
            pduFile = pdu.file
            granted = pdu.uri
            val code = notifyRespSeq.incrementAndGet()
            val sent = PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, MmsDownloadReceiver::class.java).apply {
                    action = MmsDownloadReceiver.ACTION_NOTIFYRESP_SENT
                    identifier = "notifyresp-$code"
                    putExtra(MmsDownloadReceiver.EXTRA_CONTENT_URI, pdu.uri.toString())
                    putExtra(MmsDownloadReceiver.EXTRA_FILE_PATH, pdu.file.absolutePath)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            PlatformMmsTransport.grantPduAccess(
                context,
                pdu.uri,
                PlatformMmsTransport.PDU_READ_FLAGS,
            )
            mmsTransport.send(pdu.uri, sent, sourceIntent)
        } catch (t: Throwable) {
            granted?.let {
                PlatformMmsTransport.revokePduAccess(
                    context,
                    it,
                    PlatformMmsTransport.PDU_READ_FLAGS,
                )
            }
            pduFile?.let { runCatching { it.delete() } }
            Log.e(TAG, "M-NotifyResp.ind send failed", t)
        }
    }

    suspend fun deliverUnfiltered(message: CoalescedSms) {
        val result = deliverToProvider(message, read = false)
        when (result) {
            is SmsRepository.WriteResult.Success -> {
                notificationHelper.notifyIncoming(
                    sender = message.sender,
                    body = message.body.ifBlank { message.subject.orEmpty() }.ifBlank { "(MMS)" },
                    silent = false,
                )
            }
            is SmsRepository.WriteResult.RoleNotHeld,
            is SmsRepository.WriteResult.Failed,
            -> {
                logWriteFailure("deliverUnfiltered", result)
                retainUnfiled(message, REASON_COULD_NOT_BE_FILED)
            }
        }
    }

    private suspend fun sweepOrphansOnce() {
        if (!orphansSwept.compareAndSet(false, true)) return
        runCatching { quarantineRepository.sweepOrphans() }
            .onFailure { Log.w(TAG, "held_media orphan sweep failed", it) }
    }

    private suspend fun autoDownloadEnabled(sourceIntent: Intent?): Boolean {
        val settings = settingsRepository.settings.first()
        val roaming = isRoaming(subscriptionIdFromIntent(sourceIntent))
        return shouldAutoDownload(
            autoDownloadPictures = autoDownloadPicturesOf(settings),
            downloadWhileRoaming = downloadWhileRoamingOf(settings),
            roaming = roaming,
        )
    }

    private fun isRoaming(subscriptionId: Int?): Boolean {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
        val scoped = if (subscriptionId != null) {
            tm.createForSubscriptionId(subscriptionId)
        } else {
            tm
        }
        return scoped.isNetworkRoaming
    }

    private fun startDownload(
        mmsId: Long,
        message: IncomingMms,
        location: String,
        sourceIntent: Intent?,
    ) {
        var granted: Uri? = null
        var targetFile: File? = null
        try {
            val target = mmsRepository.createDownloadTarget()
            targetFile = target.file
            granted = target.uri
            val complete = Intent(context, MmsDownloadReceiver::class.java).apply {
                action = MmsDownloadReceiver.ACTION_DOWNLOADED
                putExtra(MmsDownloadReceiver.EXTRA_ORIGINATOR, message.originator)
                putExtra(MmsDownloadReceiver.EXTRA_RECEIVED_AT, message.receivedAtMillis)
                putExtra(MmsDownloadReceiver.EXTRA_CONTENT_URI, target.uri.toString())
                putExtra(MmsDownloadReceiver.EXTRA_FILE_PATH, target.file.absolutePath)
                putExtra(MmsDownloadReceiver.EXTRA_TRANSACTION_ID, message.transactionId)
                putExtra(MmsDownloadReceiver.EXTRA_CONTENT_LOCATION, message.contentLocation)
                putExtra(MmsDownloadReceiver.EXTRA_MMS_ID, mmsId)
                val subId = subscriptionIdFromIntent(sourceIntent)
                if (subId != null) {
                    putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId)
                }
            }
            // Claim here so auto-download and a tile tap share one gate.
            // False is the UI path, which already claimed — do not abort.
            if (mmsId > 0L) MmsDownloadClaims.claim(mmsId)
            val pi = MmsDownloadCompletions.getBroadcast(context, complete, mmsId)
            PlatformMmsTransport.grantPduAccess(
                context,
                target.uri,
                PlatformMmsTransport.PDU_DOWNLOAD_FLAGS,
            )
            mmsTransport.download(location, target.uri, pi, sourceIntent)
            Log.i(TAG, "Requested MMS download for $location")
        } catch (t: Throwable) {
            granted?.let {
                PlatformMmsTransport.revokePduAccess(
                    context,
                    it,
                    PlatformMmsTransport.PDU_DOWNLOAD_FLAGS,
                )
            }
            targetFile?.let { runCatching { it.delete() } }
            Log.e(TAG, "downloadMultimediaMessage failed; leaving 130 stub", t)
            notifyDownloadFailed(mmsId)
        }
    }

    private suspend fun process(message: CoalescedSms) {
        sweepOrphansOnce()
        // Blocked senders are held, not destroyed — "Nothing is deleted behind
        // your back." Restore remains available on the Filtered screen.
        // Block is by **originator** (Filter Rule Spec §1).
        if (quarantineRepository.isBlocked(message.sender)) {
            retainUnfiled(message, REASON_BLOCKED_SENDER)
            return
        }

        val settings = settingsRepository.settings.first()
        val isKnown = resolveIsKnownContact(message.resolvedParticipants())
        val context = EvaluationContext(
            isKnownContact = isKnown,
            neverFilterContacts = settings.neverFilterContacts,
            zone = ZoneId.systemDefault(),
        )
        val rules = ruleRepository.getRules()
        val incoming = toEngineMessage(message)

        val decision = ruleEngine.evaluate(incoming, rules, context)
        applyDecision(
            message = message,
            decision = decision,
            effects = ProductionEffects(
                smsRepository = smsRepository,
                mmsRepository = mmsRepository,
                quarantineRepository = quarantineRepository,
                notificationHelper = notificationHelper,
                blockedSenderDao = blockedSenderDao,
                ruleRepository = ruleRepository,
            ),
        )
    }

    /**
     * Filter Rule Spec §1: true if **any** of [addresses] is a saved contact.
     * SMS passes a one-element list (the originator); MMS passes the full set.
     */
    suspend fun resolveIsKnownContact(addresses: List<String>): Boolean {
        if (addresses.isEmpty()) return false
        for (address in addresses) {
            if (address.isNotBlank() && contactsRepository.isKnownContact(address)) {
                return true
            }
        }
        return false
    }

    private suspend fun deliverToProvider(
        message: CoalescedSms,
        read: Boolean,
    ): SmsRepository.WriteResult {
        return if (message.isMms) {
            mmsRepository.insertInbox(message.toIncomingMms(), read = read)
        } else {
            smsRepository.insertInbox(
                address = message.sender,
                body = message.body,
                dateMillis = message.receivedAtMillis,
                read = read,
            )
        }
    }

    private suspend fun retainUnfiled(message: CoalescedSms, reason: String) {
        holdMessage(
            message = message,
            ruleId = RULE_ID_SYSTEM,
            reason = reason,
            deleteAfterDays = Rule.DEFAULT_RETENTION_DAYS,
        )
    }

    private suspend fun holdMessage(
        message: CoalescedSms,
        ruleId: String,
        reason: String,
        deleteAfterDays: Int,
    ) {
        quarantineRepository.holdOnArrival(
            sender = message.sender,
            body = message.body,
            receivedAtMillis = message.receivedAtMillis,
            ruleId = ruleId,
            reason = reason,
            deleteAfterDays = deleteAfterDays,
            parts = message.parts,
            subject = message.subject,
            participants = message.resolvedParticipants(),
            transportKind = if (message.isMms) TransportKind.MMS else TransportKind.SMS,
            transactionId = message.transactionId,
            contentLocation = message.contentLocation,
            subscriptionId = message.subscriptionId,
        )
        if (message.isMms) {
            mmsRepository.deleteNotificationStub(message.transactionId, message.contentLocation)
        }
    }

    companion object {
        private const val TAG = "IncomingMessagePipeline"
        private val notifyRespSeq = AtomicInteger(1)

        const val REASON_COULD_NOT_BE_FILED = "Could not be filed"
        const val REASON_BLOCKED_SENDER = "Blocked sender"
        const val RULE_ID_SYSTEM = "system"

        fun coalesce(parts: Array<SmsMessage>): CoalescedSms? {
            if (parts.isEmpty()) return null
            val sender = parts.first().displayOriginatingAddress
                ?: parts.first().originatingAddress
                ?: return null
            val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
            val timestamp = parts.maxOf { it.timestampMillis }
            return CoalescedSms(
                sender = sender,
                body = body,
                receivedAtMillis = if (timestamp > 0L) timestamp else System.currentTimeMillis(),
                participantAddresses = listOf(sender),
                isMms = false,
            )
        }

        /**
         * Pure decision application — unit-tested with recording [PipelineEffects].
         *
         * Once either Room or Telephony has durably accepted the message, it is never
         * delivered again. [PipelineEffects.hold] and [PipelineEffects.deliverInbox]
         * are the persistence step; a throw there still reaches the outer fallback
         * in handleParts / handleMms. [PipelineEffects.notify], [PipelineEffects.block]
         * and [PipelineEffects.recordCatch] are best-effort: a throw is logged and
         * swallowed here so it cannot trigger that fallback.
         *
         * This is a narrowing of the fail-safe from fix/silent-message-loss, not a
         * removal of it. A throw *before* persistence must still deliver unfiltered.
         *
         * [PipelineEffects.deliverInbox] returns a [SmsRepository.WriteResult].
         * Notify only after Success; on failure retain the message without notifying.
         */
        suspend fun applyDecision(
            message: CoalescedSms,
            decision: FilterDecision,
            effects: PipelineEffects,
        ) {
            suspend fun bestEffort(what: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    try {
                        Log.e(
                            TAG,
                            "Post-persistence $what threw; message is safe, not re-delivering",
                            t,
                        )
                    } catch (_: RuntimeException) {
                        // android.util.Log is unmocked in JVM unit tests.
                    }
                }
            }

            when (decision) {
                FilterDecision.Allow -> {
                    when (val result = effects.deliverInbox(message, read = false)) {
                        is SmsRepository.WriteResult.Success -> {
                            bestEffort("notify") { effects.notify(message, silent = false) }
                        }
                        is SmsRepository.WriteResult.RoleNotHeld,
                        is SmsRepository.WriteResult.Failed,
                        -> {
                            effects.onWriteFailure(message, result)
                        }
                    }
                }
                is FilterDecision.Matched -> {
                    val rule = decision.rule
                    val actions = rule.actions
                    val hold = Action.HOLD in actions
                    val silence = Action.SILENCE in actions
                    val block = Action.BLOCK in actions
                    val read = Action.READ in actions
                    // Action.REPLY is never acted on (fix/remove-auto-reply) —
                    // replying to spam confirms a live number. Data may still
                    // exist on saved rules; it is ignored here.

                    if (hold) {
                        effects.hold(message, rule, decision.reason)
                        // No notification for held messages.
                    } else {
                        when (val result = effects.deliverInbox(message, read = read)) {
                            is SmsRepository.WriteResult.Success -> {
                                bestEffort("notify") {
                                    effects.notify(message, silent = silence)
                                }
                            }
                            is SmsRepository.WriteResult.RoleNotHeld,
                            is SmsRepository.WriteResult.Failed,
                            -> {
                                effects.onWriteFailure(message, result)
                            }
                        }
                    }

                    if (block) {
                        bestEffort("block") { effects.block(message.sender) }
                    }
                    bestEffort("recordCatch") {
                        effects.recordCatch(rule.id, message.receivedAtMillis)
                    }
                }
            }
        }

        fun evaluate(
            engine: RuleEngine,
            message: CoalescedSms,
            rules: List<Rule>,
            context: EvaluationContext,
        ): FilterDecision {
            return engine.evaluate(
                toEngineMessage(message),
                rules,
                context,
            )
        }

        /**
         * `hasAnyPart` asks the weaker question — *is there content the body
         * operators cannot see?* — so it must also be true for an MMS we have
         * not decoded. `MmsPduDecoder` hardcodes `hasAttachment = false` on
         * every `M-Notification.ind`, so without `|| isMms` an undownloaded
         * picture message reaches the engine looking like an empty SMS and
         * `DOES_NOT_CONTAIN` matches it vacuously.
         *
         * `hasPhoto` comes from [CoalescedSms.hasPhoto] / [CoalescedSms.hasAttachment].
         * Decision 8 is now settled (rules never run on a notification), but
         * this floor stays so a leaked notification still cannot match HAS_PHOTO.
         */
        fun toEngineMessage(message: CoalescedSms): IncomingMessage = IncomingMessage(
            sender = message.sender,
            body = message.body,
            receivedAt = Instant.ofEpochMilli(message.receivedAtMillis),
            hasPhoto = message.hasPhoto || message.hasAttachment,
            hasAnyPart = message.hasAttachment || message.isMms,
        )

        fun shouldEvaluate(message: IncomingMms): Boolean =
            message.messageType != MmsPduDecoder.TYPE_NOTIFICATION_IND

        /**
         * Auto-download default on, roaming off. [AppSettings] field names are
         * owned by the parallel settings row; if they are not on the type yet
         * the product default still downloads on Wi-Fi / home network.
         */
        fun shouldAutoDownload(
            autoDownloadPictures: Boolean,
            downloadWhileRoaming: Boolean,
            roaming: Boolean,
        ): Boolean {
            if (!autoDownloadPictures) return false
            if (roaming && !downloadWhileRoaming) return false
            return true
        }

        internal fun autoDownloadPicturesOf(settings: AppSettings): Boolean =
            settings.autoDownloadPictures

        internal fun downloadWhileRoamingOf(settings: AppSettings): Boolean =
            settings.downloadWhileRoaming

        private fun logWriteFailure(where: String, result: SmsRepository.WriteResult) {
            when (result) {
                is SmsRepository.WriteResult.RoleNotHeld ->
                    Log.e(TAG, "$where: ROLE_SMS not held; message retained in quarantine")
                is SmsRepository.WriteResult.Failed ->
                    Log.e(TAG, "$where: provider write failed; message retained", result.cause)
                is SmsRepository.WriteResult.Success -> Unit
            }
        }
    }
}

data class CoalescedSms(
    val sender: String,
    val body: String,
    val receivedAtMillis: Long,
    /**
     * Addresses that contribute to the contacts short-circuit.
     * SMS: just the originator. MMS: originator + other participants.
     */
    val participantAddresses: List<String> = emptyList(),
    val isMms: Boolean = false,
    val subject: String? = null,
    val hasAttachment: Boolean = false,
    val hasPhoto: Boolean = hasAttachment,
    val hasAnyPart: Boolean = hasAttachment,
    val parts: List<MmsPart> = emptyList(),
    val messageType: Int? = null,
    val transactionId: String? = null,
    val contentLocation: String? = null,
    val expiryMillis: Long? = null,
    val subscriptionId: Int? = null,
) {
    fun resolvedParticipants(): List<String> =
        (if (participantAddresses.isEmpty()) listOf(sender) else participantAddresses)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

private fun IncomingMms.toCoalesced(): CoalescedSms = CoalescedSms(
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

private fun CoalescedSms.toIncomingMms(): IncomingMms = IncomingMms(
    originator = sender,
    participants = resolvedParticipants().filter { it != sender },
    body = body,
    subject = subject,
    receivedAtMillis = receivedAtMillis,
    hasPhoto = hasPhoto,
    hasAnyPart = hasAnyPart,
    hasAttachment = hasAttachment,
    transactionId = transactionId,
    contentLocation = contentLocation,
    expiryMillis = expiryMillis,
    messageType = messageType ?: MmsPduDecoder.TYPE_RETRIEVE_CONF,
    parts = parts,
    subscriptionId = subscriptionId,
)

private fun subscriptionIdFromIntent(intent: Intent?): Int? {
    if (intent == null) return null
    if (!intent.hasExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)) return null
    val value = intent.getIntExtra(
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
        SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    )
    return value.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
}

/** Side effects of the pipeline — production or recording fakes in tests. */
interface PipelineEffects {
    /** Persist to Telephony inbox. Caller must branch on the result before notifying. */
    suspend fun deliverInbox(message: CoalescedSms, read: Boolean): SmsRepository.WriteResult

    suspend fun hold(message: CoalescedSms, rule: Rule, reason: String)

    /**
     * Retain a message that could not be filed (or must not be dropped) in
     * quarantine so it surfaces on Filtered.
     */
    suspend fun retainUnfiled(message: CoalescedSms, reason: String)

    suspend fun notify(message: CoalescedSms, silent: Boolean)
    suspend fun block(sender: String)
    suspend fun recordCatch(ruleId: String, atMillis: Long)

    suspend fun onWriteFailure(message: CoalescedSms, result: SmsRepository.WriteResult) {
        retainUnfiled(message, IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED)
    }
}

private class ProductionEffects(
    private val smsRepository: SmsRepository,
    private val mmsRepository: MmsRepository,
    private val quarantineRepository: QuarantineRepository,
    private val notificationHelper: NotificationHelper,
    private val blockedSenderDao: BlockedSenderDao,
    private val ruleRepository: RuleRepository,
) : PipelineEffects {

    override suspend fun deliverInbox(
        message: CoalescedSms,
        read: Boolean,
    ): SmsRepository.WriteResult {
        return if (message.isMms) {
            mmsRepository.insertInbox(message.toIncomingMms(), read = read)
        } else {
            smsRepository.insertInbox(
                address = message.sender,
                body = message.body,
                dateMillis = message.receivedAtMillis,
                read = read,
            )
        }
    }

    override suspend fun hold(message: CoalescedSms, rule: Rule, reason: String) {
        quarantineRepository.holdOnArrival(
            sender = message.sender,
            body = message.body,
            receivedAtMillis = message.receivedAtMillis,
            ruleId = rule.id,
            reason = reason,
            deleteAfterDays = rule.deleteAfterDays,
            parts = message.parts,
            subject = message.subject,
            participants = message.resolvedParticipants(),
            transportKind = if (message.isMms) TransportKind.MMS else TransportKind.SMS,
            transactionId = message.transactionId,
            contentLocation = message.contentLocation,
            subscriptionId = message.subscriptionId,
        )
        if (message.isMms) {
            mmsRepository.deleteNotificationStub(message.transactionId, message.contentLocation)
        }
    }

    override suspend fun retainUnfiled(message: CoalescedSms, reason: String) {
        quarantineRepository.holdOnArrival(
            sender = message.sender,
            body = message.body,
            receivedAtMillis = message.receivedAtMillis,
            ruleId = IncomingMessagePipeline.RULE_ID_SYSTEM,
            reason = reason,
            deleteAfterDays = Rule.DEFAULT_RETENTION_DAYS,
            parts = message.parts,
            subject = message.subject,
            participants = message.resolvedParticipants(),
            transportKind = if (message.isMms) TransportKind.MMS else TransportKind.SMS,
            transactionId = message.transactionId,
            contentLocation = message.contentLocation,
            subscriptionId = message.subscriptionId,
        )
        if (message.isMms) {
            mmsRepository.deleteNotificationStub(message.transactionId, message.contentLocation)
        }
    }

    override suspend fun notify(message: CoalescedSms, silent: Boolean) {
        val body = message.body.ifBlank { message.subject.orEmpty() }.ifBlank {
            if (message.hasAttachment) "(Photo)" else "(MMS)"
        }
        notificationHelper.notifyIncoming(message.sender, body, silent)
    }

    override suspend fun block(sender: String) {
        blockedSenderDao.upsert(
            BlockedSenderEntity(
                sender = SenderIds.normalize(sender),
                blockedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun recordCatch(ruleId: String, atMillis: Long) {
        ruleRepository.recordCatch(ruleId, atMillis)
    }

    override suspend fun onWriteFailure(
        message: CoalescedSms,
        result: SmsRepository.WriteResult,
    ) {
        when (result) {
            is SmsRepository.WriteResult.RoleNotHeld ->
                Log.e(TAG, "insertInbox: ROLE_SMS not held; retaining in quarantine")
            is SmsRepository.WriteResult.Failed ->
                Log.e(TAG, "insertInbox failed; retaining in quarantine", result.cause)
            is SmsRepository.WriteResult.Success -> Unit
        }
        // Never notify for a message that was not persisted to the provider.
        retainUnfiled(message, IncomingMessagePipeline.REASON_COULD_NOT_BE_FILED)
    }

    private companion object {
        const val TAG = "IncomingMessagePipeline"
    }
}

/**
 * Process-scoped MMS retrieve claims. A type-130 row is also the
 * not-yet-tapped state, so in-flight cannot live on the provider.
 *
 * The receiver reports failure through [IncomingMessagePipeline.notifyDownloadFailed]
 * rather than reaching into [com.pinotrouge.messaging.ui.thread.ThreadViewModel].
 */
internal object MmsDownloadClaims {
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun claim(mmsId: Long): Boolean {
        failed.remove(mmsId)
        return inFlight.add(mmsId)
    }

    fun release(mmsId: Long) {
        inFlight.remove(mmsId)
    }

    fun markFailed(mmsId: Long) {
        inFlight.remove(mmsId)
        failed.add(mmsId)
    }

    fun clearFailed(mmsId: Long) {
        failed.remove(mmsId)
    }

    fun isInFlight(mmsId: Long): Boolean = mmsId in inFlight

    fun isFailed(mmsId: Long): Boolean = mmsId in failed

    fun resetForTests() {
        inFlight.clear()
        failed.clear()
    }
}

/**
 * Completion [PendingIntent]s for an MMS retrieve.
 *
 * Request code and [Intent.identifier] are per-message, not per content
 * location. Two retrieves at one MMSC URL must not share a token —
 * [Intent.filterEquals] ignores extras, so a location-keyed request code
 * with [PendingIntent.FLAG_UPDATE_CURRENT] would let the second rewrite
 * the first's `EXTRA_MMS_ID` and target file.
 *
 * [IncomingMessagePipeline.sendNotifyResp] uses its own monotonic request
 * codes: those intents now carry the FileProvider URI and the cache path.
 */
internal object MmsDownloadCompletions {
    private val zeroStubSeq = java.util.concurrent.atomic.AtomicInteger(0)

    fun requestCode(mmsId: Long): Int =
        if (mmsId > 0L) mmsId.hashCode() else zeroStubSeq.decrementAndGet()

    fun identifier(mmsId: Long, requestCode: Int): String =
        if (mmsId > 0L) mmsId.toString() else "zero-$requestCode"

    fun getBroadcast(context: Context, complete: Intent, mmsId: Long): PendingIntent {
        val code = requestCode(mmsId)
        complete.identifier = identifier(mmsId, code)
        return PendingIntent.getBroadcast(
            context,
            code,
            complete,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun resetForTests() {
        zeroStubSeq.set(0)
    }
}
