package com.pinotrouge.messaging.data.telephony

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.HashSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One MMS part as 17.3 will render it. [uri] is `content://mms/part/N` —
 * open with [android.content.ContentResolver.openInputStream], never by
 * reading `_data`. Empty image parts (zero bytes) are omitted.
 */
data class MmsPartRow(
    val seq: Int,
    val contentType: String,
    val uri: Uri,
    val byteSize: Long,
)

/**
 * Read/write Android's Telephony MMS provider (`pdu` + `part` tables).
 *
 * Writes are ROLE_SMS-gated, same contract as [SmsRepository]. Every insert
 * returns [SmsRepository.WriteResult] — callers **must** branch; discarding a
 * failure is how messages are lost while notifications still fire.
 *
 * No HTTP client: carrier MMSC traffic goes through [MmsTransport].
 */
@Singleton
class MmsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val smsRepository: SmsRepository,
) {

    /**
     * Persist an incoming MMS into [Telephony.Mms.Inbox] with real parts
     * streamed through [android.content.ContentResolver.openOutputStream].
     * Subject is its own column; text part only when [IncomingMms.body] is
     * non-blank. Does not download from the carrier — the receiver owns that.
     *
     * If a notification stub (`MESSAGE_TYPE` 130) already exists for the same
     * content location (transaction id only when location is blank, or when
     * location does not match any stub), that row is flipped to retrieve-conf
     * 132 rather than inserting a duplicate. A second retrieve-conf for the
     * same location **and originator** that is already type 132 is a no-op —
     * it does not insert and does not stream parts again. A blank originator
     * matches only a stored row whose From is also blank, never a row that
     * has one. Location alone is not enough: two different senders can share
     * an MMSC URL, and treating them as one message would silently drop the
     * second.
     *
     * @param read restore-as-already-read (sweep undo / expiry file). Arrival
     *   inserts leave this false.
     */
    suspend fun insertInbox(
        message: IncomingMms,
        read: Boolean = false,
    ): SmsRepository.WriteResult = withContext(Dispatchers.IO) {
        if (!smsRepository.isDefaultSmsApp()) {
            return@withContext SmsRepository.WriteResult.RoleNotHeld
        }
        try {
            val addresses = message.allAddresses
            if (addresses.isEmpty()) {
                return@withContext SmsRepository.WriteResult.Failed(
                    IllegalArgumentException("MMS has no addresses"),
                )
            }
            val readFlag = if (read) 1 else 0
            val stubId = findStubId(message.transactionId, message.contentLocation)
            if (stubId == null) {
                val already = findRetrievedId(message.contentLocation, message.originator)
                if (already != null) {
                    return@withContext SmsRepository.WriteResult.Success(
                        ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, already),
                    )
                }
            }
            val msgId: Long
            val msgUri: Uri
            if (stubId != null) {
                msgId = stubId
                msgUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, msgId)
                val updated = context.contentResolver.update(
                    msgUri,
                    retrieveConfValues(message, threadId = null, readFlag = readFlag, includeThread = false),
                    null,
                    null,
                )
                if (updated <= 0) {
                    return@withContext SmsRepository.WriteResult.Failed(
                        IllegalStateException("failed to flip notification stub $msgId"),
                    )
                }
            } else {
                val threadId = getOrCreateThreadId(addresses)
                val inserted = context.contentResolver.insert(
                    Telephony.Mms.Inbox.CONTENT_URI,
                    retrieveConfValues(message, threadId, readFlag, includeThread = true),
                ) ?: return@withContext SmsRepository.WriteResult.Failed(
                    IllegalStateException("Mms.Inbox insert returned null"),
                )
                msgUri = inserted
                msgId = ContentUris.parseId(inserted)
                insertAddr(msgId, message.originator, ADDR_TYPE_FROM)
                for (to in message.participants) {
                    if (to != message.originator) {
                        insertAddr(msgId, to, ADDR_TYPE_TO)
                    }
                }
            }

            try {
                streamParts(msgId, message)
            } catch (t: Throwable) {
                deletePdu(msgId)
                throw t
            }
            SmsRepository.WriteResult.Success(msgUri)
        } catch (t: Throwable) {
            Log.e(TAG, "insertInbox failed", t)
            SmsRepository.WriteResult.Failed(t)
        }
    }

    /**
     * Write a downloaded Retrieve-Conf PDU bytes into the provider via the same
     * path as [insertInbox], after a best-effort parse of originator/body.
     */
    suspend fun insertInboxFromParsed(message: IncomingMms): SmsRepository.WriteResult =
        insertInbox(message)

    /**
     * File an undownloaded `M-Notification.ind` so Chats can show the
     * **Download** affordance. No parts. Idempotent on content location,
     * or on transaction id when location is blank.
     */
    suspend fun insertNotificationStub(message: IncomingMms): SmsRepository.WriteResult =
        withContext(Dispatchers.IO) {
            if (!smsRepository.isDefaultSmsApp()) {
                return@withContext SmsRepository.WriteResult.RoleNotHeld
            }
            try {
                val addresses = message.allAddresses
                if (addresses.isEmpty()) {
                    return@withContext SmsRepository.WriteResult.Failed(
                        IllegalArgumentException("MMS notification has no addresses"),
                    )
                }
                findStubId(message.transactionId, message.contentLocation)?.let { existing ->
                    return@withContext SmsRepository.WriteResult.Success(
                        ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, existing),
                    )
                }
                val threadId = getOrCreateThreadId(addresses)
                val values = ContentValues().apply {
                    put(Telephony.Mms.THREAD_ID, threadId)
                    put(Telephony.Mms.DATE, message.receivedAtMillis / 1000L)
                    put(Telephony.Mms.DATE_SENT, message.receivedAtMillis / 1000L)
                    put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                    put(Telephony.Mms.READ, 0)
                    put(Telephony.Mms.SEEN, 0)
                    put(Telephony.Mms.MESSAGE_TYPE, MESSAGE_TYPE_NOTIFICATION_IND)
                    put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
                    put(Telephony.Mms.MESSAGE_CLASS, "personal")
                    put(Telephony.Mms.PRIORITY, PRIORITY_NORMAL)
                    put(Telephony.Mms.TEXT_ONLY, 1)
                    put(Telephony.Mms.SUBJECT, message.subject.orEmpty())
                    put(Telephony.Mms.SUBJECT_CHARSET, CHARSET_UTF8)
                    if (!message.transactionId.isNullOrBlank()) {
                        put(Telephony.Mms.TRANSACTION_ID, message.transactionId)
                    }
                    if (!message.contentLocation.isNullOrBlank()) {
                        put(Telephony.Mms.CONTENT_LOCATION, message.contentLocation)
                    }
                    message.expiryMillis?.let { put(Telephony.Mms.EXPIRY, it / 1000L) }
                    message.subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
                }
                val msgUri = context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values)
                    ?: return@withContext SmsRepository.WriteResult.Failed(
                        IllegalStateException("Mms.Inbox stub insert returned null"),
                    )
                val msgId = ContentUris.parseId(msgUri)
                insertAddr(msgId, message.originator, ADDR_TYPE_FROM)
                for (to in message.participants) {
                    if (to != message.originator) {
                        insertAddr(msgId, to, ADDR_TYPE_TO)
                    }
                }
                SmsRepository.WriteResult.Success(msgUri)
            } catch (t: Throwable) {
                Log.e(TAG, "insertNotificationStub failed", t)
                SmsRepository.WriteResult.Failed(t)
            }
        }

    /**
     * Remove a notification stub after the message is held. A held picture
     * must not keep a Chats row that still says **Download**.
     */
    suspend fun deleteNotificationStub(
        transactionId: String?,
        contentLocation: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val id = findStubId(transactionId, contentLocation) ?: return@withContext
        deletePdu(id)
    }

    /**
     * Parts for [mmsId], skipping empty image parts (the Wave 10 marker lie).
     * Cleaning those rows is a side effect of touching the message.
     */
    suspend fun getParts(mmsId: Long): List<MmsPartRow> = withContext(Dispatchers.IO) {
        cleanEmptyImageParts(mmsId)
        queryParts(mmsId)
    }

    /**
     * Rehydrate an undownloaded notification so tapping **Download** can
     * resume the carrier fetch. 17.3 is the named UI consumer.
     */
    suspend fun notificationFor(mmsId: Long): IncomingMms? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
        val projection = arrayOf(
            Telephony.Mms.MESSAGE_TYPE,
            Telephony.Mms.SUBJECT,
            Telephony.Mms.DATE,
            Telephony.Mms.TRANSACTION_ID,
            Telephony.Mms.CONTENT_LOCATION,
            Telephony.Mms.EXPIRY,
            Telephony.Mms.SUBSCRIPTION_ID,
        )
        val row = context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val type = c.getInt(0)
            if (type != MESSAGE_TYPE_NOTIFICATION_IND) return@use null
            val subject = c.getString(1)?.takeIf { it.isNotBlank() }
            val dateSec = c.getLong(2)
            val txn = c.getString(3)?.takeIf { it.isNotBlank() }
            val location = c.getString(4)?.takeIf { it.isNotBlank() }
            val expiry = if (c.isNull(5)) null else c.getLong(5) * 1000L
            val subId = if (c.isNull(6)) null else c.getInt(6)
            NotificationRow(subject, dateSec * 1000L, txn, location, expiry, subId)
        } ?: return@withContext null
        val originator = queryAddr(mmsId, ADDR_TYPE_FROM) ?: return@withContext null
        val tos = queryAddrs(mmsId, ADDR_TYPE_TO).filter { it != originator }
        IncomingMms(
            originator = originator,
            participants = tos,
            body = "",
            subject = row.subject,
            receivedAtMillis = row.receivedAtMillis,
            messageType = MESSAGE_TYPE_NOTIFICATION_IND,
            transactionId = row.transactionId,
            contentLocation = row.contentLocation,
            expiryMillis = row.expiryMillis,
            subscriptionId = row.subscriptionId,
        )
    }

    private data class NotificationRow(
        val subject: String?,
        val receivedAtMillis: Long,
        val transactionId: String?,
        val contentLocation: String?,
        val expiryMillis: Long?,
        val subscriptionId: Int?,
    )

    /**
     * Persist an outgoing multi-recipient MMS into [Telephony.Mms.Sent].
     * Telephony only — the carrier send is [MmsTransport].
     *
     * [image] is the ladder-encoded bytes that went into the PDU, not the
     * original file. `TEXT_ONLY` is 0 when an image is present so the
     * thread tile path can see it. A blank caption writes no text part.
     */
    suspend fun insertSent(
        recipients: List<String>,
        body: String,
        image: MmsSendComposer.OutboundImage? = null,
        dateMillis: Long = System.currentTimeMillis(),
    ): SmsRepository.WriteResult = withContext(Dispatchers.IO) {
        if (!smsRepository.isDefaultSmsApp()) {
            return@withContext SmsRepository.WriteResult.RoleNotHeld
        }
        val addresses = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (addresses.isEmpty()) {
            return@withContext SmsRepository.WriteResult.Failed(
                IllegalArgumentException("MMS sent with no recipients"),
            )
        }
        var msgUri: Uri? = null
        try {
            val threadId = getOrCreateThreadId(addresses)
            val msgValues = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, threadId)
                put(Telephony.Mms.DATE, dateMillis / 1000L)
                put(Telephony.Mms.DATE_SENT, dateMillis / 1000L)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, MESSAGE_TYPE_SEND_REQ)
                put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
                put(Telephony.Mms.MESSAGE_CLASS, "personal")
                put(Telephony.Mms.PRIORITY, PRIORITY_NORMAL)
                put(Telephony.Mms.READ_REPORT, 0)
                put(Telephony.Mms.REPORT_ALLOWED, 0)
                put(Telephony.Mms.TRANSACTION_ID, "pinot-out-$dateMillis")
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                put(Telephony.Mms.TEXT_ONLY, if (image != null) 0 else 1)
            }
            msgUri = context.contentResolver.insert(Telephony.Mms.Sent.CONTENT_URI, msgValues)
                ?: return@withContext SmsRepository.WriteResult.Failed(
                    IllegalStateException("Mms.Sent insert returned null"),
                )
            val msgId = ContentUris.parseId(msgUri)
            for (to in addresses) {
                insertAddr(msgId, to, ADDR_TYPE_TO)
            }
            var seq = 0
            if (body.isNotBlank()) {
                insertTextPart(msgId, body, seq)
                seq++
            }
            if (image != null) {
                insertImagePart(msgId, image, seq)
            }
            SmsRepository.WriteResult.Success(msgUri)
        } catch (t: Throwable) {
            msgUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
                runCatching {
                    val msgId = ContentUris.parseId(uri)
                    context.contentResolver.delete(Uri.parse("content://mms/$msgId/part"), null, null)
                    context.contentResolver.delete(Uri.parse("content://mms/$msgId/addr"), null, null)
                }
            }
            Log.e(TAG, "insertSent failed", t)
            SmsRepository.WriteResult.Failed(t)
        }
    }

    /** Thread id for a multi-party set — used after a successful group send. */
    suspend fun threadIdFor(addresses: Collection<String>): Long = withContext(Dispatchers.IO) {
        getOrCreateThreadId(addresses.map { it.trim() }.filter { it.isNotEmpty() })
    }

    /**
     * Write a SendReq PDU to cache for [MmsTransport.send].
     * FileProvider content URI — `com.android.mms.service` is a different UID.
     */
    fun writeSendPduFile(recipients: List<String>, body: String): MmsCachePdu =
        PlatformMmsTransport.cachePdu(
            context = context,
            dirName = PlatformMmsTransport.SEND_CACHE_DIR,
            fileName = "send-${System.nanoTime()}.pdu",
            bytes = MmsSendComposer.composeSendReq(recipients, body),
        )

    /**
     * M-NotifyResp.ind bytes in `mms_send/` — never `held_media/`.
     * FileProvider content URI so the MMS service can read the ack.
     */
    fun writeNotifyRespFile(transactionId: String): MmsCachePdu =
        PlatformMmsTransport.cachePdu(
            context = context,
            dirName = PlatformMmsTransport.SEND_CACHE_DIR,
            fileName = "notifyresp-${System.nanoTime()}.pdu",
            bytes = MmsPduDecoder.composeNotifyResp(transactionId),
        )

    /**
     * Writable FileProvider target for [MmsTransport.download].
     * Android's MMS service is a different UID; a `file://` URI into our
     * private cache cannot be written through a URI permission grant.
     */
    fun createDownloadTarget(): MmsCachePdu =
        PlatformMmsTransport.cachePdu(
            context = context,
            dirName = PlatformMmsTransport.DOWNLOAD_CACHE_DIR,
            fileName = "dl-${System.nanoTime()}.pdu",
        )

    private fun retrieveConfValues(
        message: IncomingMms,
        threadId: Long?,
        readFlag: Int,
        includeThread: Boolean,
    ): ContentValues = ContentValues().apply {
        if (includeThread && threadId != null) {
            put(Telephony.Mms.THREAD_ID, threadId)
        }
        put(Telephony.Mms.DATE, message.receivedAtMillis / 1000L)
        put(Telephony.Mms.DATE_SENT, message.receivedAtMillis / 1000L)
        put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
        put(Telephony.Mms.READ, readFlag)
        put(Telephony.Mms.SEEN, readFlag)
        put(Telephony.Mms.MESSAGE_TYPE, MESSAGE_TYPE_RETRIEVE_CONF)
        put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
        put(Telephony.Mms.MESSAGE_CLASS, "personal")
        put(Telephony.Mms.PRIORITY, PRIORITY_NORMAL)
        put(Telephony.Mms.READ_REPORT, 0)
        put(Telephony.Mms.REPORT_ALLOWED, 0)
        put(Telephony.Mms.RESPONSE_STATUS, 0)
        put(Telephony.Mms.STATUS, 0)
        put(
            Telephony.Mms.TRANSACTION_ID,
            message.transactionId?.takeIf { it.isNotBlank() } ?: "pinot-${message.receivedAtMillis}",
        )
        put(Telephony.Mms.SUBJECT, message.subject.orEmpty())
        put(Telephony.Mms.SUBJECT_CHARSET, CHARSET_UTF8)
        put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
        put(Telephony.Mms.TEXT_ONLY, if (isTextOnly(message)) 1 else 0)
        if (!message.contentLocation.isNullOrBlank()) {
            put(Telephony.Mms.CONTENT_LOCATION, message.contentLocation)
        }
        message.subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
    }

    private fun isTextOnly(message: IncomingMms): Boolean {
        if (message.hasPhoto || message.hasAnyPart || message.hasAttachment) return false
        return message.parts.none { !it.isTextPlain && !it.isSmil && it.bytes.isNotEmpty() }
    }

    private fun streamParts(msgId: Long, message: IncomingMms) {
        var seq = 0
        val textFromParts = message.parts.filter { it.isTextPlain && !it.isSmil && it.bytes.isNotEmpty() }
        if (textFromParts.isNotEmpty()) {
            for (part in textFromParts) {
                val charset = charsetOrUtf8(part.charset)
                val text = String(part.bytes, charset)
                if (text.isNotBlank()) {
                    insertTextPart(msgId, text, seq, charsetMib(part.charset))
                    seq++
                }
            }
        } else if (message.body.isNotBlank()) {
            insertTextPart(msgId, message.body, seq)
            seq++
        }

        val binary = message.parts.filter { part ->
            !part.isSmil && !part.isTextPlain && !(part.isImage && part.bytes.isEmpty())
        }
        for (part in binary) {
            insertBinaryPart(msgId, part, seq)
            seq++
        }
        cleanEmptyImageParts(msgId)
    }

    private fun insertBinaryPart(msgId: Long, part: MmsPart, seq: Int) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, msgId)
            put(Telephony.Mms.Part.SEQ, seq)
            put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
            put(Telephony.Mms.Part.NAME, nameFor(part.contentType, seq))
            part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
            part.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
        }
        val partUri = context.contentResolver.insert(
            Uri.parse("content://mms/$msgId/part"),
            values,
        ) ?: throw IllegalStateException("part insert returned null for seq=$seq")
        val stream = context.contentResolver.openOutputStream(partUri)
            ?: throw IllegalStateException("openOutputStream null for $partUri")
        stream.use { it.write(part.bytes) }
    }

    private fun nameFor(contentType: String, seq: Int): String {
        val subtype = contentType.substringAfter('/', missingDelimiterValue = "bin")
            .substringBefore(';')
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .ifBlank { "bin" }
        return "part-$seq.$subtype"
    }

    private fun queryParts(mmsId: Long): List<MmsPartRow> {
        val partDir = Uri.parse("content://mms/$mmsId/part")
        return context.contentResolver.query(
            partDir,
            arrayOf(
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.SEQ,
                Telephony.Mms.Part.CONTENT_TYPE,
            ),
            null,
            null,
            "${Telephony.Mms.Part.SEQ} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
            val seqIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.SEQ)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId)
                    val type = cursor.getString(typeIdx).orEmpty()
                    val size = partByteSize(uri)
                    if (type.startsWith("image/", ignoreCase = true) && size <= 0L) continue
                    add(
                        MmsPartRow(
                            seq = cursor.getInt(seqIdx),
                            contentType = type,
                            uri = uri,
                            byteSize = size,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    private fun cleanEmptyImageParts(mmsId: Long) {
        val partDir = Uri.parse("content://mms/$mmsId/part")
        val toDelete = mutableListOf<Long>()
        context.contentResolver.query(
            partDir,
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            while (cursor.moveToNext()) {
                val type = cursor.getString(typeIdx).orEmpty()
                if (!type.startsWith("image/", ignoreCase = true)) continue
                val partId = cursor.getLong(idIdx)
                val uri = ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId)
                if (partByteSize(uri) <= 0L) toDelete += partId
            }
        }
        for (partId in toDelete) {
            context.contentResolver.delete(
                ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId),
                null,
                null,
            )
        }
    }

    private fun partByteSize(partUri: Uri): Long {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(partUri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0L } ?: 0L
    }

    /**
     * Locate a still-undownloaded `M-Notification.ind` (type 130).
     *
     * Content location is tried first — it is the URL the retrieve was
     * made to. If that misses (the retrieve-conf echoed a different
     * location) or location is blank, transaction id is tried among the
     * remaining type-130 rows. The two are never ANDed. Whitespace is
     * trimmed on both the lookup value and the stored column (carriers pad).
     *
     * Already-flipped retrieve-conf rows are [findRetrievedId], not here —
     * dropping the type-130 filter would let a stub lookup match an
     * unrelated downloaded message.
     */
    private fun findStubId(transactionId: String?, contentLocation: String?): Long? {
        val location = contentLocation?.trim()?.takeIf { it.isNotEmpty() }
        val txn = transactionId?.trim()?.takeIf { it.isNotEmpty() }
        if (location == null && txn == null) return null
        val byLocation = location?.let { loc ->
            queryPduIds(MESSAGE_TYPE_NOTIFICATION_IND) { storedLoc, _, id ->
                if (storedLoc == loc) id else null
            }
        }
        if (byLocation != null) return byLocation
        if (txn == null) return null
        return queryPduIds(MESSAGE_TYPE_NOTIFICATION_IND) { _, storedTxn, id ->
            if (storedTxn == txn) id else null
        }
    }

    /**
     * A retrieve-conf (type 132) already stored for this message.
     *
     * Two retrieve-confs are the same message when they share a content
     * location **and** an originator (trimmed, case-insensitive). Location
     * is the URL the retrieve was made to; originator is the second
     * discriminator so two different senders at one MMSC URL cannot
     * collapse into one row. Transaction id is not used — it is not
     * stable across PDUs, which is why [findStubId] exists separately.
     *
     * A blank originator does **not** skip the lookup. It matches a stored
     * 132 at that location whose From is also blank. It never matches a
     * row that has a From — that would be location-alone, which drops a
     * second sender's message.
     */
    private fun findRetrievedId(contentLocation: String?, originator: String?): Long? {
        val location = contentLocation?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val from = originator?.trim()?.takeIf { it.isNotEmpty() }
        return queryPduIds(MESSAGE_TYPE_RETRIEVE_CONF) { storedLoc, _, id ->
            if (storedLoc != location) return@queryPduIds null
            val storedFrom = queryAddr(id, ADDR_TYPE_FROM)?.trim().orEmpty()
            if (from != null) {
                if (storedFrom.equals(from, ignoreCase = true)) id else null
            } else {
                if (storedFrom.isEmpty()) id else null
            }
        }
    }

    private fun queryPduIds(
        messageType: Int,
        match: (storedLoc: String, storedTxn: String, id: Long) -> Long?,
    ): Long? {
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.TRANSACTION_ID,
                Telephony.Mms.CONTENT_LOCATION,
            ),
            "${Telephony.Mms.MESSAGE_TYPE} = ?",
            arrayOf(messageType.toString()),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
            val txnIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.TRANSACTION_ID)
            val locIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.CONTENT_LOCATION)
            while (cursor.moveToNext()) {
                val hit = match(
                    cursor.getString(locIdx)?.trim().orEmpty(),
                    cursor.getString(txnIdx)?.trim().orEmpty(),
                    cursor.getLong(idIdx),
                )
                if (hit != null) return@use hit
            }
            null
        }
    }

    private fun deletePdu(msgId: Long) {
        context.contentResolver.delete(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, msgId),
            null,
            null,
        )
    }

    private fun getOrCreateThreadId(addresses: List<String>): Long {
        val set = HashSet(addresses)
        return Telephony.Threads.getOrCreateThreadId(context, set)
    }

    private fun insertAddr(msgId: Long, address: String, type: Int) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Addr.MSG_ID, msgId)
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8)
        }
        context.contentResolver.insert(
            Uri.parse("content://mms/$msgId/addr"),
            values,
        )
    }

    private fun queryAddr(msgId: Long, type: Int): String? =
        queryAddrs(msgId, type).firstOrNull()

    private fun queryAddrs(msgId: Long, type: Int): List<String> {
        return context.contentResolver.query(
            Uri.parse("content://mms/$msgId/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val out = mutableListOf<String>()
            val addrIdx = cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
            val typeIdx = cursor.getColumnIndex(Telephony.Mms.Addr.TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getInt(typeIdx) != type) continue
                val address = cursor.getString(addrIdx)?.trim().orEmpty()
                if (address.isNotEmpty()) out += address
            }
            out
        }.orEmpty()
    }

    private fun insertTextPart(
        msgId: Long,
        text: String,
        seq: Int,
        charset: Int = CHARSET_UTF8,
    ) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, msgId)
            put(Telephony.Mms.Part.SEQ, seq)
            put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
            put(Telephony.Mms.Part.NAME, "body.txt")
            put(Telephony.Mms.Part.CHARSET, charset)
            put(Telephony.Mms.Part.TEXT, text)
        }
        val inserted = context.contentResolver.insert(
            Uri.parse("content://mms/$msgId/part"),
            values,
        )
        if (inserted == null) {
            throw IllegalStateException("text part insert returned null")
        }
    }

    /**
     * Same `content://mms/$msgId/part` insert as [insertTextPart] / inbound
     * [insertBinaryPart]. Bytes go through the part's stream, never [Telephony.Mms.Part.TEXT].
     * [MmsSendComposer.OutboundImage.location] is both NAME and CONTENT_LOCATION
     * so the sender's copy matches the PDU.
     */
    private fun insertImagePart(
        msgId: Long,
        image: MmsSendComposer.OutboundImage,
        seq: Int,
    ) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, msgId)
            put(Telephony.Mms.Part.SEQ, seq)
            put(Telephony.Mms.Part.CONTENT_TYPE, image.contentType)
            put(Telephony.Mms.Part.NAME, image.location)
            put(Telephony.Mms.Part.CONTENT_LOCATION, image.location)
        }
        onImagePartWrite?.invoke(msgId, image)
        val partUri = context.contentResolver.insert(
            Uri.parse("content://mms/$msgId/part"),
            values,
        ) ?: throw IllegalStateException("image part insert returned null")
        val stream = context.contentResolver.openOutputStream(partUri)
            ?: throw IllegalStateException("openOutputStream null for $partUri")
        stream.use { it.write(image.bytes) }
    }

    private fun charsetOrUtf8(name: String?): java.nio.charset.Charset {
        if (name.isNullOrBlank()) return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun charsetMib(name: String?): Int = when (name?.lowercase()) {
        "utf-8", "utf8" -> CHARSET_UTF8
        "us-ascii", "ascii" -> 3
        "iso-8859-1", "latin1" -> 4
        else -> CHARSET_UTF8
    }

    companion object {
        private const val TAG = "MmsRepository"

        /**
         * Test seam: invoke before the image-part insert so a forced throw
         * still leaves the message + addr rows that the catch must roll back.
         */
        @VisibleForTesting
        @Volatile
        internal var onImagePartWrite: ((msgId: Long, image: MmsSendComposer.OutboundImage) -> Unit)? = null

        private const val MESSAGE_TYPE_SEND_REQ = 0x80
        const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82
        const val MESSAGE_TYPE_RETRIEVE_CONF = 0x84
        private const val MMS_VERSION_1_2 = 0x12
        private const val PRIORITY_NORMAL = 0x81
        private const val CHARSET_UTF8 = 106
        private const val ADDR_TYPE_FROM = 0x89
        private const val ADDR_TYPE_TO = 0x97
    }
}
