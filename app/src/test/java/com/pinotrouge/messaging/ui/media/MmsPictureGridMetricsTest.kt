package com.pinotrouge.messaging.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MmsPictureGridMetricsTest {

    @Test
    fun overflowCount_onlyAfterFourPhotos() {
        assertEquals(0, MmsPictureGridMetrics.overflowCount(1))
        assertEquals(0, MmsPictureGridMetrics.overflowCount(4))
        assertEquals(2, MmsPictureGridMetrics.overflowCount(5))
        assertEquals(3, MmsPictureGridMetrics.overflowCount(6))
    }

    @Test
    fun heightFractions_matchV6Ratios() {
        assertEquals(0.725f, MmsPictureGridMetrics.heightFraction(1))
        assertEquals(0.595f, MmsPictureGridMetrics.heightFraction(2))
        assertEquals(0.729f, MmsPictureGridMetrics.heightFraction(3))
        assertEquals(0.784f, MmsPictureGridMetrics.heightFraction(4))
        assertEquals(0.784f, MmsPictureGridMetrics.heightFraction(9))
    }
}
