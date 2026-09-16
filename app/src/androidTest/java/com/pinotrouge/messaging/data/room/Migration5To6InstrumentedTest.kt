package com.pinotrouge.messaging.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.room.PinotDatabase
import com.pinotrouge.messaging.data.room.PinotMigrations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves [PinotMigrations.MIGRATION_5_6] adds restore claim/URI columns
 * without destroying held rows. Uses the same [PinotMigrations.ALL] list
 * production registers in [com.pinotrouge.messaging.di.DataModule].
 *
 * Built without [androidx.room.testing.MigrationTestHelper]: Room 2.8's schema
 * bundle deserializer currently throws [AbstractMethodError] against the project's
 * kotlinx-serialization stack on device.
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6InstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var openHelper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV5Schema(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
    }

    @After
    fun tearDown() {
        openHelper.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun production_list_contains_migration_5_6() {
        assertTrue(
            PinotMigrations.ALL.any { it.startVersion == 5 && it.endVersion == 6 },
        )
        assertEquals(PinotMigrations.MIGRATION_5_6, PinotMigrations.ALL.last())
    }

    @Test
    fun migration_5_6_adds_claim_columns_and_preserves_rows() {
        val db = openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO held_messages
            (id, sender, body, receivedAt, heldAt, ruleId, reason, expiresAt,
             heldBy, wasRead, transportKind)
            VALUES ('h1', '555', 'body', 1, 2, 'r1', 'Filter: x', 3, 'ARRIVAL', 0, 'SMS')
            """.trimIndent(),
        )

        PinotMigrations.MIGRATION_5_6.migrate(db)

        db.query(
            "SELECT restoreClaimedAt, restoreUri, body FROM held_messages WHERE id = 'h1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals("body", c.getString(2))
        }
        db.query("SELECT COUNT(*) FROM held_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun room_opens_v5_file_with_production_addMigrations_list() {
        val v5 = openHelper.writableDatabase
        v5.execSQL(
            """
            INSERT INTO held_messages
            (id, sender, body, receivedAt, heldAt, ruleId, reason, expiresAt,
             heldBy, wasRead, transportKind)
            VALUES ('h-prod', '555', 'kept', 1, 2, 'r1', 'x', 3, 'ARRIVAL', 0, 'SMS')
            """.trimIndent(),
        )
        v5.close()

        val room = Room.databaseBuilder(context, PinotDatabase::class.java, TEST_DB)
            .addMigrations(*PinotMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val held = room.query(
                "SELECT body, restoreClaimedAt, restoreUri FROM held_messages WHERE id = 'h-prod'",
                null,
            )
            held.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("kept", c.getString(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration_5_6_test.db"
        const val V5_IDENTITY_HASH = "3e3fc78a002d96df32e2c08dd8e2337f"

        fun createV5Schema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `rules` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `order` INTEGER NOT NULL,
                    `match` TEXT NOT NULL,
                    `conditions` TEXT NOT NULL,
                    `actions` TEXT NOT NULL,
                    `deleteAfterDays` INTEGER NOT NULL,
                    `autoReplyText` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `held_messages` (
                    `id` TEXT NOT NULL,
                    `sender` TEXT NOT NULL,
                    `body` TEXT NOT NULL,
                    `receivedAt` INTEGER NOT NULL,
                    `heldAt` INTEGER NOT NULL,
                    `ruleId` TEXT NOT NULL,
                    `reason` TEXT NOT NULL,
                    `expiresAt` INTEGER NOT NULL,
                    `heldBy` TEXT NOT NULL,
                    `wasRead` INTEGER NOT NULL,
                    `transportKind` TEXT NOT NULL,
                    `subject` TEXT,
                    `participants` TEXT,
                    `subscriptionId` INTEGER,
                    `transactionId` TEXT,
                    `contentLocation` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_held_messages_expiresAt` ON `held_messages` (`expiresAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_held_messages_ruleId` ON `held_messages` (`ruleId`)",
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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `blocked_senders` (
                    `sender` TEXT NOT NULL,
                    `blockedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`sender`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `rule_stats` (
                    `ruleId` TEXT NOT NULL,
                    `caughtCount` INTEGER NOT NULL,
                    `lastCaughtAt` INTEGER,
                    `sweptCount` INTEGER NOT NULL,
                    PRIMARY KEY(`ruleId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `archived_threads` (
                    `threadId` INTEGER NOT NULL,
                    PRIMARY KEY(`threadId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$V5_IDENTITY_HASH')",
            )
        }
    }
}
