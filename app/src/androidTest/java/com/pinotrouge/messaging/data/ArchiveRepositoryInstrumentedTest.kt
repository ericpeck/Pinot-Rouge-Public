package com.pinotrouge.messaging.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.repo.ArchiveRepository
import com.pinotrouge.messaging.util.createInMemoryDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Archive is local Room filing only — never a Telephony delete.
 * [ArchiveRepository] takes only [com.pinotrouge.messaging.data.room.ArchivedThreadDao];
 * there is no provider write path, so provider row count is unchanged by construction.
 * These tests pin archive / unarchive / idempotent re-archive on the repository.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var archive: ArchiveRepository

    @Before
    fun setUp() {
        db = createInMemoryDb(context)
        archive = ArchiveRepository(db.archivedThreadDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun archive_then_unarchive_round_trip() = runBlocking {
        assertFalse(archive.isArchived(12L))
        archive.archive(listOf(12L, 34L))
        assertTrue(archive.isArchived(12L))
        assertTrue(archive.isArchived(34L))
        assertEquals(setOf(12L, 34L), archive.observeArchivedIds().first())

        archive.unarchive(12L)
        assertFalse(archive.isArchived(12L))
        assertTrue(archive.isArchived(34L))
        assertEquals(setOf(34L), archive.observeArchivedIds().first())
    }

    @Test
    fun archive_is_idempotent() = runBlocking {
        archive.archive(99L)
        archive.archive(99L)
        assertEquals(1, archive.observeCount().first())
    }
}
