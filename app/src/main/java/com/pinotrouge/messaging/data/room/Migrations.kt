package com.pinotrouge.messaging.data.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations for [PinotDatabase]. Never use fallbackToDestructiveMigration —
 * held messages live in this database and destroying it would break the third promise.
 */
object PinotMigrations {
    /**
     * v1 → v2: add [archived_threads] for local archive filing (no Telephony write,
     * no expiry column — archived conversations are kept forever).
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `archived_threads` (
                    `threadId` INTEGER NOT NULL,
                    PRIMARY KEY(`threadId`)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v2 → v3: [held_messages.heldBy] (ARRIVAL vs SWEEP) and [rule_stats.sweptCount]
     * for the inbox sweep counter (`feat/apply-filters-to-inbox`).
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE `held_messages`
                ADD COLUMN `heldBy` TEXT NOT NULL DEFAULT 'ARRIVAL'
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE `rule_stats`
                ADD COLUMN `sweptCount` INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
        }
    }

    /**
     * v3 → v4: [held_messages.wasRead] so sweep undo restores provider read state
     * (`fix/sweep-followups`). Default false keeps ARRIVAL restores unread.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE `held_messages`
                ADD COLUMN `wasRead` INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
        }
    }

    /**
     * v4 → v5: MMS restore metadata on [held_messages] and the [held_media]
     * sidecar (paths only — never a BLOB).
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE `held_messages`
                ADD COLUMN `transportKind` TEXT NOT NULL DEFAULT 'SMS'
                """.trimIndent(),
            )
            db.execSQL(
                "ALTER TABLE `held_messages` ADD COLUMN `subject` TEXT",
            )
            db.execSQL(
                "ALTER TABLE `held_messages` ADD COLUMN `participants` TEXT",
            )
            db.execSQL(
                "ALTER TABLE `held_messages` ADD COLUMN `subscriptionId` INTEGER",
            )
            db.execSQL(
                "ALTER TABLE `held_messages` ADD COLUMN `transactionId` TEXT",
            )
            db.execSQL(
                "ALTER TABLE `held_messages` ADD COLUMN `contentLocation` TEXT",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `held_media` (
                    `id` TEXT NOT NULL,
                    `heldId` TEXT NOT NULL,
                    `contentType` TEXT NOT NULL,
                    `seq` INTEGER NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `byteSize` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_held_media_heldId` ON `held_media` (`heldId`)",
            )
        }
    }

    /**
     * v5 → v6: restore claim + provider URI on [held_messages] so concurrent
     * [moveToInbox] / expiry cannot insert the same held SMS twice
     * (`fix/quarantine-restore-claim`).
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `held_messages` ADD COLUMN `restoreClaimedAt` INTEGER",
            )
            db.execSQL(
                "ALTER TABLE `held_messages` ADD COLUMN `restoreUri` TEXT",
            )
        }
    }

    /** Production list — [com.pinotrouge.messaging.di.DataModule] and tests share this. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    )
}
