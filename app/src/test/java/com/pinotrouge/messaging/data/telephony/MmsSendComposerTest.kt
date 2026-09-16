package com.pinotrouge.messaging.data.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsSendComposerTest {

    @Test
    fun composeSendReq_roundTripsUtf8Text_withoutPartContentTypeIdentifier() {
        val body = "hello café"
        val pdu = MmsSendComposer.composeSendReq(listOf("+15555550100"), body)

        assertEquals(
            "0x04 is MMS Content-Type's assigned number & 0x7F — it is not a part field",
            -1,
            firstPartContentTypeLead(pdu),
        )
        assertNotEquals(
            0x04,
            firstPartContentTypeLeadByte(pdu),
        )

        val parsed = requireNotNull(MmsPduDecoder.parse(pdu))
        assertEquals(MmsPduDecoder.TYPE_SEND_REQ, parsed.messageType)
        assertEquals(listOf("+15555550100"), parsed.participants)
        assertEquals(body, parsed.body)
        assertEquals(1, parsed.parts.size)
        val part = parsed.parts.single()
        assertTrue(part.isTextPlain)
        assertEquals("utf-8", part.charset)
        assertEquals(body, part.bytes.toString(Charsets.UTF_8))
        assertEquals("<text_0>", part.contentId)
    }

    @Test
    fun composeSendReq_imageRoundTrip_smilSrcMatchesLocation_no04() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03)
        val caption = "a caption"
        val pdu = MmsSendComposer.composeSendReq(
            recipients = listOf("+15555550100"),
            body = caption,
            image = MmsSendComposer.OutboundImage(
                contentType = "image/jpeg",
                bytes = jpeg,
                location = "image_0.jpg",
            ),
        )

        assertNotEquals(0x04, firstPartContentTypeLeadByte(pdu))
        val ascii = pdu.toString(Charsets.ISO_8859_1)
        assertTrue("SMIL src must match Content-Location", ascii.contains("""src="image_0.jpg""""))
        assertTrue(ascii.contains("smil.xml"))
        assertTrue(ascii.contains("text_0.txt"))

        val parsed = requireNotNull(MmsPduDecoder.parse(pdu))
        assertEquals(caption, parsed.body)
        assertTrue(parsed.hasPhoto)
        val image = parsed.parts.single { it.isImage }
        assertEquals("image/jpeg", image.contentType)
        assertEquals(MmsSendComposer.CONTENT_ID_IMAGE, image.contentId)
        assertEquals("image_0.jpg", image.contentLocation)
        assertTrue(jpeg.contentEquals(image.bytes))
        val text = parsed.parts.single { it.isTextPlain }
        assertEquals(MmsSendComposer.CONTENT_ID_TEXT, text.contentId)
        assertEquals("text_0.txt", text.contentLocation)
        assertFalse(parsed.parts.any { it.isSmil })
    }

    @Test
    fun composeSendReq_pictureOnly_omitsTextPart() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00)
        val pdu = MmsSendComposer.composeSendReq(
            recipients = listOf("+15555550100"),
            body = "",
            image = MmsSendComposer.OutboundImage(
                contentType = "image/jpeg",
                bytes = jpeg,
                location = "image_0.jpg",
            ),
        )
        val parsed = requireNotNull(MmsPduDecoder.parse(pdu))
        assertEquals("", parsed.body)
        assertTrue(parsed.hasPhoto)
        assertEquals(1, parsed.parts.size)
        assertTrue(parsed.parts.single().isImage)
        val ascii = pdu.toString(Charsets.ISO_8859_1)
        assertTrue(ascii.contains("""src="image_0.jpg""""))
        assertFalse(ascii.contains("text_0.txt"))
    }

    @Test
    fun parseSendConf_readsResponseStatusAndMessageId() {
        val pdu = byteArrayOf(
            0x8C.toByte(), 0x81.toByte(), // Message-Type Send-Conf
            0x8D.toByte(), 0x92.toByte(), // MMS-Version 1.2
            0x92.toByte(), 0x80.toByte(), // Response-Status Ok
            0x8B.toByte(),
        ) + "mid-abc".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val conf = MmsSendComposer.parseSendConf(pdu)
        assertTrue(conf.ok)
        assertEquals(0x80, conf.responseStatus)
        assertEquals("mid-abc", conf.messageId)
    }

    @Test
    fun parseSendConf_nonOkIsFailure() {
        val pdu = byteArrayOf(
            0x8C.toByte(), 0x81.toByte(),
            0x92.toByte(), 0x81.toByte(), // Error-unspecified
        )
        val conf = MmsSendComposer.parseSendConf(pdu)
        assertFalse(conf.ok)
        assertEquals(0x81, conf.responseStatus)
    }

    @Test
    fun parseSendConf_emptyHasNoStatus() {
        val conf = MmsSendComposer.parseSendConf(byteArrayOf())
        assertFalse(conf.ok)
        assertEquals(null, conf.responseStatus)
        assertEquals(null, conf.messageId)
    }

    @Test
    fun parseSendConf_truncatedHasNoStatus() {
        val conf = MmsSendComposer.parseSendConf(byteArrayOf(0x8C.toByte()))
        assertFalse(conf.ok)
        assertEquals(null, conf.responseStatus)
    }

    @Test
    fun parseSendConf_contentTypeFirstHasNoStatus() {
        val conf = MmsSendComposer.parseSendConf(
            byteArrayOf(0x8C.toByte(), 0x81.toByte(), 0x84.toByte()),
        )
        assertFalse(conf.ok)
        assertEquals(null, conf.responseStatus)
    }

    @Test
    fun parseSendConf_strayOctetHasNoStatus() {
        val conf = MmsSendComposer.parseSendConf(byteArrayOf(0x01))
        assertFalse(conf.ok)
        assertEquals(null, conf.responseStatus)
    }

    @Test
    fun composeSendReq_plmnSuffixOnNumericAddress() {
        assertTrue(
            MmsSendComposer.normalizeAddress("+15555550100").endsWith("/TYPE=PLMN"),
        )
        assertEquals(
            "short",
            MmsSendComposer.normalizeAddress("short"),
        )
    }

    /**
     * Walk past MMS headers to the first part's ContentType octet.
     * Returns that byte, or -1 if the PDU is too short to have a part.
     */
    private fun firstPartContentTypeLeadByte(pdu: ByteArray): Int {
        val i = indexOfFirstPartContentType(pdu) ?: return -1
        return pdu[i].toInt() and 0xFF
    }

    /**
     * The bug wrote `HEADER_CONTENT_TYPE and 0x7F` = `0x04` as if ContentType
     * needed an identifier. After the fix the lead is a Value-length (0x03
     * for text/plain + charset) or a well-known short-integer (≥ 0x80).
     */
    private fun firstPartContentTypeLead(pdu: ByteArray): Int {
        val lead = firstPartContentTypeLeadByte(pdu)
        return if (lead == 0x04) 0x04 else -1
    }

    private fun indexOfFirstPartContentType(pdu: ByteArray): Int? {
        var i = 0
        while (i < pdu.size) {
            val header = pdu[i].toInt() and 0xFF
            i++
            if (header == 0x84) {
                // Content-Type is the last MMS header; skip its value, then
                // nEntries / HeadersLen / DataLen, then ContentType.
                i = skipContentTypeValue(pdu, i) ?: return null
                i = skipUintvar(pdu, i) ?: return null // nEntries
                i = skipUintvar(pdu, i) ?: return null // HeadersLen
                i = skipUintvar(pdu, i) ?: return null // DataLen
                return if (i < pdu.size) i else null
            }
            i = skipMmsHeaderValue(pdu, header, i) ?: return null
        }
        return null
    }

    private fun skipContentTypeValue(pdu: ByteArray, start: Int): Int? {
        if (start >= pdu.size) return null
        val v = pdu[start].toInt() and 0xFF
        return when {
            v <= 30 -> {
                val end = start + 1 + v
                if (end > pdu.size) null else end
            }
            v == 31 -> {
                val (len, after) = readUintvar(pdu, start + 1) ?: return null
                val end = after + len
                if (end > pdu.size) null else end
            }
            v in 32..127 -> skipCString(pdu, start)
            else -> start + 1 // well-known short-integer, no params
        }
    }

    private fun skipMmsHeaderValue(pdu: ByteArray, header: Int, start: Int): Int? {
        if (start >= pdu.size) return null
        return when (header) {
            0x8C, 0x8D -> start + 1 // Message-Type, MMS-Version short-int
            0x98 -> skipCString(pdu, start) // Transaction-Id
            0x89, 0x97 -> skipValueLengthField(pdu, start) // From, To
            else -> skipCString(pdu, start)
        }
    }

    private fun skipValueLengthField(pdu: ByteArray, start: Int): Int? {
        if (start >= pdu.size) return null
        val v = pdu[start].toInt() and 0xFF
        return when {
            v <= 30 -> {
                val end = start + 1 + v
                if (end > pdu.size) null else end
            }
            v == 31 -> {
                val (len, after) = readUintvar(pdu, start + 1) ?: return null
                val end = after + len
                if (end > pdu.size) null else end
            }
            else -> skipCString(pdu, start)
        }
    }

    private fun skipCString(pdu: ByteArray, start: Int): Int? {
        var i = start
        if (i < pdu.size && (pdu[i].toInt() and 0xFF) == 0x7F) i++
        while (i < pdu.size) {
            if (pdu[i].toInt() == 0) return i + 1
            i++
        }
        return null
    }

    private fun skipUintvar(pdu: ByteArray, start: Int): Int? {
        val parsed = readUintvar(pdu, start) ?: return null
        return parsed.second
    }

    private fun readUintvar(pdu: ByteArray, start: Int): Pair<Int, Int>? {
        var i = start
        var value = 0
        repeat(5) {
            if (i >= pdu.size) return null
            val b = pdu[i].toInt() and 0xFF
            i++
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return value to i
        }
        return null
    }
}
