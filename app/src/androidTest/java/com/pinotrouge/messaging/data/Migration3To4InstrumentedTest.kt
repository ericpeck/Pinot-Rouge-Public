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
 * Proves [PinotMigrations.MIGRATION_3_4] adds [wasRead] without destroying held rows.
 */
@RunWith(AndroidJUnit4::class)
class Migration3To4InstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var openHelper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
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
                                    `heldBy` TEXT NOT NULL DEFAULT 'ARRIVAL',
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
    fun migration_3_4_adds_wasRead_and_preserves_held_rows() {
        val db = openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO held_messages
            (id, sender, body, receivedAt, heldAt, ruleId, reason, expiresAt, heldBy)
            VALUES ('h1', '555', 'body', 1, 2, 'r1', 'Filter: x', 3, 'SWEEP')
            """.trimIndent(),
        )

        PinotMigrations.MIGRATION_3_4.migrate(db)

        db.query("SELECT wasRead, heldBy, sender FROM held_messages WHERE id = 'h1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0)) // default false for existing rows
            assertEquals("SWEEP", c.getString(1))
            assertEquals("555", c.getString(2))
        }
        db.query("SELECT COUNT(*) FROM held_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration_3_4_test.db"
    }
}
