package com.pinotrouge.messaging.ui.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One scale factor for [android.graphics.ImageDecoder.setTargetSize].
 * Computing width and height independently distorts; a 4:3 fixture hides it.
 */
enum class DecodeScale {
    /** Fill [boxWidth]×[boxHeight]; Compose then centre-crops. */
    Cover,
    /** Fit inside the box; Compose letterboxes. */
    Fit,
}

object DecodeTarget {
    const val DEFAULT_MAX_PIXELS = 8_388_608

    fun size(
        sourceWidth: Int,
        sourceHeight: Int,
        boxWidth: Int,
        boxHeight: Int,
        scale: DecodeScale,
        maxPixels: Int = DEFAULT_MAX_PIXELS,
    ): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1 to 1
        val boxW = boxWidth.coerceAtLeast(1)
        val boxH = boxHeight.coerceAtLeast(1)
        val raw = when (scale) {
            DecodeScale.Cover -> max(boxW / sourceWidth.toFloat(), boxH / sourceHeight.toFloat())
            DecodeScale.Fit -> min(boxW / sourceWidth.toFloat(), boxH / sourceHeight.toFloat())
        }
        // Never decode larger than the source — Compose will upscale a small image.
        val factor = min(raw, 1f)
        var width = (sourceWidth * factor).roundToInt().coerceAtLeast(1)
        var height = (sourceHeight * factor).roundToInt().coerceAtLeast(1)
        val pixels = width.toLong() * height.toLong()
        if (pixels > maxPixels) {
            val shrink = sqrt(maxPixels.toDouble() / pixels.toDouble())
            width = (width * shrink).toInt().coerceAtLeast(1)
            height = (height * shrink).toInt().coerceAtLeast(1)
            while (width.toLong() * height > maxPixels) {
                if (width >= height && width > 1) width-- else if (height > 1) height-- else break
            }
        }
        return width to height
    }
}
