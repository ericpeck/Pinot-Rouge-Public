package com.pinotrouge.messaging.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.room.PinotMigrations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves [PinotMigrations.MIGRATION_1_2] creates `archived_threads` **without**
 * destroying held quarantine rows — the third promise.
 *
 * Built without [androidx.room.testing.MigrationTestHelper]: Room 2.8's schema
 * bundle deserializer currently throws [AbstractMethodError] against the project's
 * kotlinx-serialization stack on device. Behaviour under test is the same —
 * open at v1 shape, run the migration, assert data and the new table.
 */
@RunWith(AndroidJUnit4::class)
class Migration1To2InstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var openHelper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // v1 held_messages shape from app/schemas/.../1.json
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
                                    PRIMARY KEY(`id`)
                                )
                                """.trimIndent(),
                            )
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
    fun migration_1_2_creates_archive_table_and_preserves_held_rows() {
        val db = openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO held_messages
            (id, sender, body, receivedAt, heldAt, ruleId, reason, expiresAt)
            VALUES
            ('held-1', '18445550192', 'PRE-APPROVED spam', 1000, 2000, 'r3',
             'Filter: Loan and crypto offers', 999999),
            ('held-2', '88022', 'blocked later', 1001, 2001, 'system',
             'Blocked sender', 999999)
            """.trimIndent(),
        )
        db.query("SELECT COUNT(*) FROM held_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }

        PinotMigrations.MIGRATION_1_2.migrate(db)

        // Held rows must survive — a wipe would still "open" the app.
        db.query("SELECT id, body FROM held_messages ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("held-1", c.getString(0))
            assertEquals("PRE-APPROVED spam", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals("held-2", c.getString(0))
            assertEquals(2, c.count)
        }
        // New table exists and is empty.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='archived_threads'",
        ).use { c ->
            assertTrue("archived_threads must exist after migration", c.moveToFirst())
        }
        db.query("SELECT COUNT(*) FROM archived_threads").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // Can insert into the new table (shape matches production CREATE).
        db.execSQL("INSERT INTO archived_threads (threadId) VALUES (42)")
        db.query("SELECT threadId FROM archived_threads").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-pinot"
    }
}
