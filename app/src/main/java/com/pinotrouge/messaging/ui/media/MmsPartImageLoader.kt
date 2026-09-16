package com.pinotrouge.messaging.ui.media

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Byte-sized [LruCache] of decoded MMS parts. Keyed by (uri, target pixels)
 * so the tile and the full-screen viewer do not evict each other.
 */
class MmsPartImageLoader(
    context: Context,
    private val maxCacheBytes: Long = defaultMaxCacheBytes(),
    private val hardware: Boolean = true,
) : MmsImageSource {

    private val appContext = context.applicationContext

    private val cache = object : LruCache<CacheKey, Bitmap>(
        maxCacheBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int {
            val bytes = runCatching { value.allocationByteCount }.getOrDefault(value.byteCount)
            return bytes.coerceAtLeast(1)
        }
    }

    init {
        appContext.registerComponentCallbacks(TrimCallbacks())
    }

    override suspend fun load(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int,
        scale: DecodeScale,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = CacheKey(uri, targetWidthPx, targetHeightPx, scale)
        cache.get(key)?.let { return@withContext it.asImageBitmap() }
        coroutineContext.ensureActive()
        val bitmap = decode(uri, targetWidthPx, targetHeightPx, scale) ?: return@withContext null
        coroutineContext.ensureActive()
        cache.put(key, bitmap)
        bitmap.asImageBitmap()
    }

    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                cache.trimToSize((maxCacheBytes / 2).toInt().coerceAtLeast(1))
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                cache.trimToSize((maxCacheBytes / 2).toInt().coerceAtLeast(1))
        }
    }

    @VisibleForTesting
    fun contains(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int,
        scale: DecodeScale = DecodeScale.Cover,
    ): Boolean = cache.get(CacheKey(uri, targetWidthPx, targetHeightPx, scale)) != null

    @VisibleForTesting
    fun cachedBytes(): Int = cache.size()

    @VisibleForTesting
    fun evictAll() {
        cache.evictAll()
    }

    private fun decode(uri: Uri, boxW: Int, boxH: Int, scale: DecodeScale): Bitmap? {
        fun once(hardware: Boolean): Bitmap? {
            val source = runCatching {
                ImageDecoder.createSource(appContext.contentResolver, uri)
            }.getOrNull() ?: return null
            return runCatching {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    applyTarget(decoder, info.size, boxW, boxH, scale, hardware)
                }
            }.getOrNull()
        }
        return once(hardware) ?: if (hardware) once(false) else null
    }

    private fun applyTarget(
        decoder: ImageDecoder,
        source: Size,
        boxW: Int,
        boxH: Int,
        scale: DecodeScale,
        hardware: Boolean,
    ) {
        decoder.allocator = if (hardware) {
            ImageDecoder.ALLOCATOR_HARDWARE
        } else {
            ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val (w, h) = DecodeTarget.size(
            sourceWidth = source.width,
            sourceHeight = source.height,
            boxWidth = boxW.coerceAtLeast(1),
            boxHeight = boxH.coerceAtLeast(1),
            scale = scale,
        )
        decoder.setTargetSize(w, h)
    }

    private inner class TrimCallbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            this@MmsPartImageLoader.onTrimMemory(level)
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            cache.evictAll()
        }
    }

    private data class CacheKey(
        val uri: Uri,
        val widthPx: Int,
        val heightPx: Int,
        val scale: DecodeScale,
    )

    companion object {
        @Volatile
        private var singleton: MmsPartImageLoader? = null

        fun defaultMaxCacheBytes(): Long =
            (Runtime.getRuntime().maxMemory() / 8L).coerceAtLeast(1L)

        fun get(context: Context): MmsPartImageLoader {
            return singleton ?: synchronized(this) {
                singleton ?: MmsPartImageLoader(context.applicationContext).also { singleton = it }
            }
        }

        @VisibleForTesting
        fun resetForTests() {
            synchronized(this) {
                singleton?.evictAll()
                singleton = null
            }
        }
    }
}
