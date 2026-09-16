package com.pinotrouge.messaging.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RuleEntity::class,
        HeldMessageEntity::class,
        HeldMediaEntity::class,
        BlockedSenderEntity::class,
        RuleStatsEntity::class,
        ArchivedThreadEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PinotDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun heldMessageDao(): HeldMessageDao
    abstract fun heldMediaDao(): HeldMediaDao
    abstract fun blockedSenderDao(): BlockedSenderDao
    abstract fun ruleStatsDao(): RuleStatsDao
    abstract fun archivedThreadDao(): ArchivedThreadDao

    companion object {
        const val NAME = "pinot_rouge.db"
    }
}
