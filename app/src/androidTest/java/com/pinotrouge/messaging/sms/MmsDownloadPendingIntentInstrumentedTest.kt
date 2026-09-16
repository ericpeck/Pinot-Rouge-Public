package com.pinotrouge.messaging.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two retrieves at one content location must not share a completion
 * [PendingIntent]. @HiltAndroidTest so [MmsDownloadReceiver] can resolve
 * the pipeline; without it the receiver throws and the process crashes.
 *
 * 0 skipped.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MmsDownloadPendingIntentInstrumentedTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val handles = mutableListOf<PendingIntent>()
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        hiltRule.inject()
        MmsDownloadClaims.resetForTests()
        MmsDownloadCompletions.resetForTests()
    }

    @After
    fun tearDown() {
        handles.forEach { it.cancel() }
        files.forEach { it.delete() }
        MmsDownloadClaims.resetForTests()
        MmsDownloadCompletions.resetForTests()
    }

    @Test
    fun twoIdsAtOneLocation_areDistinctPendingIntents() {
        val location = "http://mmsc.example/shared-pi"
        val a = completion(mmsId = 111_001L, location = location)
        val b = completion(mmsId = 222_002L, location = location)
        assertNotEquals(
            "two messages at one content location must not share a PendingIntent",
            a,
            b,
        )
    }

    @Test
    fun firingTheFirstMarksTheFirstFailed_notTheSecond() {
        val location = "http://mmsc.example/shared-pi-fire"
        val fileA = target("pi-a")
        val fileB = target("pi-b")
        val a = completion(111_001L, location, fileA)
        val b = completion(222_002L, location, fileB)
        assertNotEquals(a, b)

        a.send()
        Thread.sleep(2_000)

        assertTrue("A's handle must fail A", MmsDownloadClaims.isFailed(111_001L))
        assertFalse("A's handle must not fail B", MmsDownloadClaims.isFailed(222_002L))
        assertFalse("receiver finally deletes A's target", fileA.exists())
        assertTrue("B's target must still be there", fileB.exists())
    }

    @Test
    fun zeroMmsIdTwice_doesNotCollide() {
        val location = "http://mmsc.example/zero-stub"
        val a = completion(mmsId = 0L, location = location)
        val b = completion(mmsId = 0L, location = location)
        assertNotEquals(
            "two mmsId==0 retrieves must not share a PendingIntent",
            a,
            b,
        )
    }

    private fun completion(
        mmsId: Long,
        location: String,
        target: File = target("pi-$mmsId-${handles.size}"),
    ): PendingIntent {
        val complete = Intent(context, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_DOWNLOADED
            putExtra(MmsDownloadReceiver.EXTRA_MMS_ID, mmsId)
            putExtra(MmsDownloadReceiver.EXTRA_CONTENT_URI, Uri.fromFile(target).toString())
            putExtra(MmsDownloadReceiver.EXTRA_FILE_PATH, target.absolutePath)
            putExtra(MmsDownloadReceiver.EXTRA_CONTENT_LOCATION, location)
        }
        return MmsDownloadCompletions.getBroadcast(context, complete, mmsId).also {
            handles += it
        }
    }

    private fun target(name: String): File =
        File(context.cacheDir, name).also {
            it.writeText(name)
            files += it
        }
}
