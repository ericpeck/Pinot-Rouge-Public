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
            queryBody = "Hello",
            extraBody = null,
        )
        assertEquals("+15555550100", parsed?.recipient)
        assertEquals("Hello", parsed?.body)
    }

    @Test
    fun `rejects schemes outside the SMS family`() {
        assertNull(
            SendToParser.parse(
                scheme = "https",
                schemeSpecificPart = "example.com",
                queryBody = null,
                extraBody = "hi",
            ),
        )
        assertNull(
            SendToParser.parse(
                scheme = "file",
                schemeSpecificPart = "/tmp/x",
                queryBody = null,
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
                queryBody = null,
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
            queryBody = null,
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
                queryBody = null,
                extraBody = "",
            ),
        )
    }

    @Test
    fun `allowed schemes are only the four ROLE_SMS send-to schemes`() {
        assertTrue(SendToParser.ALLOWED_SCHEMES == setOf("sms", "smsto", "mms", "mmsto"))
    }
}
