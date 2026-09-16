package com.pinotrouge.messaging.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val order: Int,
    val match: MatchMode,
    /** JSON via [Converters] — order preserved. */
    val conditions: List<Condition>,
    /**
     * JSON array of [Action] names in insertion order.
     * Stored as a List so round-trips do not scramble [Set] iteration order.
     */
    val actions: List<Action>,
    val deleteAfterDays: Int,
    val autoReplyText: String?,
)

/**
 * How a held row was created. [ARRIVAL] is the normal SMS/MMS path;
 * [SWEEP] is a confirmed *Run filters on my inbox* move (provider copy deleted).
 */
object HeldBy {
    const val ARRIVAL = "ARRIVAL"
    const val SWEEP = "SWEEP"
}

/** How [HeldMessageEntity] restores into Telephony. Sweep stays SMS-only. */
object TransportKind {
    const val SMS = "SMS"
    const val MMS = "MMS"
}

@Entity(
    tableName = "held_messages",
    indices = [Index("expiresAt"), Index("ruleId")],
)
data class HeldMessageEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val heldAt: Long,
    val ruleId: String,
    val reason: String,
    val expiresAt: Long,
    /** [HeldBy.ARRIVAL] or [HeldBy.SWEEP]. */
    val heldBy: String = HeldBy.ARRIVAL,
    /**
     * Provider read flag captured at sweep time. Restored by [moveToInbox] for
     * [HeldBy.SWEEP] rows only. Always false for arrival holds (never in inbox).
     */
    val wasRead: Boolean = false,
    /** [TransportKind.SMS] or [TransportKind.MMS]. */
    val transportKind: String = TransportKind.SMS,
    val subject: String? = null,
    /** Record-separator-joined addresses; empty/null means originator only. */
    val participants: String? = null,
    val subscriptionId: Int? = null,
    val transactionId: String? = null,
    val contentLocation: String? = null,
    /**
     * Set while a restore is in flight (manual or expiry). Null means unclaimed.
     * Survives process death so a retry cannot insert a second inbox copy.
     */
    val restoreClaimedAt: Long? = null,
    /**
     * Provider URI written after a successful insert, before the held row is
     * deleted. A crash between those steps finishes by deleting, not inserting.
     */
    val restoreUri: String? = null,
)

/**
 * Metadata for bytes at `filesDir/held_media/<heldId>/<fileName>`.
 * No BLOB — [HeldMessageDao.observeAll] would throw on a 1.2 MB JPEG.
 */
@Entity(
    tableName = "held_media",
    indices = [Index("heldId")],
)
data class HeldMediaEntity(
    @PrimaryKey val id: String,
    val heldId: String,
    val contentType: String,
    val seq: Int,
    val fileName: String,
    val byteSize: Long,
)

@Entity(tableName = "blocked_senders")
data class BlockedSenderEntity(
    /** Normalised number / sender id used for matching. */
    @PrimaryKey val sender: String,
    val blockedAt: Long,
)

@Entity(tableName = "rule_stats")
data class RuleStatsEntity(
    @PrimaryKey val ruleId: String,
    val caughtCount: Int,
    val lastCaughtAt: Long?,
    /** Cumulative messages moved into Filtered by inbox sweep (not arrival). */
    val sweptCount: Int = 0,
)

/**
 * Local filing only — Telephony is never written. No [archivedAt]: archived
 * conversations are kept forever (2026-08-09 decision).
 */
@Entity(tableName = "archived_threads")
data class ArchivedThreadEntity(
    @PrimaryKey val threadId: Long,
)
