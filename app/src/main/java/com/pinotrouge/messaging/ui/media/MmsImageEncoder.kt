package com.pinotrouge.messaging.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface MmsEncodeResult {
    class Fit(
        val bytes: ByteArray,
        val contentType: String,
        val location: String,
    ) : MmsEncodeResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fit) return false
            return contentType == other.contentType &&
                location == other.location &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var hash = contentType.hashCode()
            hash = 31 * hash + location.hashCode()
            hash = 31 * hash + bytes.contentHashCode()
            return hash
        }
    }

    data object TooLarge : MmsEncodeResult
}

/**
 * Encode one outbound photograph to fit [MmsImageLadder.imageBudgetBytes].
 *
 * JPEG candidates are always re-encoded (EXIF stripped). GIF under budget is
 * the exception: animation cannot be re-encoded here, so the original bytes
 * go through unchanged when they fit.
 */
object MmsImageEncoder {

    fun encode(source: ByteArray, budgetBytes: Int): MmsEncodeResult {
        if (source.isEmpty() || budgetBytes <= 0) return MmsEncodeResult.TooLarge

        if (MmsImageLadder.hasGifSignature(source)) {
            return if (source.size <= budgetBytes) {
                MmsEncodeResult.Fit(
                    source,
                    "image/gif",
                    MmsImageLadder.locationFor("image/gif"),
                )
            } else {
                MmsEncodeResult.TooLarge
            }
        }

        val keepAlpha = MmsImageLadder.pngHasAlpha(source) ||
            MmsImageLadder.webpHasAlpha(source)
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: return MmsEncodeResult.TooLarge
        val encoded = if (keepAlpha) {
            encodePngLadder(bitmap, budgetBytes)
        } else {
            encodeJpegLadder(bitmap, budgetBytes)
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        return encoded
    }

    private fun encodeJpegLadder(source: Bitmap, budgetBytes: Int): MmsEncodeResult {
        var current: Bitmap? = null
        var encoded: MmsEncodeResult = MmsEncodeResult.TooLarge
        for (rung in MmsImageLadder.RUNGS) {
            val scaled = scaleToLongEdge(source, rung.longEdgePx)
            if (current != null && current !== source) current.recycle()
            current = scaled
            val bytes = compress(scaled, Bitmap.CompressFormat.JPEG, rung.jpegQuality)
            if (bytes.size <= budgetBytes) {
                encoded = MmsEncodeResult.Fit(
                    bytes,
                    "image/jpeg",
                    MmsImageLadder.locationFor("image/jpeg"),
                )
                break
            }
        }
        if (current != null && current !== source && !current.isRecycled) {
            current.recycle()
        }
        return encoded
    }

    private fun encodePngLadder(source: Bitmap, budgetBytes: Int): MmsEncodeResult {
        var current: Bitmap? = null
        var encoded: MmsEncodeResult = MmsEncodeResult.TooLarge
        for (rung in MmsImageLadder.RUNGS) {
            val scaled = scaleToLongEdge(source, rung.longEdgePx)
            if (current != null && current !== source) current.recycle()
            current = scaled
            val bytes = compress(scaled, Bitmap.CompressFormat.PNG, quality = 100)
            if (bytes.size <= budgetBytes) {
                encoded = MmsEncodeResult.Fit(
                    bytes,
                    "image/png",
                    MmsImageLadder.locationFor("image/png"),
                )
                break
            }
        }
        if (current != null && current !== source && !current.isRecycled) {
            current.recycle()
        }
        return encoded
    }

    private fun scaleToLongEdge(source: Bitmap, longEdgePx: Int): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= longEdgePx) return source
        val factor = longEdgePx / longEdge.toFloat()
        val w = (source.width * factor).roundToInt().coerceAtLeast(1)
        val h = (source.height * factor).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }
}
