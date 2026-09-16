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
 * Proves [PinotMigrations.MIGRATION_2_3] adds heldBy / sweptCount without
 * destroying held quarantine rows.
 */
@RunWith(AndroidJUnit4::class)
class Migration2To3InstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var openHelper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
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
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `rule_stats` (
                                    `ruleId` TEXT NOT NULL,
                                    `caughtCount` INTEGER NOT NULL,
                                    `lastCaughtAt` INTEGER,
                                    PRIMARY KEY(`ruleId`)
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
    fun migration_2_3_adds_columns_and_preserves_held_rows() {
        val db = openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO held_messages
            (id, sender, body, receivedAt, heldAt, ruleId, reason, expiresAt)
            VALUES ('h1', '555', 'body', 1, 2, 'r1', 'Filter: x', 3)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO rule_stats (ruleId, caughtCount, lastCaughtAt)
            VALUES ('r1', 5, 99)
            """.trimIndent(),
        )

        PinotMigrations.MIGRATION_2_3.migrate(db)

        db.query("SELECT heldBy FROM held_messages WHERE id = 'h1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("ARRIVAL", c.getString(0))
        }
        db.query("SELECT sweptCount, caughtCount FROM rule_stats WHERE ruleId = 'r1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(5, c.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM held_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration_2_3_test.db"
    }
}
