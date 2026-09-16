package com.pinotrouge.messaging.data.telephony

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.pinotrouge.messaging.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SmsThread(
    val threadId: Long,
    val address: String?,
    val snippet: String?,
    val date: Long,
    val messageCount: Int,
    val unreadCount: Int,
    /**
     * Other parties on the thread, resolved from [Telephony.Threads.RECIPIENT_IDS]
     * via the canonical-addresses table. Size ≥ 2 means a group; empty when the
     * provider omits recipients. Does not include the user ("you").
     */
    val participantAddresses: List<String> = emptyList(),
) {
    val isGroup: Boolean get() = participantAddresses.size > 1
}

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String?,
    val body: String?,
    val date: Long,
    val type: Int,
    val read: Boolean,
    val kind: MessageRef.Kind = MessageRef.Kind.SMS,
    /** MMS `SUBJECT` column; null for SMS and when the PDU has none. */
    val subject: String? = null,
    /**
     * MMS `TEXT_ONLY` (1 = no non-text part). SMS is always text-only.
     * Drives the 17.1 placeholder tile; 17.3 replaces it with real bytes.
     */
    val textOnly: Boolean = true,
) {
    val ref: MessageRef get() = MessageRef(kind, id)
}

/**
 * One matching message body for Search. Callers collapse to the newest hit
 * per [threadId]; [SmsRepository.searchMessageBodies] already does that.
 */
data class ThreadBodyHit(
    val threadId: Long,
    val body: String,
    val dateMillis: Long,
)

/**
 * Read/write Android's Telephony SMS provider.
 *
 * Writes are only permitted when we hold ROLE_SMS. Callers should treat
 * [WriteResult.RoleNotHeld] as inert preview mode, not a crash.
 */
