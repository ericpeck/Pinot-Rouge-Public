package com.pinotrouge.messaging.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.room.PinotDatabase
import com.pinotrouge.messaging.sms.SendToActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Asserts D2D exclude paths against the real on-device storage domains.
 * This is not a device-to-device migration test.
 */
@RunWith(AndroidJUnit4::class)
class DataExtractionRulesInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pendingComposePrefsLiveAtExcludedSharedPrefPath() {
        val prefs = context.getSharedPreferences(SendToActivity.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(SendToActivity.KEY_RECIPIENT, "5550100").commit()
        try {
            val file = File(context.applicationInfo.dataDir, "shared_prefs/${SendToActivity.PREFS}.xml")
            assertTrue("expected $file", file.exists())
            assertEquals("pending_compose.xml", file.name)
            assertTrue(excludePairs().contains("sharedpref" to file.name))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun roomDatabaseNameMatchesExcludedDatabasePath() {
        assertEquals("pinot_rouge.db", PinotDatabase.NAME)
        val excludes = excludePairs()
        assertTrue(excludes.contains("database" to PinotDatabase.NAME))
        assertTrue(excludes.contains("database" to "${PinotDatabase.NAME}-wal"))
        assertTrue(excludes.contains("database" to "${PinotDatabase.NAME}-shm"))
    }

    @Test
    fun workManagerDatabasePathIsExcluded() {
        val excludes = excludePairs()
        assertTrue(excludes.contains("database" to "androidx.work.workdb"))
        assertTrue(excludes.contains("database" to "androidx.work.workdb-wal"))
        assertTrue(excludes.contains("database" to "androidx.work.workdb-shm"))
    }

    @Test
    fun mmsCacheLivesUnderCacheDir_notADataExtractionDomain() {
        val send = File(context.cacheDir, "mms_send")
        val download = File(context.cacheDir, "mms_download")
        send.mkdirs()
        download.mkdirs()
        assertTrue(send.isDirectory)
        assertTrue(download.isDirectory)
        assertEquals("mms_send", send.name)
        assertEquals("mms_download", download.name)
        val domains = excludePairs().map { it.first }.toSet()
        assertTrue("cache is not a valid data-extraction domain", !domains.contains("cache"))
    }

    private fun excludePairs(): List<Pair<String, String>> {
        val parser = context.resources.getXml(com.pinotrouge.messaging.R.xml.data_extraction_rules)
        val out = mutableListOf<Pair<String, String>>()
        try {
            var event = parser.eventType
            while (event != android.content.res.XmlResourceParser.END_DOCUMENT) {
                if (event == android.content.res.XmlResourceParser.START_TAG &&
                    parser.name == "exclude"
                ) {
                    out += parser.getAttributeValue(null, "domain") to
                        parser.getAttributeValue(null, "path")
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return out
    }
}
