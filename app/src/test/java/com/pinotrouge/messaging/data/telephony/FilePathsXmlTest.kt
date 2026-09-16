package com.pinotrouge.messaging.data.telephony

import android.telephony.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FilePathsXmlTest {

    @Test
    fun file_paths_covers_send_and_download_cache_only() {
        val file = filePathsFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        val paths = mutableListOf<Pair<String, String>>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            paths += el.tagName to el.getAttribute("path")
            val path = el.getAttribute("path")
            val name = el.getAttribute("name")
            assertFalse("held_media must never be a FileProvider path", "held_media" in path)
            assertFalse("held_media must never be a FileProvider name", "held_media" in name)
            assertEquals("cache-path", el.tagName)
        }
        assertTrue(paths.any { it.second.contains("mms_send") })
        assertTrue(paths.any { it.second.contains("mms_download") })
        assertEquals(2, paths.size)
    }

    @Test
    fun missing_subscription_extra_uses_default() {
        assertNull(subscriptionIdFromExtra(hasExtra = false, value = 1))
        assertNull(
            subscriptionIdFromExtra(
                hasExtra = true,
                value = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            ),
        )
        assertEquals(2, subscriptionIdFromExtra(hasExtra = true, value = 2))
    }

    private fun filePathsFile(): File {
        val candidates = listOf(
            File("src/main/res/xml/file_paths.xml"),
            File("app/src/main/res/xml/file_paths.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("file_paths.xml not found from ${File(".").absolutePath}")
    }
}
