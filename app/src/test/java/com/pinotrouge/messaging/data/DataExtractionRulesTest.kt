package com.pinotrouge.messaging.data

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DataExtractionRulesTest {

    @Test
    fun data_extraction_rules_exclude_held_media() {
        val file = rulesFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val excludes = mutableListOf<Pair<String, String>>()
        val nodes = doc.getElementsByTagName("exclude")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            excludes += el.getAttribute("domain") to el.getAttribute("path")
        }
        assertTrue(
            "held_media/ must be excluded from device-to-device transfer",
            excludes.any { it.first == "file" && it.second == "held_media/" },
        )
        assertTrue(
            "pinot_rouge.db must be excluded from device-to-device transfer",
            excludes.any { it.first == "database" && it.second == "pinot_rouge.db" },
        )
        assertTrue(
            "WAL must be excluded from device-to-device transfer",
            excludes.any { it.first == "database" && it.second == "pinot_rouge.db-wal" },
        )
        assertTrue(
            "SHM must be excluded from device-to-device transfer",
            excludes.any { it.first == "database" && it.second == "pinot_rouge.db-shm" },
        )
        assertTrue(
            "DataStore must be excluded from device-to-device transfer",
            excludes.any { it.first == "file" && it.second == "datastore/" },
        )
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
