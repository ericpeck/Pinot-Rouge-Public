package com.pinotrouge.messaging.data.repo

import android.util.Log
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.data.telephony.SmsMessage
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.data.telephony.SmsThread
import com.pinotrouge.messaging.rules.BacktestSample
import com.pinotrouge.messaging.rules.IncomingMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade over Telephony for inbox/thread UI and backtest sampling.
 * Delivered message bodies live **only** in the provider — never in Room.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val smsRepository: SmsRepository,
    private val contactsRepository: ContactsRepository,
) {
    suspend fun getThreads(limit: Int = 200): List<SmsThread> =
        smsRepository.getThreads(limit)

    suspend fun getThreadsOlderThan(beforeDateMillis: Long, limit: Int = 200): List<SmsThread> =
        smsRepository.getThreadsOlderThan(beforeDateMillis, limit)

    suspend fun getThreadsByIds(ids: Collection<Long>): List<SmsThread> =
        smsRepository.getThreadsByIds(ids)

    suspend fun getMessages(
        threadId: Long,
        limit: Int = 200,
        beforeDateMillis: Long? = null,
    ): List<SmsMessage> =
        smsRepository.getMessagesForThread(threadId, limit, beforeDateMillis)

    suspend fun sendMessage(
        address: String,
        body: String,
        dateMillis: Long = System.currentTimeMillis(),
    ): SmsRepository.WriteResult {
        val result = smsRepository.insertSent(address, body, dateMillis)
        logWriteFailure("insertSent", result)
        return result
    }

    suspend fun deleteThread(threadId: Long): SmsRepository.WriteResult {
        val result = smsRepository.deleteThread(threadId)
        logWriteFailure("deleteThread", result)
        return result
    }

    suspend fun deleteMessages(refs: List<MessageRef>): SmsRepository.WriteResult {
        val result = smsRepository.deleteMessages(refs)
        logWriteFailure("deleteMessages", result)
        return result
    }

    suspend fun isDefaultSmsApp(): Boolean = smsRepository.isDefaultSmsApp()

    /**
     * Live inbox unread total for the nav badge.
     *
     * Driven by [SmsRepository.observeSmsChanges] (a ContentObserver), not a
     * timer. Same total as before: sum of per-thread unread flags from
     * [getThreads]. Debounced so a multipart delivery does not thrash.
     *
     * The inbox list can share [SmsRepository.observeSmsChanges] later for
     * the same reason — that is why the observer lives on the repository.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observeUnreadCount(threadLimit: Int = 200): Flow<Int> =
        smsRepository.observeSmsChanges()
            .debounce(SMS_CHANGE_DEBOUNCE_MS)
            .mapLatest {
                runCatching {
                    unreadBadgeTotal(smsRepository.getThreads(threadLimit))
                }.getOrDefault(0)
            }

    /**
     * Last [limit] inbox messages as [BacktestSample]s for the rule builder.
     * Default 200 is for the builder preview only — do **not** use for a full
     * inbox sweep ([sweepInboxCandidates]).
     */
    suspend fun backtestSamples(limit: Int = 200): List<BacktestSample> {
        val messages = smsRepository.getRecentInbox(limit)
        return messages.map { sms ->
            val address = sms.address.orEmpty()
            BacktestSample(
                message = IncomingMessage(
                    sender = address,
                    body = sms.body.orEmpty(),
                    receivedAt = Instant.ofEpochMilli(sms.date),
                ),
                isKnownContact = contactsRepository.isKnownContact(address),
            )
        }
    }

    /**
     * Every inbox SMS as a [SweepCandidate] for *Run filters on my inbox*.
     * Cap is deliberately high so the scanned count matches the real inbox;
     * the builder's 200 default is **not** used.
     */
    suspend fun sweepInboxCandidates(
        limit: Int = SWEEP_INBOX_LIMIT,
    ): List<SweepCandidate> {
        val messages = smsRepository.getRecentInbox(limit)
        return messages.map { sms ->
            val address = sms.address.orEmpty()
            SweepCandidate(
                providerMessageId = sms.id,
                message = IncomingMessage(
                    sender = address,
                    body = sms.body.orEmpty(),
                    receivedAt = Instant.ofEpochMilli(sms.date),
                ),
                isKnownContact = contactsRepository.isKnownContact(address),
                wasRead = sms.read,
            )
        }
    }

    private fun logWriteFailure(where: String, result: SmsRepository.WriteResult) {
        when (result) {
            is SmsRepository.WriteResult.RoleNotHeld ->
                Log.e(TAG, "$where: ROLE_SMS not held")
            is SmsRepository.WriteResult.Failed ->
                Log.e(TAG, "$where: provider write failed", result.cause)
            is SmsRepository.WriteResult.Success -> Unit
        }
    }

    companion object {
        private const val TAG = "MessageRepository"

        /** Multipart / burst provider notifications settle within this window. */
        const val SMS_CHANGE_DEBOUNCE_MS = 250L

        /** Hard cap for sweep so a pathological inbox cannot OOM the process. */
        const val SWEEP_INBOX_LIMIT = 50_000
    }
}

/**
 * One inbox row for the sweep scanner — includes the provider `_ID` so confirm
 * can delete the Telephony copy after holding, and [wasRead] so undo can restore
 * the prior read state.
 */
data class SweepCandidate(
    val providerMessageId: Long,
    val message: IncomingMessage,
    val isKnownContact: Boolean,
    val wasRead: Boolean = false,
)

/**
 * Badge total — same aggregation the shell used under polling.
 * Exposed for unit tests so the display contract cannot drift silently.
 */
fun unreadBadgeTotal(threads: List<SmsThread>): Int =
    threads.sumOf { it.unreadCount.coerceAtLeast(0) }