@Singleton
class SmsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    sealed interface WriteResult {
        data class Success(val uri: Uri?) : WriteResult
        data object RoleNotHeld : WriteResult
        data class Failed(val cause: Throwable) : WriteResult
    }

    suspend fun getThreads(limit: Int = 200): List<SmsThread> = withContext(Dispatchers.IO) {
        queryThreads(limit = limit, beforeDateMillis = null)
    }

    /**
     * Next older page of conversations: [Telephony.Threads.DATE] strictly
     * before [beforeDateMillis], newest first. Used by Chats load-more.
     */
    suspend fun getThreadsOlderThan(
        beforeDateMillis: Long,
        limit: Int = 200,
    ): List<SmsThread> = withContext(Dispatchers.IO) {
        queryThreads(limit = limit, beforeDateMillis = beforeDateMillis)
    }

    /**
     * Resolve specific threads by id. Archive uses this so a filed
     * conversation is not dropped just because it is older than the newest
     * 500 provider rows.
     *
     * `Threads.CONTENT_URI?simple=true` reads the `threads` table. AOSP
     * hard-codes `date DESC` and ignores caller sort. Capping that cursor at
     * `ids.size` is a newest-N slice if `_ID` selection is ignored — keep a
     * row only when `_id` matches, otherwise resolve via SMS/MMS `THREAD_ID`.
     */
    suspend fun getThreadsByIds(ids: Collection<Long>): List<SmsThread> =
        withContext(Dispatchers.IO) {
            if (!hasReadSmsPermission() || ids.isEmpty()) return@withContext emptyList()
            ids.distinct().mapNotNull { id ->
                queryThreads(
                    limit = 1,
                    beforeDateMillis = null,
                    selection = "${Telephony.Threads._ID} = ?",
                    selectionArgs = arrayOf(id.toString()),
                ).firstOrNull()?.takeIf { it.threadId == id }
                    ?: threadFromMessageTables(id)
            }.sortedByDescending { it.date }
        }

    private fun queryThreads(
        limit: Int,
        beforeDateMillis: Long?,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<SmsThread> {
        if (!hasReadSmsPermission() || limit <= 0) return emptyList()
        val uri = Telephony.Threads.CONTENT_URI.buildUpon()
            .appendQueryParameter("simple", "true")
            .build()
        val (sel, args) = when {
            selection != null -> selection to selectionArgs
            beforeDateMillis != null ->
                "${Telephony.Threads.DATE} < ?" to arrayOf(beforeDateMillis.toString())
            else -> null to null
        }
        return runCatching {
            context.contentResolver.query(
                uri,
                THREAD_PROJECTION,
                sel,
                args,
                "${Telephony.Threads.DATE} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Threads._ID)
                val snippetIdx = cursor.getColumnIndexOrThrow(Telephony.Threads.SNIPPET)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Threads.DATE)
                val countIdx = cursor.getColumnIndexOrThrow(Telephony.Threads.MESSAGE_COUNT)
                val readIdx = cursor.getColumnIndex(Telephony.Threads.READ)
                val recipientIdsIdx = cursor.getColumnIndex(Telephony.Threads.RECIPIENT_IDS)
                buildList {
                    var n = 0
                    while (cursor.moveToNext() && n < limit) {
                        val threadId = cursor.getLong(idIdx)
                        val read = if (readIdx >= 0) cursor.getInt(readIdx) == 1 else true
                        val recipientIdsRaw =
                            if (recipientIdsIdx >= 0) cursor.getString(recipientIdsIdx) else null
                        val participants = resolveRecipientAddresses(recipientIdsRaw)
                        add(
                            SmsThread(
                                threadId = threadId,
                                // Threads.CONTENT_URI simple mode has no ADDRESS column;
                                // resolve from the latest message in the thread.
                                address = resolveAddressForThread(threadId)
                                    ?: participants.firstOrNull(),
                                snippet = snippetOrPhotoFallback(
                                    threadId,
                                    cursor.getString(snippetIdx),
                                ),
                                date = cursor.getLong(dateIdx),
                                messageCount = cursor.getInt(countIdx),
                                unreadCount = if (read) 0 else 1,
                                participantAddresses = participants,
                            ),
                        )
                        n++
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Other parties on [threadId], from [Telephony.Threads.RECIPIENT_IDS].
     * Used by the thread screen without reloading the full inbox list.
     */
    suspend fun getThreadParticipantAddresses(threadId: Long): List<String> =
        withContext(Dispatchers.IO) {
            if (!hasReadSmsPermission()) return@withContext emptyList()
            val uri = Telephony.Threads.CONTENT_URI.buildUpon()
                .appendQueryParameter("simple", "true")
                .build()
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(Telephony.Threads.RECIPIENT_IDS),
                    "${Telephony.Threads._ID} = ?",
                    arrayOf(threadId.toString()),
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use emptyList()
                    val idx = cursor.getColumnIndex(Telephony.Threads.RECIPIENT_IDS)
                    if (idx < 0) return@use emptyList()
                    resolveRecipientAddresses(cursor.getString(idx))
                }
            }.getOrNull().orEmpty()
        }

    /**
     * SMS + MMS still on [threadId] when conversations simple mode did not
     * return that `_id`. Date comes from the messages themselves — Threads.DATE
     * is last-write at second precision and is not [Telephony.Sms.DATE].
     */
    private fun threadFromMessageTables(threadId: Long): SmsThread? {
        val smsCount = countInThread(
            Telephony.Sms.CONTENT_URI,
            Telephony.Sms.THREAD_ID,
            threadId,
        )
        val mmsCount = countInThread(
            Telephony.Mms.CONTENT_URI,
            Telephony.Mms.THREAD_ID,
            threadId,
        )
        if (smsCount + mmsCount == 0) return null
        val smsDate = latestColumnLong(
            Telephony.Sms.CONTENT_URI,
            Telephony.Sms.DATE,
            "${Telephony.Sms.THREAD_ID} = ?",
            threadId,
        )
        val mmsDateSeconds = latestColumnLong(
            Telephony.Mms.CONTENT_URI,
            Telephony.Mms.DATE,
            "${Telephony.Mms.THREAD_ID} = ?",
            threadId,
        )
        val date = maxOf(
            smsDate ?: Long.MIN_VALUE,
            mmsDateSeconds?.let { it * 1000L } ?: Long.MIN_VALUE,
        ).takeUnless { it == Long.MIN_VALUE } ?: 0L
        val body = runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val address = resolveAddressForThread(threadId)
        return SmsThread(
            threadId = threadId,
            address = address,
            snippet = snippetOrPhotoFallback(threadId, body),
            date = date,
            messageCount = smsCount + mmsCount,
            unreadCount = if (threadHasUnread(threadId)) 1 else 0,
            participantAddresses = listOfNotNull(address).distinct(),
        )
    }

    private fun latestColumnLong(
        uri: Uri,
        column: String,
        selection: String,
        threadId: Long,
    ): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(column),
            selection,
            arrayOf(threadId.toString()),
            "$column DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun threadHasUnread(threadId: Long): Boolean {
        val unreadSms = runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
                null,
            )?.use { it.count > 0 }
        }.getOrNull() == true
        if (unreadSms) return true
        return runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                arrayOf(threadId.toString()),
                null,
            )?.use { it.count > 0 }
        }.getOrNull() == true
    }

    /**
     * Latest [Telephony.Sms.ADDRESS] for [threadId], or the MMS originator when
     * the thread is picture-only. Used because [Telephony.Threads] simple
     * projection does not expose an address.
     */
    private fun resolveAddressForThread(threadId: Long): String? {
        val sms = runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        if (!sms.isNullOrBlank()) return sms
        return resolveMmsAddressForThread(threadId)
    }

    /**
     * Chats snippet when the platform row is blank or the thread still has
     * an undownloaded `M-Notification.ind`. Undownloaded rows use
     * [R.string.mms_download] so copy item 9 actually reaches the screen.
     * Picture-only threads fall back to [PHOTO_SNIPPET_FALLBACK], matching
     * [IncomingMessagePipeline] display copy — not a new string resource.
     */
    private fun snippetOrPhotoFallback(threadId: Long, snippet: String?): String? {
        val hasUndownloaded = runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_TYPE} = ?",
                arrayOf(
                    threadId.toString(),
                    MmsRepository.MESSAGE_TYPE_NOTIFICATION_IND.toString(),
                ),
                null,
            )?.use { it.count > 0 }
        }.getOrNull() == true
        if (hasUndownloaded) return context.getString(R.string.mms_download)
        if (!snippet.isNullOrBlank()) return snippet
        val hasPhoto = runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.TEXT_ONLY} = 0",
                arrayOf(threadId.toString()),
                null,
            )?.use { it.count > 0 }
        }.getOrNull() == true
        return if (hasPhoto) PHOTO_SNIPPET_FALLBACK else snippet
    }

    private fun resolveMmsAddressForThread(threadId: Long): String? {
        val msgId = runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Mms.DATE} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getLong(0) to cursor.getInt(1)
            }
        }.getOrNull() ?: return null
        val incoming = msgId.second == Telephony.Mms.MESSAGE_BOX_INBOX
        return resolveMmsAddress(msgId.first, incoming)
    }

    private fun resolveMmsAddress(msgId: Long, incoming: Boolean): String? {
        val addrType = if (incoming) {
            MMS_ADDR_TYPE_FROM
        } else {
            MMS_ADDR_TYPE_TO
        }
        return runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/$msgId/addr"),
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null,
                null,
                null,
            )?.use { cursor ->
                val addressIdx = cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
                val typeIdx = cursor.getColumnIndex(Telephony.Mms.Addr.TYPE)
                if (addressIdx < 0) return@use null
                while (cursor.moveToNext()) {
                    val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1
                    if (type == addrType) {
                        val address = cursor.getString(addressIdx)
                        if (!address.isNullOrBlank()) return@use address
                    }
                }
                null
            }
        }.getOrNull()
    }

    /**
     * [Telephony.Threads.RECIPIENT_IDS] is a space-separated list of rows in the
     * platform's canonical-addresses table — not phone numbers. Resolve each id.
     */
    private fun resolveRecipientAddresses(recipientIdsRaw: String?): List<String> {
        if (recipientIdsRaw.isNullOrBlank()) return emptyList()
        val ids = recipientIdsRaw
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { id -> resolveCanonicalAddress(id) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun resolveCanonicalAddress(recipientId: Long): String? {
        // Prefer the singular URI used by AOSP Telephony; fall back to the table query.
        val singular = runCatching {
            val uri = android.content.ContentUris.withAppendedId(
                Uri.parse("content://mms-sms/canonical-address"),
                recipientId,
            )
            context.contentResolver.query(
                uri,
                arrayOf("address"),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        if (!singular.isNullOrBlank()) return singular

        return runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms-sms/canonical-addresses"),
                arrayOf("address"),
                "_id = ?",
                arrayOf(recipientId.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /**
     * SMS + MMS in [threadId], oldest first, the [limit] newest of that window.
     *
     * Newest page: each table is queried `DATE DESC` and stopped at [limit],
     * then merged. Older page: pass [beforeDateMillis] (`DATE < oldest`).
     * Does **not** load the whole thread into memory then `takeLast`.
     *
     * Shipped as an explicit two-table merge keyed by [MessageRef], not
     * `content://mms-sms/conversations/{id}`. That URI's `transport_type`
     * discriminator is SDK-sensitive; a merge makes identity (and the
     * colliding-`_id` delete) a property of the query rather than of the
     * provider view. Does **not** read [Telephony.Mms.Part] — body for MMS
     * is the `SUBJECT` column until 17.3.
     */
    suspend fun getMessagesForThread(
        threadId: Long,
        limit: Int = 200,
        beforeDateMillis: Long? = null,
    ): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            if (!hasReadSmsPermission() || limit <= 0) return@withContext emptyList()
            val sms = querySmsForThread(threadId, limit, beforeDateMillis)
            val mms = queryMmsForThread(threadId, limit, beforeDateMillis)
            val merged = (sms + mms).sortedBy { it.date }
            val window = if (beforeDateMillis != null) {
                merged.filter { it.date < beforeDateMillis }
            } else {
                merged
            }
            window.takeLast(limit)
        }

    private fun querySmsForThread(
        threadId: Long,
        limit: Int,
        beforeDateMillis: Long?,
    ): List<SmsMessage> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        val (selection, args) = if (beforeDateMillis != null) {
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} < ?" to
                arrayOf(threadId.toString(), beforeDateMillis.toString())
        } else {
            "${Telephony.Sms.THREAD_ID} = ?" to arrayOf(threadId.toString())
        }
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                args,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                buildList {
                    var n = 0
                    while (cursor.moveToNext() && n < limit) {
                        add(
                            SmsMessage(
                                id = cursor.getLong(idIdx),
                                threadId = cursor.getLong(threadIdx),
                                address = cursor.getString(addressIdx),
                                body = cursor.getString(bodyIdx),
                                date = cursor.getLong(dateIdx),
                                type = cursor.getInt(typeIdx),
                                read = cursor.getInt(readIdx) == 1,
                                kind = MessageRef.Kind.SMS,
                            ),
                        )
                        n++
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun queryMmsForThread(
        threadId: Long,
        limit: Int,
        beforeDateMillis: Long?,
    ): List<SmsMessage> {
        val projection = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.READ,
            Telephony.Mms.SUBJECT,
            Telephony.Mms.TEXT_ONLY,
        )
        // MMS DATE is seconds. Inclusive on that second, then filter millis.
        val (selection, args) = if (beforeDateMillis != null) {
            val beforeSeconds = beforeDateMillis / 1000L
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.DATE} <= ?" to
                arrayOf(threadId.toString(), beforeSeconds.toString())
        } else {
            "${Telephony.Mms.THREAD_ID} = ?" to arrayOf(threadId.toString())
        }
        return runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                projection,
                selection,
                args,
                "${Telephony.Mms.DATE} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val boxIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val readIdx = cursor.getColumnIndex(Telephony.Mms.READ)
                val subjectIdx = cursor.getColumnIndex(Telephony.Mms.SUBJECT)
                val textOnlyIdx = cursor.getColumnIndex(Telephony.Mms.TEXT_ONLY)
                buildList {
                    var n = 0
                    while (cursor.moveToNext() && n < limit) {
                        val id = cursor.getLong(idIdx)
                        val box = cursor.getInt(boxIdx)
                        val incoming = box == Telephony.Mms.MESSAGE_BOX_INBOX
                        val dateMillis = cursor.getLong(dateIdx) * 1000L
                        if (beforeDateMillis != null && dateMillis >= beforeDateMillis) continue
                        add(
                            SmsMessage(
                                id = id,
                                threadId = cursor.getLong(threadIdx),
                                address = resolveMmsAddress(id, incoming),
                                body = null,
                                date = dateMillis,
                                type = box,
                                read = if (readIdx >= 0) cursor.getInt(readIdx) == 1 else true,
                                kind = MessageRef.Kind.MMS,
                                subject = if (subjectIdx >= 0) {
                                    cursor.getString(subjectIdx)?.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                },
                                textOnly = if (textOnlyIdx >= 0) {
                                    cursor.getInt(textOnlyIdx) == 1
                                } else {
                                    true
                                },
                            ),
                        )
                        n++
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Recent inbox messages for the builder backtest (last N by date).
     */
    suspend fun getRecentInbox(limit: Int = 200): List<SmsMessage> = withContext(Dispatchers.IO) {
        if (!hasReadSmsPermission()) return@withContext emptyList()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                buildList {
                    var n = 0
                    while (cursor.moveToNext() && n < limit) {
                        add(
                            SmsMessage(
                                id = cursor.getLong(idIdx),
                                threadId = cursor.getLong(threadIdx),
                                address = cursor.getString(addressIdx),
                                body = cursor.getString(bodyIdx),
                                date = cursor.getLong(dateIdx),
                                type = cursor.getInt(typeIdx),
                                read = cursor.getInt(readIdx) == 1,
                            ),
                        )
                        n++
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Substring scan of SMS bodies (inbox **and** sent) plus MMS `text/plain`
     * part text. Newest hit per thread wins. Does **not** use [getRecentInbox]
     * — that stays SMS-inbox-only for builder backtest/sweep.
     */
    suspend fun searchMessageBodies(
        query: String,
        limitPerSource: Int = SEARCH_BODY_SCAN_LIMIT,
    ): Map<Long, ThreadBodyHit> = withContext(Dispatchers.IO) {
        val pattern = sqlLikeContains(query) ?: return@withContext emptyMap()
        if (!hasReadSmsPermission()) return@withContext emptyMap()
        val byThread = LinkedHashMap<Long, ThreadBodyHit>()
        fun consider(hit: ThreadBodyHit) {
            val existing = byThread[hit.threadId]
            if (existing == null || hit.dateMillis > existing.dateMillis) {
                byThread[hit.threadId] = hit
            }
        }
        searchSmsBodies(pattern, limitPerSource).forEach(::consider)
        searchMmsPlainTextBodies(pattern, limitPerSource).forEach(::consider)
        byThread
    }

    private fun searchSmsBodies(pattern: String, limit: Int): List<ThreadBodyHit> {
        val selection = "(${Telephony.Sms.TYPE} = ? OR ${Telephony.Sms.TYPE} = ?) " +
            "AND ${Telephony.Sms.BODY} LIKE ? ESCAPE '!'"
        val args = arrayOf(
            Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
            Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
            pattern,
        )
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY, Telephony.Sms.DATE),
                selection,
                args,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                buildList {
                    var n = 0
                    while (cursor.moveToNext() && n < limit) {
                        val body = cursor.getString(bodyIdx)?.takeIf { it.isNotBlank() }
                            ?: continue
                        add(
                            ThreadBodyHit(
                                threadId = cursor.getLong(threadIdx),
                                body = body,
                                dateMillis = cursor.getLong(dateIdx),
                            ),
                        )
                        n++
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun searchMmsPlainTextBodies(pattern: String, limit: Int): List<ThreadBodyHit> {
        val selection = "(${Telephony.Mms.Part.CONTENT_TYPE} = ? OR " +
            "${Telephony.Mms.Part.CONTENT_TYPE} LIKE ?) AND " +
            "${Telephony.Mms.Part.TEXT} LIKE ? ESCAPE '!'"
        val args = arrayOf("text/plain", "text/plain;%", pattern)
        val threadDateByMsg = HashMap<Long, Pair<Long, Long>>()
        return runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
                selection,
                args,
                null,
            )?.use { cursor ->
                val msgIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID)
                val textIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
                buildList {
                    var n = 0
                    while (cursor.moveToNext() && n < limit) {
                        val text = cursor.getString(textIdx)?.takeIf { it.isNotBlank() }
                            ?: continue
                        val msgId = cursor.getLong(msgIdx)
                        val threadDate = threadDateByMsg.getOrPut(msgId) {
                            resolveMmsThreadAndDate(msgId) ?: return@getOrPut (-1L to 0L)
                        }
                        if (threadDate.first < 0L) continue
                        add(
                            ThreadBodyHit(
                                threadId = threadDate.first,
                                body = text,
                                dateMillis = threadDate.second,
                            ),
                        )
                        n++
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun resolveMmsThreadAndDate(msgId: Long): Pair<Long, Long>? {
        return runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
                "${Telephony.Mms._ID} = ?",
                arrayOf(msgId.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val threadId = cursor.getLong(0)
                val dateMillis = cursor.getLong(1) * 1000L
                threadId to dateMillis
            }
        }.getOrNull()
    }

    suspend fun insertInbox(
        address: String,
        body: String,
        dateMillis: Long,
        read: Boolean,
    ): WriteResult = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext WriteResult.RoleNotHeld
        try {
            val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, dateMillis)
                put(Telephony.Sms.DATE_SENT, dateMillis)
                put(Telephony.Sms.READ, if (read) 1 else 0)
                put(Telephony.Sms.SEEN, if (read) 1 else 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.THREAD_ID, threadId)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                ?: return@withContext WriteResult.Failed(
                    IllegalStateException("SMS inbox insert returned null"),
                )
            WriteResult.Success(uri)
        } catch (t: Throwable) {
            WriteResult.Failed(t)
        }
    }

    /**
     * Deletes every SMS **and** MMS in [threadId]. The provider reaps the empty
     * thread. Only the default SMS app may delete — check role before writing.
     *
     * Both tables are attempted even if the first throws. Success requires
     * neither throw **and** zero remaining rows in either table — a partial
     * delete must not report [WriteResult.Success].
     */
    suspend fun deleteThread(threadId: Long): WriteResult = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext WriteResult.RoleNotHeld
        var smsThrew: Throwable? = null
        var mmsThrew: Throwable? = null
        try {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
            )
        } catch (t: Throwable) {
            smsThrew = t
        }
        try {
            context.contentResolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
            )
        } catch (t: Throwable) {
            mmsThrew = t
        }
        val smsRemaining = countInThread(
            Telephony.Sms.CONTENT_URI,
            Telephony.Sms.THREAD_ID,
            threadId,
        )
        val mmsRemaining = countInThread(
            Telephony.Mms.CONTENT_URI,
            Telephony.Mms.THREAD_ID,
            threadId,
        )
        if (threadDeleteClearedBothTables(
                smsThrew = smsThrew != null,
                mmsThrew = mmsThrew != null,
                smsRemaining = smsRemaining,
                mmsRemaining = mmsRemaining,
            )
        ) {
            WriteResult.Success(uri = null)
        } else {
            WriteResult.Failed(
                smsThrew ?: mmsThrew ?: IllegalStateException(
                    "partial thread delete: smsLeft=$smsRemaining mmsLeft=$mmsRemaining",
                ),
            )
        }
    }

    /**
     * Deletes messages by [MessageRef], routing SMS and MMS `_ID`s to their
     * own tables. Chunks ids to stay under SQLite variable limits. Role-gated
     * like [insertInbox]. A throw from either table is [WriteResult.Failed].
     */
    suspend fun deleteMessages(refs: List<MessageRef>): WriteResult = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext WriteResult.RoleNotHeld
        if (refs.isEmpty()) return@withContext WriteResult.Success(uri = null)
        try {
            val smsIds = refs.filter { it.kind == MessageRef.Kind.SMS }.map { it.id }
            val mmsIds = refs.filter { it.kind == MessageRef.Kind.MMS }.map { it.id }
            deleteIds(Telephony.Sms.CONTENT_URI, Telephony.Sms._ID, smsIds)
            deleteIds(Telephony.Mms.CONTENT_URI, Telephony.Mms._ID, mmsIds)
            WriteResult.Success(uri = null)
        } catch (t: Throwable) {
            WriteResult.Failed(t)
        }
    }

    private fun deleteIds(uri: Uri, idColumn: String, ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(DELETE_ID_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(separator = ",") { "?" }
            context.contentResolver.delete(
                uri,
                "$idColumn IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
            )
        }
    }

    private fun countInThread(uri: Uri, threadColumn: String, threadId: Long): Int {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf("_id"),
                "$threadColumn = ?",
                arrayOf(threadId.toString()),
                null,
            )?.use { it.count }
        }.getOrNull() ?: 0
    }

    /**
     * Marks every unread SMS and MMS in [threadId] as read and seen. Role-gated
     * like the other writes. [WriteResult.Success.uri] is null — an update has
     * no row to return, same as the delete paths.
     *
     * Both provider tables are updated inside one role gate and one `try`. A
     * partial failure (SMS marked, MMS throwing, or the reverse) returns
     * [WriteResult.Failed]: the conversation is not reliably read, and
     * [WriteResult.Success] would hide a badge that never clears.
     *
     * Does not touch [Telephony.Mms.READ_REPORT]. That column is a delivery
     * receipt to the sender; this app never sends one.
     */
    suspend fun markThreadRead(threadId: Long): WriteResult = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext WriteResult.RoleNotHeld
        try {
            val smsValues = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                smsValues,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
            val mmsValues = ContentValues().apply {
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
            }
            context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                mmsValues,
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                arrayOf(threadId.toString()),
            )
            WriteResult.Success(uri = null)
        } catch (t: Throwable) {
            WriteResult.Failed(t)
        }
    }

    suspend fun insertSent(
        address: String,
        body: String,
        dateMillis: Long,
    ): WriteResult = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext WriteResult.RoleNotHeld
        try {
            val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, dateMillis)
                put(Telephony.Sms.DATE_SENT, dateMillis)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.THREAD_ID, threadId)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                ?: return@withContext WriteResult.Failed(
                    IllegalStateException("SMS sent insert returned null"),
                )
            WriteResult.Success(uri)
        } catch (t: Throwable) {
            WriteResult.Failed(t)
        }
    }

    /**
     * Persist an outgoing SMS as [Telephony.Sms.MESSAGE_TYPE_OUTBOX] before the
     * radio call. The sent-result callback moves it to Sent or Failed.
     */
    suspend fun insertOutbox(
        address: String,
        body: String,
        dateMillis: Long,
    ): WriteResult = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext WriteResult.RoleNotHeld
        try {
            val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, dateMillis)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                put(Telephony.Sms.THREAD_ID, threadId)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
                ?: return@withContext WriteResult.Failed(
                    IllegalStateException("SMS outbox insert returned null"),
                )
            WriteResult.Success(uri)
        } catch (t: Throwable) {
            WriteResult.Failed(t)
        }
    }

    /**
     * Whether we hold ROLE_SMS — the **only** gate that matches whether the
     * system delivers [Telephony.Sms.Intents.SMS_DELIVER_ACTION] to us.
     *
     * Do not use [Telephony.Sms.getDefaultSmsPackage]: on some emulators it
     * returns null while RoleManager still holds the role, which caused every
     * inbox write to return [WriteResult.RoleNotHeld] and silent message loss.
     */
    fun isDefaultSmsApp(): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_SMS)
    }

    /**
     * Runtime [Manifest.permission.READ_SMS]. Granted automatically with
     * ROLE_SMS; when the role is not held the user must grant this explicitly
     * for preview-mode browsing.
     */
    fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Runtime [Manifest.permission.READ_CONTACTS]. Same story as SMS — role
     * grants it; preview mode needs an explicit ask for contact names.
     */
    fun hasReadContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Permissions preview mode needs to browse real history. */
    fun previewReadPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
    )

    /**
     * Emits when the SMS or MMS provider changes (insert, mark-read, delete,
     * sent, picture-message arrival, …).
     *
     * Telephony exposes no [Flow]; a [ContentObserver] is the event-driven
     * alternative to polling. Collectors (inbox badge, later the thread list)
     * should debounce — multipart deliveries fire several times in a burst.
     *
     * SMS and MMS are separate tables. An MMS write never notifies
     * [Telephony.Sms.CONTENT_URI], so a second observer watches
     * [Telephony.MmsSms.CONTENT_URI] (measured on API 36.1: that URI fires on
     * inbox insert; [Telephony.Sms.CONTENT_URI] does not). Both feed this
     * same flow so existing debounce still coalesces a burst.
     *
     * Emits once immediately so the first collection is not waiting on a
     * change. Registration failures (e.g. no `READ_SMS` in preview mode)
     * fail quietly and independently: one observer dying must not unregister
     * the other. One emission, then idle until cancelled — no crash.
     */
    fun observeSmsChanges(): Flow<Unit> = callbackFlow {
        fun newObserver() = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val smsObserver = newObserver()
        val mmsObserver = newObserver()

        fun tryRegister(uri: Uri, observer: ContentObserver): Boolean = try {
            context.contentResolver.registerContentObserver(
                uri,
                /* notifyForDescendants = */ true,
                observer,
            )
            true
        } catch (_: SecurityException) {
            // No READ_SMS (or similar) — stay quiet so preview mode is safe.
            false
        } catch (_: RuntimeException) {
            false
        }

        val smsRegistered = tryRegister(Telephony.Sms.CONTENT_URI, smsObserver)
        // Conversation-level view; MMS insert notifies this URI, not Sms.
        val mmsRegistered = tryRegister(Telephony.MmsSms.CONTENT_URI, mmsObserver)
        // Seed so subscribers compute a first value without waiting.
        trySend(Unit)
        awaitClose {
            if (smsRegistered) {
                runCatching {
                    context.contentResolver.unregisterContentObserver(smsObserver)
                }
            }
            if (mmsRegistered) {
                runCatching {
                    context.contentResolver.unregisterContentObserver(mmsObserver)
                }
            }
        }
    }

    companion object {
        /** Stay well under SQLite's host-parameter limit when deleting by id. */
        private const val DELETE_ID_CHUNK = 400

        /** Cap matching rows per transport so a pathological store cannot OOM search. */
        const val SEARCH_BODY_SCAN_LIMIT = 1_000

        private val THREAD_PROJECTION = arrayOf(
            Telephony.Threads._ID,
            Telephony.Threads.SNIPPET,
            Telephony.Threads.DATE,
            Telephony.Threads.MESSAGE_COUNT,
            Telephony.Threads.READ,
            Telephony.Threads.RECIPIENT_IDS,
        )

        /** Chats snippet when a picture MMS has no text part / blank SNIPPET. */
        internal const val PHOTO_SNIPPET_FALLBACK = "(Photo)"

        /** OMA-TS-MMS-ENC From / To — [Telephony.Mms.Addr] does not expose these as constants. */
        private const val MMS_ADDR_TYPE_FROM = 0x89
        private const val MMS_ADDR_TYPE_TO = 0x97
    }
}
