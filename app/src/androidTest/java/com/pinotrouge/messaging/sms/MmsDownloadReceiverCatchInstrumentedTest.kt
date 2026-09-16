package com.pinotrouge.messaging.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Recovery after a download complete must not rethrow. A catch that
 * re-resolves the Hilt entry point killed the process when that resolve
 * was what failed (MmsDownloadReceiver.kt:97 on main).
 *
 * 0 skipped.
 */
@RunWith(AndroidJUnit4::class)
class MmsDownloadReceiverCatchInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val handles = mutableListOf<PendingIntent>()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        handles.forEach { it.cancel() }
        files.forEach { it.delete() }
        MmsDownloadClaims.resetForTests()
        MmsDownloadCompletions.resetForTests()
    }

    @Test
    fun broadcastWithoutHiltTest_doesNotKillTheProcess() {
        val target = target("catch-no-hilt")
        val complete = completion(mmsId = 441_001L, target = target)
        complete.send()
        Thread.sleep(2_000)
        assertTrue("receiver must return without killing the process", true)
        assertFalse("finally still deletes the target", target.exists())
    }

    @Test
    fun notifyRespSent_deletesFileAndRevokesGrant() {
        val pdu = PlatformMmsTransport.cachePdu(
            context,
            PlatformMmsTransport.SEND_CACHE_DIR,
            "notifyresp-cleanup.pdu",
            byteArrayOf(0x8C.toByte(), 0x83.toByte()),
        )
        files += pdu.file
        val installed = PlatformMmsTransport.PDU_READ_GRANTEES.filter { packageInstalled(it) }
        assumeTrue("emulator images ship at least one MMS-service package", installed.isNotEmpty())
        PlatformMmsTransport.grantPduAccess(
            context,
            pdu.uri,
            PlatformMmsTransport.PDU_READ_FLAGS,
        )
        for (pkg in installed) {
            assertEquals(
                "grant must be in place before NotifyResp cleanup: $pkg",
                PackageManager.PERMISSION_GRANTED,
                uriPermission(pdu.uri, pkg),
            )
        }
        val complete = Intent(context, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_NOTIFYRESP_SENT
            putExtra(MmsDownloadReceiver.EXTRA_CONTENT_URI, pdu.uri.toString())
            putExtra(MmsDownloadReceiver.EXTRA_FILE_PATH, pdu.file.absolutePath)
        }
        PendingIntent.getBroadcast(
            context,
            441_010,
            complete,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).also { handles += it }.send()
        Thread.sleep(500)
        assertFalse("NotifyResp callback must delete the ack file", pdu.file.exists())
        for (pkg in installed) {
            assertEquals(
                "NotifyResp callback must revoke the grant: $pkg",
                PackageManager.PERMISSION_DENIED,
                uriPermission(pdu.uri, pkg),
            )
        }
    }

    private fun packageInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun uriPermission(uri: Uri, pkg: String): Int {
        val uid = context.packageManager.getPackageUid(pkg, 0)
        return context.checkUriPermission(
            uri,
            /* pid = */ -1,
            uid,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private fun completion(mmsId: Long, target: File): PendingIntent {
        val complete = Intent(context, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_DOWNLOADED
            putExtra(MmsDownloadReceiver.EXTRA_MMS_ID, mmsId)
            putExtra(MmsDownloadReceiver.EXTRA_CONTENT_URI, Uri.fromFile(target).toString())
            putExtra(MmsDownloadReceiver.EXTRA_FILE_PATH, target.absolutePath)
            putExtra(MmsDownloadReceiver.EXTRA_CONTENT_LOCATION, "http://mmsc.example/catch")
        }
        return MmsDownloadCompletions.getBroadcast(context, complete, mmsId).also {
            handles += it
        }
    }

    private fun target(name: String): File =
        File(context.cacheDir, name).also {
            it.writeBytes(ByteArray(0))
            files += it
        }
}

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MmsDownloadReceiverFailureInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

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
    fun emptyDownload_marksFailed_andFinishesGoAsync() {
        val mmsId = 441_002L
        val target = File(context.cacheDir, "catch-empty").also {
            it.writeBytes(ByteArray(0))
            files += it
        }
        val complete = Intent(context, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_DOWNLOADED
            putExtra(MmsDownloadReceiver.EXTRA_MMS_ID, mmsId)
            putExtra(MmsDownloadReceiver.EXTRA_CONTENT_URI, Uri.fromFile(target).toString())
            putExtra(MmsDownloadReceiver.EXTRA_FILE_PATH, target.absolutePath)
            putExtra(MmsDownloadReceiver.EXTRA_CONTENT_LOCATION, "http://mmsc.example/empty")
        }
        val handle = MmsDownloadCompletions.getBroadcast(context, complete, mmsId).also {
            handles += it
        }
        handle.send()
        Thread.sleep(2_000)
        assertTrue("empty retrieve must still report failed", MmsDownloadClaims.isFailed(mmsId))
        assertFalse("finally deletes the target exactly on this path too", target.exists())
    }

    @Test
    fun missingContentUri_marksFailed() {
        val mmsId = 441_003L
        val complete = Intent(context, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_DOWNLOADED
            putExtra(MmsDownloadReceiver.EXTRA_MMS_ID, mmsId)
        }
        val handle = MmsDownloadCompletions.getBroadcast(context, complete, mmsId).also {
            handles += it
        }
        handle.send()
        Thread.sleep(2_000)
        assertTrue("no content URI must still report failed", MmsDownloadClaims.isFailed(mmsId))
    }
}
