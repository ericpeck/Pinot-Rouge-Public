package com.pinotrouge.messaging.data.repo

import android.content.Context
import android.net.Uri
import com.pinotrouge.messaging.data.room.BlockedSenderDao
import com.pinotrouge.messaging.data.room.BlockedSenderEntity
import com.pinotrouge.messaging.data.room.HeldBy
import com.pinotrouge.messaging.data.room.HeldMediaDao
import com.pinotrouge.messaging.data.room.HeldMediaEntity
import com.pinotrouge.messaging.data.room.HeldMessageDao
import com.pinotrouge.messaging.data.room.HeldMessageEntity
import com.pinotrouge.messaging.data.room.RuleDao
import com.pinotrouge.messaging.data.room.TransportKind
import com.pinotrouge.messaging.data.telephony.IncomingMms
import com.pinotrouge.messaging.data.telephony.MmsPart
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.rules.Action
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One held attachment for Filtered (17.4). [file] is under
 * `filesDir/held_media/<heldId>/` — never the sender's filename.
 */
data class HeldMediaRef(
    val contentType: String,
    val file: File,
)

/**
 * Hybrid quarantine: hold privately in Room, file into Telephony only on
 * release or non-DELETE expiry. Every row of the quarantine table in
 * `03 - Filter Rule Spec` maps to a method here.
 *
 * MMS bytes live on disk, metadata in [held_media]. Write order is bytes
 * first, then the Room row — a file without a row is an orphan the sweep
 * collects; a row without bytes is permanently broken UI.
 */
