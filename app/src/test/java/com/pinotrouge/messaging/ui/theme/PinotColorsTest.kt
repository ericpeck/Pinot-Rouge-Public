package com.pinotrouge.messaging.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class PinotColorsTest {

    @Test
    fun `unknown storage key falls back to pinot`() {
        assertEquals(PinotThemeKey.Pinot, PinotThemeKey.fromStorageKey(null))
        assertEquals(PinotThemeKey.Pinot, PinotThemeKey.fromStorageKey(""))
        assertEquals(PinotThemeKey.Pinot, PinotThemeKey.fromStorageKey("not-a-theme"))
        assertEquals(PinotThemeKey.Atlantic, PinotThemeKey.fromStorageKey("atlantic"))
        assertEquals(PinotThemeKey.Bordeaux, PinotThemeKey.fromStorageKey("BORDEAUX"))
        assertEquals(PinotThemeKey.Rose, PinotThemeKey.fromStorageKey("rose"))
        assertEquals(PinotThemeKey.Grigio, PinotThemeKey.fromStorageKey("grigio"))
        assertEquals(PinotThemeKey.Blanc, PinotThemeKey.fromStorageKey("blanc"))
    }

    @Test
    fun `offered accents are the nine prototype accentOptions without bordeaux`() {
        // Version 5 order: Cabernet, Rosé, Concord, Malbec, Thompson, Chardonnay, Amber, Grigio, Blanc
        assertEquals(
            listOf(
                PinotThemeKey.Pinot,
                PinotThemeKey.Rose,
                PinotThemeKey.Violet,
                PinotThemeKey.Atlantic,
                PinotThemeKey.Laurel,
                PinotThemeKey.Amber,
                PinotThemeKey.Tangerine,
                PinotThemeKey.Grigio,
                PinotThemeKey.Blanc,
            ),
            PinotThemeKey.offeredAccents,
        )
        assertTrue(PinotThemeKey.Bordeaux !in PinotThemeKey.offeredAccents)
        // Persisted bordeaux still builds a palette (theme layer, not the picker).
        pinotColors(PinotThemeKey.Bordeaux, dark = true)
    }

    @Test
    fun `default pinot dark accent matches prior brand ramp step 3`() {
        val c = pinotColors(PinotThemeKey.Pinot, dark = true)
        assertEquals(Color(0xFFD4798C), c.accent)
        assertEquals(Color(0xFF17141C), c.bg)
        assertEquals(Color(0xFF221E29), c.surface)
    }

    @Test
    fun `default pinot light accent matches prior brand ramp step 5`() {
        val c = pinotColors(PinotThemeKey.Pinot, dark = false)
        assertEquals(Color(0xFF9A3B53), c.accent)
        assertEquals(Color(0xFFFAF7F8), c.bg)
    }

    @Test
    fun `bordeaux reuses pinot neutrals but not the same accent`() {
        val pinot = pinotColors(PinotThemeKey.Pinot, dark = true)
        val bordeaux = pinotColors(PinotThemeKey.Bordeaux, dark = true)
        assertEquals(pinot.bg, bordeaux.bg)
        assertEquals(pinot.surface, bordeaux.surface)
        assertEquals(pinot.dimmer, bordeaux.dimmer)
        assertNotEquals(pinot.accent, bordeaux.accent)
    }

    @Test
    fun `grigio and blanc use explicit accent not ramp step`() {
        val grigioLight = pinotColors(PinotThemeKey.Grigio, dark = false)
        val grigioDark = pinotColors(PinotThemeKey.Grigio, dark = true)
        val blancLight = pinotColors(PinotThemeKey.Blanc, dark = false)
        val blancDark = pinotColors(PinotThemeKey.Blanc, dark = true)
        assertEquals(Color(0xFF2B2F36), grigioLight.accent)
        assertEquals(Color(0xFFE3E7EC), grigioDark.accent)
        // Blanc light accent is deliberately blue — do not "correct" it.
        assertEquals(Color(0xFF0B57D0), blancLight.accent)
        assertEquals(Color(0xFFA8C7FA), blancDark.accent)
        // Explicit outBub diverges from ramp step 1 / 7.
        assertEquals(Color(0xFF1B1E23), grigioLight.outgoingBubble)
        assertEquals(Color(0xFFEEF0F4), grigioDark.outgoingBubble)
    }

    @Test
    fun `ink falls back to accent except rose and amber light`() {
        val pinot = pinotColors(PinotThemeKey.Pinot, dark = false)
        assertEquals(pinot.accent, pinot.ink)

        val roseLight = pinotColors(PinotThemeKey.Rose, dark = false)
        assertEquals(Color(0xFF9C4A45), roseLight.ink)
        assertNotEquals(roseLight.accent, roseLight.ink)

        val amberLight = pinotColors(PinotThemeKey.Amber, dark = false)
        assertEquals(Color(0xFF6D5C16), amberLight.ink)
        assertNotEquals(amberLight.accent, amberLight.ink)

        val roseDark = pinotColors(PinotThemeKey.Rose, dark = true)
        assertEquals(roseDark.accent, roseDark.ink)
    }

    @Test
    fun `swatch split only for grigio and blanc`() {
        assertNull(PinotThemeKey.Pinot.swatchSplit(dark = false))
        assertNull(PinotThemeKey.Rose.swatchSplit(dark = true))
        assertEquals(
            Color(0xFFFDFDFE) to Color(0xFF1B1E23),
            PinotThemeKey.Grigio.swatchSplit(dark = false),
        )
        assertEquals(
            Color(0xFFEDEDED) to Color(0xFF0B57D0),
            PinotThemeKey.Blanc.swatchSplit(dark = false),
        )
        assertEquals(Color(0xFFC9CCD3), PinotThemeKey.Grigio.swatchDotRing(dark = false))
        assertNull(PinotThemeKey.Pinot.swatchDotRing(dark = false))
    }

    @Test
    fun `all ten themes resolve in both modes`() {
        assertEquals(10, PinotThemeKey.entries.size)
        for (key in PinotThemeKey.entries) {
            val dark = pinotColors(key, dark = true)
            val light = pinotColors(key, dark = false)
            assertTrue(dark.isDark)
            assertTrue(!light.isDark)
            assertNotEquals(dark.text, dark.surface)
            assertNotEquals(light.text, light.surface)
        }
    }

    /**
     * Normal-text AA (4.5:1) for text / dim / dimmer / ink on surface and bg.
     * Floor is 4.5, not 3.0 — that is how #90 shipped atlantic at 4.48:1 green.
     */
    @Test
    fun `text dim dimmer and ink meet AA in every theme`() {
        val ratios = mutableListOf<String>()
        val floor = 4.5
        val failures = mutableListOf<String>()
        for (key in PinotThemeKey.entries) {
            for (dark in listOf(true, false)) {
                val c = pinotColors(key, dark)
                val mode = if (dark) "dark" else "light"
                val pairs = listOf(
                    "text/surface" to contrastRatio(c.text, c.surface),
                    "text/bg" to contrastRatio(c.text, c.bg),
                    "dimmer/surface" to contrastRatio(c.dimmer, c.surface),
                    "dimmer/bg" to contrastRatio(c.dimmer, c.bg),
                    "dim/surface" to contrastRatio(c.dim, c.surface),
                    "dim/bg" to contrastRatio(c.dim, c.bg),
                    "ink/surface" to contrastRatio(c.ink, c.surface),
                )
                for ((label, ratio) in pairs) {
                    val line = "${key.storageKey}/$mode $label=${"%.2f".format(ratio)}"
                    ratios += line
                    if (ratio < floor) {
                        failures += line
                    }
                }
            }
        }
        println("AA contrast: ${ratios.joinToString("; ")}")
        assertTrue(
            "AA failures (do not relax the floor):\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    /**
     * onAccent on accent — 4.5:1 required for badge/check text.
     *
     * Version 5 prototype misses AA for **rose light** and **amber light**
     * (both ~3.62:1). Even pure white on those accents is ~3.9:1, so the
     * accent ramps themselves are too pale for normal-text AA. Named here
     * rather than silently patching prototype values. Any *other* miss fails.
     */
    @Test
    fun `onAccent AA with named prototype misses`() {
        val floor = 4.5
        val knownMisses = setOf("rose/light", "amber/light")
        val unexpected = mutableListOf<String>()
        val observedMisses = mutableSetOf<String>()
        for (key in PinotThemeKey.entries) {
            for (dark in listOf(true, false)) {
                val c = pinotColors(key, dark)
                val mode = if (dark) "dark" else "light"
                val id = "${key.storageKey}/$mode"
                val ratio = contrastRatio(c.onAccent, c.accent)
                val line = "$id onAccent/accent=${"%.2f".format(ratio)}"
                println(line)
                if (ratio < floor) {
                    if (id in knownMisses) {
                        observedMisses += id
                    } else {
                        unexpected += line
                    }
                }
            }
        }
        assertTrue(
            "Unexpected onAccent AA failures:\n${unexpected.joinToString("\n")}",
            unexpected.isEmpty(),
        )
        // Pin the prototype misses so a silent "fix" is noticed.
        assertEquals(knownMisses, observedMisses)
    }

    /**
     * Prototype swatch rings (#c9ccd3 on near-white surface) are sub-3:1 —
     * decorative hairlines, same class as dividers. Recorded, not enforced.
     */
    @Test
    fun `grigio and blanc light swatch rings are measured`() {
        for (key in listOf(PinotThemeKey.Grigio, PinotThemeKey.Blanc)) {
            val ring = key.swatchDotRing(dark = false)!!
            val surface = pinotColors(key, dark = false).surface
            val ratio = contrastRatio(ring, surface)
            println("${key.storageKey} light dotRing/surface=${"%.2f".format(ratio)}")
            assertTrue(ratio > 1.0)
        }
    }

    /** V5 replaced the atlantic ramp; re-measure — do not re-apply #94's hand value. */
    @Test
    fun `atlantic light dimmer on surface is measured against V5 ramp`() {
        val c = pinotColors(PinotThemeKey.Atlantic, dark = false)
        val ratio = contrastRatio(c.dimmer, c.surface)
        // V5 measures ~4.72 — above 4.5 without the #94 hand darken.
        println("atlantic light dimmer/surface V5 = ${"%.2f".format(ratio)}")
        assertTrue("atlantic light dimmer/surface $ratio < 4.5", ratio >= 4.5)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
