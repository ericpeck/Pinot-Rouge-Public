package com.pinotrouge.messaging.ui.media

import com.pinotrouge.messaging.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsTileClassifyTest {

    @Test
    fun skipsEmptyImage() {
        assertNull(classifyMmsPart("image/jpeg", 0L))
    }

    @Test
    fun photoWhenImageHasBytes() {
        assertEquals(MmsTileKind.Photo to null, classifyMmsPart("image/jpeg; name=x.jpg", 120L))
    }

    @Test
    fun skipsSmilAndPlainText() {
        assertNull(classifyMmsPart("application/smil", 40L))
        assertNull(classifyMmsPart("text/plain", 12L))
    }

    @Test
    fun videoAudioContact() {
        assertEquals(
            MmsTileKind.Video to R.string.mms_part_video,
            classifyMmsPart("video/3gpp", 80L),
        )
        assertEquals(
            MmsTileKind.Audio to R.string.mms_part_audio,
            classifyMmsPart("audio/amr", 80L),
        )
        assertEquals(
            MmsTileKind.Contact to R.string.mms_part_contact,
            classifyMmsPart("text/x-vCard", 80L),
        )
    }

    @Test
    fun neverUsesFilename_unknownBinaryIsUnsupported() {
        assertEquals(
            MmsTileKind.Unsupported to R.string.mms_part_unsupported,
            classifyMmsPart("application/octet-stream", 80L),
        )
    }

    @Test
    fun stubKind_timeoutToFailed_expiredWins() {
        assertEquals(MmsTileKind.NotDownloaded, stubTileKind(expired = false, MmsDownloadUi.Idle))
        assertEquals(MmsTileKind.Downloading, stubTileKind(expired = false, MmsDownloadUi.Downloading))
        assertEquals(MmsTileKind.Failed, stubTileKind(expired = false, MmsDownloadUi.Failed))
        assertEquals(MmsTileKind.Expired, stubTileKind(expired = true, MmsDownloadUi.Downloading))
    }
}
