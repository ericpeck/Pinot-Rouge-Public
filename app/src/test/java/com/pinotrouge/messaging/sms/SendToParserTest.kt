package com.pinotrouge.messaging.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendToParserTest {

    @Test
    fun `sms scheme with recipient and query body`() {
        val parsed = SendToParser.parse(
            scheme = "sms",
            schemeSpecificPart = "+15555550100?body=Hello",
            extraBody = null,
        )
        assertEquals("+15555550100", parsed?.recipient)
        assertEquals("Hello", parsed?.body)
    }

    @Test
    fun `opaque ssp keeps plus on the recipient and decodes body`() {
        val parsed = SendToParser.parse(
            scheme = "sms",
            schemeSpecificPart = "+15555550100?body=Hello%20there",
        )
        assertEquals("+15555550100", parsed?.recipient)
        assertEquals("Hello there", parsed?.body)
    }

    @Test
    fun `hierarchical ssp drops the authority slashes`() {
        val parsed = SendToParser.parse(
            scheme = "smsto",
            schemeSpecificPart = "//5550100?body=Hi",
        )
        assertEquals("5550100", parsed?.recipient)
        assertEquals("Hi", parsed?.body)
    }

    @Test
    fun `sms_body extra fills in when the URI has no body query`() {
        val parsed = SendToParser.parse(
            scheme = "sms",
            schemeSpecificPart = "5550100",
            extraBody = "from extra",
        )
        assertEquals("5550100", parsed?.recipient)
        assertEquals("from extra", parsed?.body)
    }

    @Test
    fun `query body wins over extras`() {
        val parsed = SendToParser.parse(
            scheme = "mms",
            schemeSpecificPart = "5550100?body=from-uri",
            extraBody = "from extra",
        )
        assertEquals("from-uri", parsed?.body)
    }

    @Test
    fun `rejects schemes outside the SMS family`() {
        assertNull(
            SendToParser.parse(
                scheme = "https",
                schemeSpecificPart = "example.com",
                extraBody = "hi",
            ),
        )
        assertNull(
            SendToParser.parse(
                scheme = "file",
                schemeSpecificPart = "/tmp/x",
                extraBody = "hi",
            ),
        )
    }

    @Test
    fun `rejects oversized recipient rather than truncating a number`() {
        val tooLong = "1".repeat(SendToParser.MAX_RECIPIENT_LENGTH + 1)
        assertNull(
            SendToParser.parse(
                scheme = "smsto",
                schemeSpecificPart = tooLong,
                extraBody = "hi",
            ),
        )
    }

    @Test
    fun `caps an oversized body from another app`() {
        val huge = "a".repeat(SendToParser.MAX_BODY_LENGTH + 50)
        val parsed = SendToParser.parse(
            scheme = "sms",
            schemeSpecificPart = "5550100",
            extraBody = huge,
        )
        assertEquals("5550100", parsed?.recipient)
        assertEquals(SendToParser.MAX_BODY_LENGTH, parsed?.body?.length)
    }

    @Test
    fun `empty recipient and body is ignored`() {
        assertNull(
            SendToParser.parse(
                scheme = "sms",
                schemeSpecificPart = "",
                extraBody = "",
            ),
        )
    }

    @Test
    fun `malformed percent-encoding does not throw`() {
        val parsed = SendToParser.parse(
            scheme = "sms",
            schemeSpecificPart = "5550100?body=%nothex",
        )
        assertEquals("5550100", parsed?.recipient)
        assertEquals("%nothex", parsed?.body)
    }

    @Test
    fun `allowed schemes are only the four ROLE_SMS send-to schemes`() {
        assertTrue(SendToParser.ALLOWED_SCHEMES == setOf("sms", "smsto", "mms", "mmsto"))
    }
}
