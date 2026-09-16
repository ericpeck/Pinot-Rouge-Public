package com.pinotrouge.messaging.data.telephony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduDecoderTest {

    @Test
    fun `messageType reads 0x8C header`() {
        // Minimal: Message-Type (0x8C) = Notification-Ind (0x82). Sequential
        // parse still treats the first header as Message-Type; no body follows.
        val pdu = byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x00)
        assertEquals(MmsPduDecoder.TYPE_NOTIFICATION_IND, MmsPduDecoder.messageType(pdu))
    }

    @Test
    fun `messageType reads retrieve-conf`() {
        val pdu = byteArrayOf(0x8C.toByte(), 0x84.toByte())
        assertEquals(MmsPduDecoder.TYPE_RETRIEVE_CONF, MmsPduDecoder.messageType(pdu))
    }

    @Test
    fun `parseNotification reads From value-length and Content-Location text-string`() {
        // Previous version scanned the whole PDU as Latin-1 for phone-like
        // strings. From-value is value-length + 0x80 + encoded-string; a bare
        // 0x89 followed by ASCII is not a valid PDU and is no longer accepted.
        val from = "+15551234567"
        val url = "http://mmsc.carrier.example/mms/a1b2c3"
        val inner = byteArrayOf(0x80.toByte()) + from.toByteArray(Charsets.US_ASCII) + 0
        val pdu = byteArrayOf(0x8C.toByte(), 0x82.toByte()) +
            byteArrayOf(0x89.toByte(), inner.size.toByte()) + inner +
            byteArrayOf(0x83.toByte()) + url.toByteArray(Charsets.US_ASCII) + 0

        val n = MmsPduDecoder.parseNotification(pdu)
        assertNotNull(n)
        assertEquals(from, n!!.originator)
        assertEquals(url, n.contentLocation)
    }

    @Test
    fun `toIncomingMms null on empty pdu`() {
        assertNull(MmsPduDecoder.toIncomingMms(ByteArray(0)))
        assertNull(MmsPduDecoder.toIncomingMms(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun `IncomingMms allAddresses distinct originator plus participants`() {
        val m = IncomingMms(
            originator = "A",
            participants = listOf("B", "A", "C"),
            body = "hi",
        )
        assertEquals(listOf("A", "B", "C"), m.allAddresses)
    }

    @Test
    fun `IncomingMms hasAttachment still constructible and derives from photo or part`() {
        val legacy = IncomingMms(originator = "A", body = "", hasAttachment = true)
        assertTrue(legacy.hasAttachment)
        assertFalse(legacy.hasPhoto)
        assertFalse(legacy.hasAnyPart)

        val photo = IncomingMms(originator = "A", body = "", hasPhoto = true)
        assertTrue(photo.hasAttachment)
        val other = IncomingMms(originator = "A", body = "", hasAnyPart = true)
        assertTrue(other.hasAttachment)
    }

    @Test
    fun `nowsms retrieve-conf headers match published dump and skip SMIL`() {
        val pdu = loadGolden("nowsms-retrieve-conf.bin")
        val m = requireNotNull(MmsPduDecoder.toIncomingMms(pdu, receivedAtMillis = 1_000L))
        assertEquals(MmsPduDecoder.TYPE_RETRIEVE_CONF, m.messageType)
        assertEquals("+35799536214", m.originator)
        assertEquals(listOf("+447740305115"), m.participants)
        assertEquals("The Matrix", m.subject)
        assertEquals("1w774QoKutEP9fzXV54nbA ", m.transactionId)
        assertEquals("Follow the white rabbit.", m.body)
        assertFalse(m.hasPhoto)
        assertFalse(m.hasAnyPart)
        assertFalse(m.hasAttachment)
        assertEquals(1, m.parts.size)
        assertTrue(m.parts.single().isTextPlain)
        assertFalse(m.parts.any { it.isSmil })
    }

    @Test
    fun `gm well-known octets produce an image and skip SMIL`() {
        val pdu = loadGolden("gm-wellknown.bin")
        val m = requireNotNull(MmsPduDecoder.toIncomingMms(pdu, receivedAtMillis = 1_000L))
        assertEquals("+15550001000", m.originator)
        assertEquals(listOf("+15550002000"), m.participants)
        assertEquals("Photo", m.subject)
        assertEquals("gm-txn-1", m.transactionId)
        assertEquals("café", m.body)
        assertTrue(m.hasPhoto)
        assertTrue(m.hasAnyPart)
        assertTrue(m.hasAttachment)
        assertEquals(2, m.parts.size)
        assertTrue(m.parts[0].isTextPlain)
        assertEquals("image/jpeg", m.parts[1].contentType)
        assertTrue(m.parts[1].isImage)
        assertFalse(m.parts.any { it.isSmil })
        assertEquals("utf-8", m.parts[0].charset)
    }

    @Test
    fun `picture-only jpeg has no body and hasPhoto`() {
        val pdu = loadGolden("picture-only.bin")
        val m = requireNotNull(MmsPduDecoder.toIncomingMms(pdu, receivedAtMillis = 1_000L))
        assertEquals("+15550003000", m.originator)
        assertTrue(m.participants.isEmpty())
        assertEquals("", m.body)
        assertTrue(m.hasPhoto)
        assertTrue(m.hasAnyPart)
        assertEquals(1, m.parts.size)
        assertEquals("image/jpeg", m.parts.single().contentType)
    }

    @Test
    fun `digits in jpeg payload are not participants`() {
        val pdu = loadGolden("digits-in-image.bin")
        val m = requireNotNull(MmsPduDecoder.toIncomingMms(pdu, receivedAtMillis = 1_000L))
        assertEquals("+15550009999", m.originator)
        assertEquals(listOf("+15550008888"), m.participants)
        assertFalse(m.allAddresses.contains("+15551234567"))
        assertTrue(m.hasPhoto)
        val jpeg = m.parts.single { it.isImage }.bytes
        assertTrue(jpeg.toString(Charsets.ISO_8859_1).contains("+15551234567"))
    }

    @Test
    fun `notification has no photo bit and fills location expiry transactionId`() {
        val receivedAt = 1_700_000_000_000L
        val pdu = loadGolden("notification.ind.bin")
        assertEquals(
            MmsPduDecoder.TYPE_NOTIFICATION_IND,
            MmsPduDecoder.messageType(pdu),
        )
        val m = requireNotNull(MmsPduDecoder.toIncomingMms(pdu, receivedAtMillis = receivedAt))
        assertEquals(MmsPduDecoder.TYPE_NOTIFICATION_IND, m.messageType)
        assertEquals("+15550001111", m.originator)
        assertEquals("http://mmsc.example/mms/xyz", m.contentLocation)
        assertEquals("txn-abc", m.transactionId)
        assertEquals(receivedAt + 86_400_000L, m.expiryMillis)
        assertEquals("", m.body)
        assertTrue(m.parts.isEmpty())
        assertFalse(m.hasPhoto)
        assertFalse(m.hasAnyPart)
        assertFalse(m.hasAttachment)
        assertTrue(m.participants.isEmpty())
    }

    @Test
    fun `truncated pdu does not throw`() {
        val pdu = loadGolden("truncated.bin")
        val parsed = MmsPduDecoder.toIncomingMms(pdu, receivedAtMillis = 1_000L)
        // Headers and the first text/plain part are intact; the JPEG is cut
        // mid-payload. Must not throw. Drop the incomplete part, keep the rest.
        val m = requireNotNull(parsed)
        assertEquals("+15550004444", m.originator)
        assertEquals("hi", m.body)
        assertFalse(m.parts.any { it.isImage })
    }

    @Test
    fun `composeNotifyResp is M-NotifyResp-ind echoing the transaction id`() {
        val pdu = MmsPduDecoder.composeNotifyResp("txn-abc")
        assertEquals(MmsPduDecoder.TYPE_NOTIFYRESP_IND, MmsPduDecoder.messageType(pdu))
        assertEquals(0x8C.toByte(), pdu[0])
        assertEquals(0x83.toByte(), pdu[1])
        assertEquals(0x8D.toByte(), pdu[2])
        assertEquals(0x92.toByte(), pdu[3]) // version 1.2 as short-integer
        assertEquals(0x98.toByte(), pdu[4])
        val idEnd = 5 + pdu.drop(5).indexOfFirst { it == 0.toByte() }
        assertEquals("txn-abc", pdu.copyOfRange(5, idEnd).toString(Charsets.US_ASCII))
        assertEquals(0x95.toByte(), pdu[idEnd + 1])
        assertEquals(0x81.toByte(), pdu[idEnd + 2])

        val fromBytes = MmsPduDecoder.composeNotifyResp("txn-abc".toByteArray(Charsets.US_ASCII))
        assertArrayEquals(pdu, fromBytes)
    }

    @Test
    fun `MmsPart equals compares bytes by content`() {
        val a = MmsPart("image/jpeg", bytes = byteArrayOf(1, 2, 3))
        val b = MmsPart("image/jpeg", bytes = byteArrayOf(1, 2, 3))
        val c = MmsPart("image/jpeg", bytes = byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        assertTrue(a.isImage)
        assertFalse(a.isTextPlain)
        assertFalse(a.isSmil)
        val smil = MmsPart(
            "text/plain",
            contentId = "<smil>",
            contentLocation = "smil.xml",
            bytes = byteArrayOf(),
        )
        assertTrue(smil.isSmil)
    }

    private fun loadGolden(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/mms/$name")
        requireNotNull(stream) { "missing golden /mms/$name" }
        return stream.use { it.readBytes() }
    }
}
