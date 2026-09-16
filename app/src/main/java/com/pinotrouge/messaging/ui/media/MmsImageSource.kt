package com.pinotrouge.messaging.ui.media

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap

/**
 * One-method image source so a later Coil swap is a single file.
 */
fun interface MmsImageSource {
    suspend fun load(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int,
        scale: DecodeScale,
    ): ImageBitmap?
}
