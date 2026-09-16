package com.pinotrouge.messaging.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsImageLadderTest {

    @Test
    fun imageBudget_is85Percent() {
        assertEquals(255_000, MmsImageLadder.imageBudgetBytes(300_000))
        assertEquals(1, MmsImageLadder.imageBudgetBytes(0))
        assertEquals(1, MmsImageLadder.imageBudgetBytes(1))
    }

    @Test
    fun rungs_areTheSpecifiedLadder() {
        assertEquals(
            listOf(1600 to 85, 1280 to 80, 1024 to 75, 800 to 70, 640 to 60),
            MmsImageLadder.RUNGS.map { it.longEdgePx to it.jpegQuality },
        )
    }

    @Test
    fun gif_isDetectedByContentsNotExtension() {
        assertTrue(MmsImageLadder.hasGifSignature("GIF89a....".toByteArray()))
        assertTrue(MmsImageLadder.hasGifSignature("GIF87a....".toByteArray()))
        assertFalse(MmsImageLadder.hasGifSignature("GIF9".toByteArray()))
        assertFalse(MmsImageLadder.hasGifSignature(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun jpeg_png_webp_signatures() {
        assertTrue(
            MmsImageLadder.hasJpegSignature(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        )
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertTrue(MmsImageLadder.hasPngSignature(png))
        val webp = ByteArray(12)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBP".toByteArray().copyInto(webp, 8)
        assertTrue(MmsImageLadder.hasWebpSignature(webp))
    }

    @Test
    fun pngHasAlpha_readsIhdrColorType() {
        // signature + len + IHDR + width + height + bitDepth + colorType 6
        val bytes = ByteArray(26)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(bytes)
        bytes[25] = 6
        assertTrue(MmsImageLadder.pngHasAlpha(bytes))
        bytes[25] = 2
        assertFalse(MmsImageLadder.pngHasAlpha(bytes))
    }

    @Test
    fun locationFor_matchesSmilSrc() {
        assertEquals("image_0.jpg", MmsImageLadder.locationFor("image/jpeg"))
        assertEquals("image_0.png", MmsImageLadder.locationFor("image/png"))
        assertEquals("image_0.gif", MmsImageLadder.locationFor("image/gif"))
    }
}
