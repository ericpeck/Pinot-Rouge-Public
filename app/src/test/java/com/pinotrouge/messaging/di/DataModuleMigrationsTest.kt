package com.pinotrouge.messaging.di

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataModuleMigrationsTest {

    @Test
    fun data_module_registers_migration_4_5_via_production_list() {
        val file = sourceFile()
        val text = file.readText()
        assertTrue("DataModule must add PinotMigrations.ALL", "PinotMigrations.ALL" in text)
        assertTrue(
            "must not call fallbackToDestructiveMigration",
            ".fallbackToDestructiveMigration" !in text,
        )
    }

    @Test
    fun production_migrations_include_5_to_6_and_are_not_destructive() {
        val text = migrationsFile().readText()
        assertTrue("must define MIGRATION_5_6", "MIGRATION_5_6" in text)
        assertTrue("ALL must include 5→6", "MIGRATION_5_6," in text)
        assertTrue(
            "must not call fallbackToDestructiveMigration",
            ".fallbackToDestructiveMigration" !in text,
        )
    }

    private fun sourceFile(): File {
        val candidates = listOf(
            File("src/main/java/com/pinotrouge/messaging/di/DataModule.kt"),
            File("app/src/main/java/com/pinotrouge/messaging/di/DataModule.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("DataModule.kt not found from ${File(".").absolutePath}")
    }

    private fun migrationsFile(): File {
        val candidates = listOf(
            File("src/main/java/com/pinotrouge/messaging/data/room/Migrations.kt"),
            File("app/src/main/java/com/pinotrouge/messaging/data/room/Migrations.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Migrations.kt not found from ${File(".").absolutePath}")
    }
}
