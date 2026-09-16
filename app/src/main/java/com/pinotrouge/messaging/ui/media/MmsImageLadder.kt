package com.pinotrouge.messaging.ui.media

/**
 * Outbound MMS size policy. Encoding happens elsewhere — pixel dimensions do
 * not predict JPEG size, so callers must encode each candidate and measure
 * the actual byte length.
 *
 * Re-encoding a photograph to JPEG **strips EXIF, GPS included**. That is a
 * privacy property of this ladder, not an optimisation. Do not "fast path"
 * original bytes through for a JPEG that already fits: those bytes still
 * carry the sender's metadata.
 *
 * PNG/WebP with an alpha channel stay lossless (PNG). They are never
 * flattened onto a JPEG. An animated GIF is validated by its contents
 * (GIF87a / GIF89a), sent unchanged when it fits the budget, and refused
 * when it does not — we do not transcode animation.
 */
object MmsImageLadder {

    data class Rung(val longEdgePx: Int, val jpegQuality: Int)

    val RUNGS: List<Rung> = listOf(
        Rung(1600, 85),
        Rung(1280, 80),
        Rung(1024, 75),
        Rung(800, 70),
        Rung(640, 60),
    )

    /** ~85% of the carrier max, leaving room for SMIL, text, and MMS headers. */
    fun imageBudgetBytes(carrierMaxMessageBytes: Int): Int {
        val cap = carrierMaxMessageBytes.coerceAtLeast(1)
        return ((cap * 85L) / 100L).toInt().coerceAtLeast(1)
    }

    fun hasGifSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        return header == "GIF87a" || header == "GIF89a"
    }

    fun hasPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
    }

    fun hasJpegSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
    }

    fun hasWebpSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val webp = String(bytes, 8, 4, Charsets.US_ASCII)
        return riff == "RIFF" && webp == "WEBP"
    }

    /**
     * PNG colour type 4 (grey+alpha) or 6 (truecolour+alpha), or a tRNS chunk.
     * Missing/truncated IHDR is treated as no alpha so we do not invent
     * transparency.
     */
    fun pngHasAlpha(bytes: ByteArray): Boolean {
        if (!hasPngSignature(bytes) || bytes.size < 26) return false
        val colorType = bytes[25].toInt() and 0xFF
        if (colorType == 4 || colorType == 6) return true
        return pngHasTrns(bytes)
    }

    /**
     * WebP VP8X: the alpha flag is bit 1 of the 32-bit flags that follow the
     * four-byte 'VP8X' chunk type.
     */
    fun webpHasAlpha(bytes: ByteArray): Boolean {
        if (!hasWebpSignature(bytes) || bytes.size < 21) return false
        // RIFF(4)+size(4)+WEBP(4) = 12, then chunk fourcc. VP8X is the extended header.
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "VP8X") return false
        val flags = bytes[20].toInt() and 0xFF
        return flags and 0x02 != 0
    }

    fun locationFor(mime: String): String {
        val type = mime.substringBefore(';').trim().lowercase()
        return when (type) {
            "image/png" -> "image_0.png"
            "image/gif" -> "image_0.gif"
            "image/webp" -> "image_0.webp"
            else -> "image_0.jpg"
        }
    }

    private fun pngHasTrns(bytes: ByteArray): Boolean {
        var i = 8
        while (i + 8 <= bytes.size) {
            val len = ((bytes[i].toInt() and 0xFF) shl 24) or
                ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                (bytes[i + 3].toInt() and 0xFF)
            if (len < 0) return false
            val type = if (i + 8 <= bytes.size) {
                String(bytes, i + 4, 4, Charsets.US_ASCII)
            } else {
                return false
            }
            if (type == "tRNS") return true
            if (type == "IEND") return false
            val next = i + 12 + len // len + type + data + crc
            if (next <= i) return false
            i = next
        }
        return false
    }
}
