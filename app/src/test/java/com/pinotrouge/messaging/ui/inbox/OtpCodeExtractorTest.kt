package com.pinotrouge.messaging.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpCodeExtractorTest {

    @Test
    fun `extracts code after 'code is'`() {
        assertEquals(
            "882041",
            OtpCodeExtractor.extract("Your Northgate Bank code is 882041. Never share it."),
        )
    }

    @Test
    fun `extracts OTP colon form`() {
        assertEquals("123456", OtpCodeExtractor.extract("Your OTP: 123456"))
    }

    @Test
    fun `extracts verification code`() {
        assertEquals(
            "99421",
            OtpCodeExtractor.extract("Your verification code is 99421"),
        )
    }

    @Test
    fun `extracts reversed form`() {
        assertEquals(
            "441722",
            OtpCodeExtractor.extract("441722 is your code. Do not share."),
        )
    }

    @Test
    fun `returns null when no code word`() {
        assertNull(OtpCodeExtractor.extract("Card ending 4417: $84.20 at Rowan Grocery."))
    }

    @Test
    fun `returns null for blank`() {
        assertNull(OtpCodeExtractor.extract(null))
        assertNull(OtpCodeExtractor.extract(""))
        assertNull(OtpCodeExtractor.extract("   "))
    }
}
