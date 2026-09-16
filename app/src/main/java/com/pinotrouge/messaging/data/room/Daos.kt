package com.pinotrouge.messaging.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY `order` ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY `order` ASC")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: String): RuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<RuleEntity>)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM rules")
    suspend fun deleteAll()
}

@Dao
interface HeldMessageDao {
    @Query("SELECT * FROM held_messages ORDER BY heldAt DESC")
    fun observeAll(): Flow<List<HeldMessageEntity>>

    @Query("SELECT * FROM held_messages WHERE id = :id")
    suspend fun getById(id: String): HeldMessageEntity?

    @Query("SELECT id FROM held_messages")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM held_messages WHERE expiresAt <= :now")
    suspend fun getExpired(now: Long): List<HeldMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: HeldMessageEntity)

    @Query("DELETE FROM held_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Claim an unclaimed row for restore. Returns 1 if this caller won,
     * 0 if the row is gone or already claimed.
     */
    @Query(
        """
        UPDATE held_messages
        SET restoreClaimedAt = :now
        WHERE id = :id AND restoreClaimedAt IS NULL
        """,
    )
    suspend fun claimRestore(id: String, now: Long): Int

    @Query("UPDATE held_messages SET restoreUri = :uri WHERE id = :id")
    suspend fun setRestoreUri(id: String, uri: String)

    @Query(
        """
        UPDATE held_messages
        SET restoreClaimedAt = NULL
        WHERE id = :id AND restoreUri IS NULL
        """,
    )
    suspend fun clearRestoreClaim(id: String): Int

    @Query("DELETE FROM held_messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM held_messages")
    fun observeCount(): Flow<Int>

    /** Still-held messages whose [HeldMessageEntity.heldAt] is on or after [sinceMillis]. */
    @Query("SELECT COUNT(*) FROM held_messages WHERE heldAt >= :sinceMillis")
    fun observeCountSince(sinceMillis: Long): Flow<Int>
}

@Dao
interface HeldMediaDao {
    @Query("SELECT * FROM held_media WHERE heldId = :heldId ORDER BY seq ASC")
    suspend fun getForHeld(heldId: String): List<HeldMediaEntity>

    @Query("SELECT DISTINCT heldId FROM held_media")
    suspend fun getHeldIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: HeldMediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<HeldMediaEntity>)

    @Query("DELETE FROM held_media WHERE heldId = :heldId")
    suspend fun deleteForHeld(heldId: String)

    @Query("DELETE FROM held_media")
    suspend fun deleteAll()
}

@Dao
interface BlockedSenderDao {
    @Query("SELECT * FROM blocked_senders ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedSenderEntity>>

    @Query("SELECT * FROM blocked_senders WHERE sender = :sender LIMIT 1")
    suspend fun get(sender: String): BlockedSenderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BlockedSenderEntity)

    @Query("DELETE FROM blocked_senders WHERE sender = :sender")
    suspend fun delete(sender: String)
}

@Dao
interface ArchivedThreadDao {
    @Query("SELECT * FROM archived_threads ORDER BY threadId ASC")
    fun observeAll(): Flow<List<ArchivedThreadEntity>>

    @Query("SELECT threadId FROM archived_threads")
    fun observeThreadIds(): Flow<List<Long>>

    @Query("SELECT threadId FROM archived_threads")
    suspend fun getThreadIds(): List<Long>

    @Query("SELECT * FROM archived_threads WHERE threadId = :threadId LIMIT 1")
    suspend fun get(threadId: Long): ArchivedThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArchivedThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ArchivedThreadEntity>)

    @Query("DELETE FROM archived_threads WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)

    @Query("DELETE FROM archived_threads WHERE threadId IN (:threadIds)")
    suspend fun deleteAll(threadIds: List<Long>)

    @Query("SELECT COUNT(*) FROM archived_threads")
    fun observeCount(): Flow<Int>
}

@Dao
interface RuleStatsDao {
    @Query("SELECT * FROM rule_stats")
    fun observeAll(): Flow<List<RuleStatsEntity>>

    @Query("SELECT * FROM rule_stats WHERE ruleId = :ruleId")
    suspend fun get(ruleId: String): RuleStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: RuleStatsEntity)

    @Query(
        """
        UPDATE rule_stats
        SET caughtCount = caughtCount + 1, lastCaughtAt = :at
        WHERE ruleId = :ruleId
        """,
    )
    suspend fun incrementCaught(ruleId: String, at: Long): Int

    /**
     * Sweep counter only — does **not** touch [RuleStatsEntity.lastCaughtAt].
     * "Last caught" is an arrival signal (decision 3).
     */
    @Query(
        """
        UPDATE rule_stats
        SET sweptCount = sweptCount + 1
        WHERE ruleId = :ruleId
        """,
    )
    suspend fun incrementSwept(ruleId: String): Int

    @Transaction
    suspend fun recordCatch(ruleId: String, at: Long) {
        val updated = incrementCaught(ruleId, at)
        if (updated == 0) {
            upsert(
                RuleStatsEntity(
                    ruleId = ruleId,
                    caughtCount = 1,
                    lastCaughtAt = at,
                    sweptCount = 0,
                ),
            )
        }
    }

    @Transaction
    suspend fun recordSweep(ruleId: String) {
        val updated = incrementSwept(ruleId)
        if (updated == 0) {
            upsert(
                RuleStatsEntity(
                    ruleId = ruleId,
                    caughtCount = 0,
                    lastCaughtAt = null,
                    sweptCount = 1,
                ),
            )
        }
    }
}
