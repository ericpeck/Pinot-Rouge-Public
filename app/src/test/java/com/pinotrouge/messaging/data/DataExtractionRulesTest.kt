package com.pinotrouge.messaging.data

import com.pinotrouge.messaging.sms.SendToActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DataExtractionRulesTest {

    @Test
    fun data_extraction_rules_exclude_message_bearing_app_private_stores() {
        val excludes = excludePairs()
        assertTrue(
            "held_media/ must be excluded from device-to-device transfer",
            excludes.contains("file" to "held_media/"),
        )
        assertTrue(
            "pinot_rouge.db must be excluded from device-to-device transfer",
            excludes.contains("database" to "pinot_rouge.db"),
        )
        assertTrue(
            "WAL must be excluded from device-to-device transfer",
            excludes.contains("database" to "pinot_rouge.db-wal"),
        )
        assertTrue(
            "SHM must be excluded from device-to-device transfer",
            excludes.contains("database" to "pinot_rouge.db-shm"),
        )
        assertTrue(
            "DataStore must be excluded from device-to-device transfer",
            excludes.contains("file" to "datastore/"),
        )
        assertEquals(
            "pending_compose prefs file name must match the sharedpref exclude path",
            "${SendToActivity.PREFS}.xml",
            "pending_compose.xml",
        )
        assertTrue(
            "pending_compose.xml must be excluded from device-to-device transfer",
            excludes.contains("sharedpref" to "pending_compose.xml"),
        )
        assertTrue(
            "WorkManager database must be excluded from device-to-device transfer",
            excludes.contains("database" to "androidx.work.workdb"),
        )
        assertTrue(excludes.contains("database" to "androidx.work.workdb-wal"))
        assertTrue(excludes.contains("database" to "androidx.work.workdb-shm"))
    }

    @Test
    fun device_transfer_has_no_cloud_backup_section() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile())
        assertEquals(0, doc.getElementsByTagName("cloud-backup").length)
        assertTrue(doc.getElementsByTagName("device-transfer").length >= 1)
    }

    private fun excludePairs(): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile())
        val excludes = mutableListOf<Pair<String, String>>()
        val nodes = doc.getElementsByTagName("exclude")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val domain = el.getAttribute("domain")
            val path = el.getAttribute("path")
            assertTrue("exclude domain must not be blank", domain.isNotBlank())
            assertTrue("exclude path must not be blank", path.isNotBlank())
            excludes += domain to path
        }
        return excludes
    }

    private fun rulesFile(): File {
        val candidates = listOf(
            File("src/main/res/xml/data_extraction_rules.xml"),
            File("app/src/main/res/xml/data_extraction_rules.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("data_extraction_rules.xml not found from ${File(".").absolutePath}")
    }
}
