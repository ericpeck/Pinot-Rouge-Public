package com.pinotrouge.messaging.data.telephony

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Contacts lookup cache: failed queries must not be remembered, and a contact
 * saved after the first miss must be recognised without a process restart.
 */
@RunWith(AndroidJUnit4::class)
class ContactsRepositoryInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var repo: ContactsRepository
    private val insertedRawContactIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CONTACTS,
        )
        repo = ContactsRepository(context)
        repo.queryOverride = null
        repo.queryCount.set(0)
        repo.clearCache()
    }

    @After
    fun tearDown() {
        repo.queryOverride = null
        repo.clearCache()
        insertedRawContactIds.forEach { id ->
            shell(
                "content delete --uri content://com.android.contacts/raw_contacts " +
                    "--where \"_id=$id\"",
            )
        }
        insertedRawContactIds.clear()
    }

    @Test
    fun failed_lookup_is_not_cached() = runBlocking {
        repo.queryOverride = { error("simulated missing READ_CONTACTS") }
        assertFalse(repo.isKnownContact(PROBE_NUMBER))
        assertEquals(1, repo.queryCount.get())

        repo.queryOverride = { true to "Ada" }
        assertTrue(
            "a later successful lookup must not be poisoned by the failed one",
            repo.isKnownContact(PROBE_NUMBER),
        )
        assertEquals(2, repo.queryCount.get())
    }

    @Test
    fun unknown_then_saved_contact_is_recognised() = runBlocking {
        val number = uniqueNumber()
        assertFalse(repo.isKnownContact(number))

        insertContact(displayName = "Calista", number = number)

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        var known = false
        while (System.currentTimeMillis() < deadline) {
            known = repo.isKnownContact(number)
            if (known) break
            Thread.sleep(50)
        }
        assertTrue("observer (or next lookup) must see the newly saved contact", known)
    }

    @Test
    fun clearCache_drops_cached_entries() = runBlocking {
        repo.queryOverride = { false to null }
        assertFalse(repo.isKnownContact(PROBE_NUMBER))
        assertEquals(1, repo.queryCount.get())

        repo.clearCache()
        assertFalse(repo.isKnownContact(PROBE_NUMBER))
        assertEquals(2, repo.queryCount.get())
    }

    @Test
    fun cache_deduplicates_repeat_lookups() = runBlocking {
        repo.queryOverride = { false to null }
        assertFalse(repo.isKnownContact(PROBE_NUMBER))
        assertFalse(repo.isKnownContact(PROBE_NUMBER))
        assertEquals(1, repo.queryCount.get())
    }

    private fun insertContact(displayName: String, number: String) {
        shell(
            "content insert --uri content://com.android.contacts/raw_contacts " +
                "--bind account_name:s:test --bind account_type:s:test",
        )
        val id = latestRawContactId()
        insertedRawContactIds += id
        shell(
            "content insert --uri content://com.android.contacts/data " +
                "--bind raw_contact_id:i:$id " +
                "--bind mimetype:s:vnd.android.cursor.item/name " +
                "--bind data1:s:$displayName",
        )
        shell(
            "content insert --uri content://com.android.contacts/data " +
                "--bind raw_contact_id:i:$id " +
                "--bind mimetype:s:vnd.android.cursor.item/phone_v2 " +
                "--bind data1:s:$number",
        )
    }

    private fun latestRawContactId(): Long {
        val queryOut = shell(
            "content query --uri content://com.android.contacts/raw_contacts --projection _id",
        )
        return queryOut.lineSequence()
            .mapNotNull { line ->
                Regex("""_id=(\d+)""").find(line)?.groupValues?.get(1)?.toLong()
            }
            .maxOrNull()
            ?: error("no raw contact after insert: '$queryOut'")
    }

    private fun uniqueNumber(): String {
        val tail = (System.currentTimeMillis() % 10_000_000L).toString().padStart(7, '0')
        return "1555$tail"
    }

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        FileInputStream(pfd.fileDescriptor).use { input ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            pfd.close()
            return text
        }
    }

    companion object {
        private const val PROBE_NUMBER = "18445550192"
    }
}
