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
 * Proves [PinotMigrations.MIGRATION_4_5] adds restore metadata and `held_media`
 * without destroying held rows. Uses the same [PinotMigrations.ALL] list
 * production registers in [com.pinotrouge.messaging.di.DataModule].
 *
 * Built without [androidx.room.testing.MigrationTestHelper]: Room 2.8's schema
 * bundle deserializer currently throws [AbstractMethodError] against the project's
 * kotlinx-serialization stack on device.
 */
@RunWith(AndroidJUnit4::class)
class Migration4To5InstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var openHelper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV4Schema(db)
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
    fun production_list_contains_migration_4_5() {
        assertTrue(
            PinotMigrations.ALL.any { it.startVersion == 4 && it.endVersion == 5 },
        )
    }

    @Test
    fun migration_4_5_adds_columns_and_held_media_and_preserves_rows() {
        val db = openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO held_messages
            (id, sender, body, receivedAt, heldAt, ruleId, reason, expiresAt, heldBy, wasRead)
            VALUES ('h1', '555', 'body', 1, 2, 'r1', 'Filter: x', 3, 'ARRIVAL', 0)
            """.trimIndent(),
        )

        PinotMigrations.MIGRATION_4_5.migrate(db)

        db.query(
            "SELECT transportKind, subject, participants, subscriptionId, transactionId, contentLocation FROM held_messages WHERE id = 'h1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("SMS", c.getString(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
            assertTrue(c.isNull(3))
            assertTrue(c.isNull(4))
            assertTrue(c.isNull(5))
        }
        db.query("SELECT COUNT(*) FROM held_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'held_media'").use { c ->
            assertTrue("held_media must exist after 4→5", c.moveToFirst())
        }
        db.query("PRAGMA table_info(held_media)").use { c ->
            val columns = mutableListOf<String>()
            while (c.moveToNext()) columns += c.getString(1)
            assertTrue("id" in columns)
            assertTrue("heldId" in columns)
            assertTrue("contentType" in columns)
            assertTrue("seq" in columns)
            assertTrue("fileName" in columns)
            assertTrue("byteSize" in columns)
            assertTrue("no BLOB column", columns.none { it.equals("bytes", ignoreCase = true) || it.equals("blob", ignoreCase = true) })
        }
    }

    @Test
    fun room_opens_v4_file_with_production_addMigrations_list() {
        val v4 = openHelper.writableDatabase
        v4.execSQL(
            """
            INSERT INTO held_messages
            (id, sender, body, receivedAt, heldAt, ruleId, reason, expiresAt, heldBy, wasRead)
            VALUES ('h-prod', '555', 'kept', 1, 2, 'r1', 'x', 3, 'ARRIVAL', 0)
            """.trimIndent(),
        )
        v4.close()

        val room = Room.databaseBuilder(context, PinotDatabase::class.java, TEST_DB)
            .addMigrations(*PinotMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val held = room.query("SELECT body, transportKind FROM held_messages WHERE id = 'h-prod'", null)
            held.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("kept", c.getString(0))
                assertEquals("SMS", c.getString(1))
            }
            room.query("SELECT COUNT(*) FROM held_media", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration_4_5_test.db"
        const val V4_IDENTITY_HASH = "00b5e7ba728b9daf223a21157fb94238"

        fun createV4Schema(db: SupportSQLiteDatabase) {
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
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$V4_IDENTITY_HASH')",
            )
        }
    }
}
