package com.pinotrouge.messaging.ui.media

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.ensureActive

/**
 * Loads [uri] at [targetWidthPx]×[targetHeightPx]. A new target cancels the
 * in-flight decode (LaunchedEffect key change) so a fast scroll does not
 * decode into a recycled slot.
 *
 * Decode runs on Dispatchers.IO inside the loader. Do not write [bitmap]
 * after cancellation: Compose can already be disposing the activity (the
 * Android 16 `SlotWriter.moveSlotGapTo` crash in
 * `HeldMediaUiInstrumentedTest.filteredRow_rendersHeldPhotoTile`).
 */
@Composable
fun rememberMmsPartImage(
    uri: Uri,
    targetWidthPx: Int,
    targetHeightPx: Int,
    scale: DecodeScale = DecodeScale.Cover,
    loader: MmsImageSource = rememberMmsPartImageLoader(),
): ImageBitmap? {
    var bitmap by remember(uri, targetWidthPx, targetHeightPx, scale) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(uri, targetWidthPx, targetHeightPx, scale, loader) {
        val loaded = loader.load(uri, targetWidthPx, targetHeightPx, scale)
        ensureActive()
        bitmap = loaded
    }
    return bitmap
}

@Composable
fun rememberMmsPartImageLoader(): MmsPartImageLoader {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        MmsPartImageLoader.get(context)
    }
}
