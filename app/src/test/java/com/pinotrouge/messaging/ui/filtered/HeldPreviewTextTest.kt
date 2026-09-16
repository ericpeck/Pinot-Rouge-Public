package com.pinotrouge.messaging.ui.filtered

import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldPreviewTextTest {

    @Test
    fun captionWinsOverPhotoFallback() {
        val tiles = listOf(MmsTile(1L, 0, MmsTileKind.Photo))
        assertEquals("a caption", heldPreviewText("a caption", tiles))
    }

    @Test
    fun photoOnlyUsesInboxSnippet() {
        val tiles = listOf(MmsTile(1L, 0, MmsTileKind.Photo))
        assertEquals(SmsRepository.PHOTO_SNIPPET_FALLBACK, heldPreviewText("", tiles))
        assertEquals(SmsRepository.PHOTO_SNIPPET_FALLBACK, heldPreviewText("  \n", tiles))
    }

    @Test
    fun textOnlyIsTrimmed() {
        assertEquals("hello", heldPreviewText("  hello\n", emptyList()))
        assertEquals("", heldPreviewText("  ", emptyList()))
    }

    @Test
    fun heldTileIdIsStableAndNonNegative() {
        val a = heldTileMessageId("held-photo-1")
        val b = heldTileMessageId("held-photo-1")
        assertEquals(a, b)
        assertTrue(a >= 0L)
        assertTrue(heldTileMessageId("other") != a)
    }
}
