package com.pinotrouge.messaging.ui.media

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MmsPartImageLoaderInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach { it.delete() }
        MmsPartImageLoader.resetForTests()
    }

    @Test
    fun viewerLoad_doesNotEvictTileKey() = runBlocking {
        val uri = jpegUri(width = 400, height = 800)
        val loader = MmsPartImageLoader(context, maxCacheBytes = 8 * 1024 * 1024, hardware = false)
        val tile = loader.load(uri, 178, 132, DecodeScale.Cover)
        assertNotNull(tile)
        val viewer = loader.load(uri, 1080, 2400, DecodeScale.Fit)
        assertNotNull(viewer)
        assertTrue(loader.contains(uri, 178, 132, DecodeScale.Cover))
        assertTrue(loader.contains(uri, 1080, 2400, DecodeScale.Fit))
    }

    @Test
    fun trimMemory_dropsCache() = runBlocking {
        val uri = jpegUri(200, 200)
        val loader = MmsPartImageLoader(context, maxCacheBytes = 8 * 1024 * 1024, hardware = false)
        loader.load(uri, 100, 100, DecodeScale.Cover)
        assertTrue(loader.cachedBytes() > 0)
        loader.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertTrue(loader.cachedBytes() == 0)
    }

    @Test
    fun corruptSource_returnsNull() = runBlocking {
        val file = File(cacheDir(), "corrupt.jpg").apply {
            writeText("not a jpeg")
            files += this
        }
        val uri = FileProvider.getUriForFile(
            context,
            PlatformMmsTransport.authority(context.packageName),
            file,
        )
        val loader = MmsPartImageLoader(context, hardware = false)
        val result = loader.load(uri, 178, 132, DecodeScale.Cover)
        assertTrue(result == null)
    }

    @Test
    fun rapidRecomposition_cancelsObsoleteLoad() {
        val first = Uri.parse("content://mms/part/first")
        val second = Uri.parse("content://mms/part/second")
        val source = RecordingSource()
        var uri by mutableStateOf(first)
        composeRule.setContent {
            rememberMmsPartImage(uri = uri, targetWidthPx = 10, targetHeightPx = 10, loader = source)
            LaunchedEffect(Unit) {
                delay(30)
                uri = second
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()
        assertTrue("second uri must have been requested", source.started.contains(second))
        assertTrue("first load must have been cancelled", source.cancelled.contains(first))
        assertTrue("first load must not have completed", source.completed.none { it == first })
    }

    private fun jpegUri(width: Int, height: Int): Uri {
        val file = File(cacheDir(), "img-${width}x${height}.jpg")
        files += file
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return FileProvider.getUriForFile(
            context,
            PlatformMmsTransport.authority(context.packageName),
            file,
        )
    }

    private fun cacheDir(): File =
        File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }

    private class RecordingSource : MmsImageSource {
        val started = mutableListOf<Uri>()
        val completed = mutableListOf<Uri>()
        val cancelled = mutableListOf<Uri>()

        override suspend fun load(
            uri: Uri,
            targetWidthPx: Int,
            targetHeightPx: Int,
            scale: DecodeScale,
        ): ImageBitmap? {
            started += uri
            try {
                delay(80)
                completed += uri
            } catch (cancelledLoad: kotlinx.coroutines.CancellationException) {
                cancelled += uri
                throw cancelledLoad
            }
            return null
        }
    }
}
