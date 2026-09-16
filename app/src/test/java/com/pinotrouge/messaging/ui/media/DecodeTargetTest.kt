package com.pinotrouge.messaging.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeTargetTest {

    @Test
    fun cover_portrait_usesOneScale_andKeepsAspect() {
        val (w, h) = DecodeTarget.size(
            sourceWidth = 1000,
            sourceHeight = 2000,
            boxWidth = 534,
            boxHeight = 396,
            scale = DecodeScale.Cover,
        )
        assertEquals(1000 / 2000.0, w / h.toDouble(), 0.02)
        assertTrue("must cover the box on the short axis", w >= 534 || h >= 396)
    }

    @Test
    fun cover_panorama_usesOneScale_andKeepsAspect() {
        val (w, h) = DecodeTarget.size(
            sourceWidth = 4000,
            sourceHeight = 1000,
            boxWidth = 534,
            boxHeight = 396,
            scale = DecodeScale.Cover,
        )
        assertEquals(4000 / 1000.0, w / h.toDouble(), 0.02)
        assertTrue(w >= 534 || h >= 396)
    }

    @Test
    fun independentDimensionsWouldDistort_portrait() {
        // The bug: setTargetSize(boxW, boxH) on a portrait source.
        val distorted = 534 / 396.0
        val (w, h) = DecodeTarget.size(
            sourceWidth = 1000,
            sourceHeight = 2000,
            boxWidth = 534,
            boxHeight = 396,
            scale = DecodeScale.Cover,
        )
        assertTrue(kotlin.math.abs(w / h.toDouble() - distorted) > 0.2)
    }

    @Test
    fun capsDecodedPixels() {
        val (w, h) = DecodeTarget.size(
            sourceWidth = 12_000,
            sourceHeight = 9_000,
            boxWidth = 12_000,
            boxHeight = 9_000,
            scale = DecodeScale.Fit,
            maxPixels = 1_000_000,
        )
        assertTrue(w.toLong() * h <= 1_000_000)
        assertEquals(12_000 / 9_000.0, w / h.toDouble(), 0.05)
    }

    @Test
    fun neverUpscalesBeyondSource() {
        val (w, h) = DecodeTarget.size(
            sourceWidth = 50,
            sourceHeight = 40,
            boxWidth = 534,
            boxHeight = 396,
            scale = DecodeScale.Cover,
        )
        assertEquals(50, w)
        assertEquals(40, h)
    }
}