@Singleton
class QuarantineRepository @Inject constructor(
    private val heldMessageDao: HeldMessageDao,
    private val blockedSenderDao: BlockedSenderDao,
    private val ruleDao: RuleDao,
    private val smsRepository: SmsRepository,
    private val heldMediaDao: HeldMediaDao,
    private val mmsRepository: MmsRepository,
    @param:ApplicationContext private val context: Context,
) {
    private val restoreMutexes = ConcurrentHashMap<String, Mutex>()

    fun observeHeld(): Flow<List<HeldMessageEntity>> = heldMessageDao.observeAll()

    fun observeHeldCount(): Flow<Int> = heldMessageDao.observeCount()

    /**
     * Count of messages still in quarantine that were held at or after [sinceMillis].
     * Used for "held by your filters this week" — pass now − 7 days so the copy and
     * the number agree (lifetime [observeHeldCount] is a different product number).
     */
    fun observeHeldCountSince(sinceMillis: Long): Flow<Int> =
        heldMessageDao.observeCountSince(sinceMillis)

    fun observeBlocked(): Flow<List<BlockedSenderEntity>> = blockedSenderDao.observeAll()

    /**
     * Attachments for a held row, in seq order. Missing files are omitted.
     */
    suspend fun heldMedia(heldId: String): List<HeldMediaRef> = withContext(Dispatchers.IO) {
        val dir = mediaDir(heldId)
        heldMediaDao.getForHeld(heldId).mapNotNull { row ->
            val file = File(dir, row.fileName)
            if (!file.isFile) return@mapNotNull null
            HeldMediaRef(contentType = row.contentType, file = file)
        }
    }

    /**
     * Rule with HOLD matches on arrival (or sweep after confirm):
     * insert into held_messages. Telephony is untouched on arrival;
     * on sweep the caller deletes the provider copy after a successful hold.
     *
     * Every new parameter is defaulted so [com.pinotrouge.messaging.ui.sweep.SweepViewModel]
     * (SMS-only, named args) keeps compiling untouched.
     *
     * @param heldBy [HeldBy.ARRIVAL] (default) or [HeldBy.SWEEP]
     * @param wasRead provider read flag at sweep time; ignored for arrival
     *   (restored only when [heldBy] is [HeldBy.SWEEP])
     */
    suspend fun holdOnArrival(
        sender: String,
        body: String,
        receivedAtMillis: Long,
        ruleId: String,
        reason: String,
        deleteAfterDays: Int,
        heldAtMillis: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
        heldBy: String = HeldBy.ARRIVAL,
        wasRead: Boolean = false,
        parts: List<MmsPart> = emptyList(),
        subject: String? = null,
        participants: List<String> = emptyList(),
        transportKind: String = TransportKind.SMS,
        transactionId: String? = null,
        contentLocation: String? = null,
        subscriptionId: Int? = null,
    ): HeldMessageEntity = withContext(Dispatchers.IO) {
        val expiresAt = heldAtMillis + TimeUnit.DAYS.toMillis(deleteAfterDays.toLong())
        val durableParts = parts.filter { part ->
            !part.isSmil && !part.isTextPlain && !(part.isImage && part.bytes.isEmpty())
        }
        val mediaRows = mutableListOf<HeldMediaEntity>()
        if (durableParts.isNotEmpty()) {
            val dir = mediaDir(id)
            dir.mkdirs()
            durableParts.forEachIndexed { seq, part ->
                val fileName = UUID.randomUUID().toString()
                val file = File(dir, fileName)
                file.outputStream().use { it.write(part.bytes) }
                mediaRows += HeldMediaEntity(
                    id = UUID.randomUUID().toString(),
                    heldId = id,
                    contentType = part.contentType,
                    seq = seq,
                    fileName = fileName,
                    byteSize = part.bytes.size.toLong(),
                )
            }
        }
        val entity = HeldMessageEntity(
            id = id,
            sender = sender,
            body = body,
            receivedAt = receivedAtMillis,
            heldAt = heldAtMillis,
            ruleId = ruleId,
            reason = reason,
            expiresAt = expiresAt,
            heldBy = heldBy,
            wasRead = wasRead,
            transportKind = transportKind,
            subject = subject,
            participants = encodeParticipants(participants),
            subscriptionId = subscriptionId,
            transactionId = transactionId,
            contentLocation = contentLocation,
        )
        heldMessageDao.insert(entity)
        if (mediaRows.isNotEmpty()) {
            heldMediaDao.insertAll(mediaRows)
        }
        entity
    }

    /**
     * User taps *Move to inbox* (or sweep undo): insert into Telephony, delete Room row.
     *
     * Arrival holds restore as **unread** (never were in the inbox).
     * Sweep holds restore the captured [HeldMessageEntity.wasRead] flag.
     * MMS restores parts from disk — no re-encode, EXIF kept as sent.
     *
     * Coordinated with [commitExpired]: a claim plus per-id mutex so two callers
     * cannot insert the same held SMS twice. A missing row is a success no-op
     * (the other caller already finished). A failed provider write keeps the
     * held message and its media.
     */
    suspend fun moveToInbox(heldId: String): SmsRepository.WriteResult = withContext(Dispatchers.IO) {
        when (val outcome = restoreHeld(heldId) { held ->
            held.heldBy == HeldBy.SWEEP && held.wasRead
        }) {
            is RestoreOutcome.Inserted -> outcome.result
            is RestoreOutcome.AlreadyDone -> SmsRepository.WriteResult.Success(outcome.uri)
            is RestoreOutcome.Unavailable -> outcome.result
        }
    }

    /**
     * User taps *Block sender*: Telephony untouched; delete row; add to blocklist.
     * A restore in flight keeps the row — blocking must not drop the last copy.
     */
    suspend fun blockSender(heldId: String, blockedAtMillis: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            mutexFor(heldId).withLock {
                val held = heldMessageDao.getById(heldId) ?: return@withLock
                if (held.restoreClaimedAt != null) return@withLock
                deleteHeldAndMedia(heldId)
                blockedSenderDao.upsert(
                    BlockedSenderEntity(
                        sender = normalizeSender(held.sender),
                        blockedAt = blockedAtMillis,
                    ),
                )
            }
        }

    /**
     * User taps *Delete*: Telephony untouched; delete row — gone for good.
     * A claimed restore keeps the row until that restore finishes or fails.
     */
    suspend fun deleteHeld(heldId: String) = withContext(Dispatchers.IO) {
        deleteHeldIfUnclaimed(heldId)
    }

    /**
     * User taps *Delete everything held*: Telephony untouched; drop unclaimed
     * rows only. Claimed restores keep their Room row and media.
     */
    suspend fun deleteAllHeld() = withContext(Dispatchers.IO) {
        val ids = heldMessageDao.getAllIds()
        for (id in ids) {
            deleteHeldIfUnclaimed(id)
        }
        if (heldMessageDao.getAllIds().isEmpty()) {
            File(context.filesDir, HELD_MEDIA_DIR).deleteRecursively()
        }
    }

    /**
     * Expiry processing for [QuarantineCommitWorker]:
     * - rule **has** DELETE → delete row (gone for good)
     * - rule **has no** DELETE → insert as **already read**, delete row
     *
     * Shares [restoreHeld] with [moveToInbox]. A claimed row is not hard-deleted.
     */
    suspend fun commitExpired(nowMillis: Long = System.currentTimeMillis()): CommitSummary =
        withContext(Dispatchers.IO) {
            val expired = heldMessageDao.getExpired(nowMillis)
            var deleted = 0
            var filed = 0
            var skipped = 0
            for (held in expired) {
                val rule = ruleDao.getById(held.ruleId)
                val hasDelete = rule?.actions?.contains(Action.DELETE) == true
                if (hasDelete) {
                    if (deleteHeldIfUnclaimed(held.id)) deleted++
                } else {
                    when (restoreHeld(held.id) { true }) {
                        is RestoreOutcome.Inserted -> filed++
                        is RestoreOutcome.AlreadyDone -> Unit
                        is RestoreOutcome.Unavailable -> skipped++
                    }
                }
            }
            CommitSummary(deleted = deleted, filed = filed, skipped = skipped)
        }

    /**
     * Drop `filesDir/held_media/<id>/` trees with no [held_messages] row.
     * Safe to call on every arrival — cheap when the directory is empty.
     */
    suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, HELD_MEDIA_DIR)
        if (!root.isDirectory) return@withContext
        val known = heldMessageDao.getAllIds().toSet()
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in known) {
                child.deleteRecursively()
            }
        }
        val mediaHeldIds = heldMediaDao.getHeldIds()
        for (heldId in mediaHeldIds) {
            if (heldId !in known) {
                heldMediaDao.deleteForHeld(heldId)
            }
        }
    }

    suspend fun isBlocked(sender: String): Boolean = withContext(Dispatchers.IO) {
        blockedSenderDao.get(normalizeSender(sender)) != null
    }

    suspend fun unblock(sender: String) = withContext(Dispatchers.IO) {
        blockedSenderDao.delete(normalizeSender(sender))
    }

    private fun mutexFor(heldId: String): Mutex =
        restoreMutexes.getOrPut(heldId) { Mutex() }

    private suspend fun deleteHeldIfUnclaimed(heldId: String): Boolean {
        return mutexFor(heldId).withLock {
            val held = heldMessageDao.getById(heldId) ?: return@withLock false
            if (held.restoreClaimedAt != null) return@withLock false
            deleteHeldAndMedia(heldId)
            true
        }
    }

    /**
     * Single restore path for [moveToInbox] and [commitExpired].
     *
     * 1. In-process mutex per [heldId].
     * 2. Room claim (`restoreClaimedAt`) so a retry after death cannot race.
     * 3. Existing [HeldMessageEntity.restoreUri] → delete held+media only.
     * 4. Row gone → success no-op.
     * 5. Insert; on success write `restoreUri` then delete; on failure clear the claim.
     */
    private suspend fun restoreHeld(
        heldId: String,
        readFor: (HeldMessageEntity) -> Boolean,
    ): RestoreOutcome = mutexFor(heldId).withLock {
        val held = heldMessageDao.getById(heldId)
            ?: return@withLock RestoreOutcome.AlreadyDone(uri = null)

        val existingUri = held.restoreUri
        if (existingUri != null) {
            deleteHeldAndMedia(heldId)
            return@withLock RestoreOutcome.AlreadyDone(uri = parseRestoreUri(existingUri))
        }

        val claimedRows = heldMessageDao.claimRestore(heldId, System.currentTimeMillis())
        val toRestore = if (claimedRows == 0) {
            val again = heldMessageDao.getById(heldId)
                ?: return@withLock RestoreOutcome.AlreadyDone(uri = null)
            val uri = again.restoreUri
            if (uri != null) {
                deleteHeldAndMedia(heldId)
                return@withLock RestoreOutcome.AlreadyDone(uri = parseRestoreUri(uri))
            }
            // Stale claim from a dead process — take over rather than leave it stuck.
            again
        } else {
            held
        }

        when (val result = restoreToProvider(toRestore, read = readFor(toRestore))) {
            is SmsRepository.WriteResult.Success -> {
                try {
                    result.uri?.toString()?.let { heldMessageDao.setRestoreUri(heldId, it) }
                } finally {
                    deleteHeldAndMedia(heldId)
                }
                RestoreOutcome.Inserted(result)
            }
            is SmsRepository.WriteResult.RoleNotHeld,
            is SmsRepository.WriteResult.Failed,
            -> {
                heldMessageDao.clearRestoreClaim(heldId)
                RestoreOutcome.Unavailable(result)
            }
        }
    }

    private fun parseRestoreUri(raw: String): Uri? =
        runCatching { Uri.parse(raw) }.getOrNull()

    private suspend fun restoreToProvider(
        held: HeldMessageEntity,
        read: Boolean,
    ): SmsRepository.WriteResult {
        return if (held.transportKind == TransportKind.MMS) {
            val parts = reconstructParts(held.id)
            mmsRepository.insertInbox(
                IncomingMms(
                    originator = held.sender,
                    participants = decodeParticipants(held.participants)
                        .filter { it != held.sender },
                    body = held.body,
                    subject = held.subject,
                    receivedAtMillis = held.receivedAt,
                    hasPhoto = parts.any { it.isImage },
                    hasAnyPart = parts.any { !it.isTextPlain },
                    transactionId = held.transactionId,
                    contentLocation = held.contentLocation,
                    parts = parts,
                    subscriptionId = held.subscriptionId,
                ),
                read = read,
            )
        } else {
            smsRepository.insertInbox(
                address = held.sender,
                body = held.body,
                dateMillis = held.receivedAt,
                read = read,
            )
        }
    }

    private suspend fun reconstructParts(heldId: String): List<MmsPart> {
        val dir = mediaDir(heldId)
        return heldMediaDao.getForHeld(heldId).mapNotNull { row ->
            val file = File(dir, row.fileName)
            if (!file.isFile) return@mapNotNull null
            MmsPart(
                contentType = row.contentType,
                bytes = file.readBytes(),
            )
        }
    }

    private suspend fun deleteHeldAndMedia(heldId: String) {
        mediaDir(heldId).deleteRecursively()
        heldMediaDao.deleteForHeld(heldId)
        heldMessageDao.deleteById(heldId)
    }

    private fun mediaDir(heldId: String): File =
        File(File(context.filesDir, HELD_MEDIA_DIR), heldId)

    private fun normalizeSender(sender: String): String {
        val digits = sender.filter { it.isDigit() }
        return if (digits.length >= 7) digits.takeLast(10) else sender.trim().lowercase()
    }

    data class CommitSummary(
        val deleted: Int,
        val filed: Int,
        val skipped: Int,
    )

    private sealed interface RestoreOutcome {
        data class Inserted(val result: SmsRepository.WriteResult.Success) : RestoreOutcome
        data class AlreadyDone(val uri: Uri?) : RestoreOutcome
        data class Unavailable(val result: SmsRepository.WriteResult) : RestoreOutcome
    }

    companion object {
        const val HELD_MEDIA_DIR = "held_media"
        private const val PARTICIPANT_SEP = '\u001e'

        internal fun encodeParticipants(participants: List<String>): String? {
            val cleaned = participants.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) return null
            return cleaned.joinToString(PARTICIPANT_SEP.toString())
        }

        internal fun decodeParticipants(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(PARTICIPANT_SEP).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
