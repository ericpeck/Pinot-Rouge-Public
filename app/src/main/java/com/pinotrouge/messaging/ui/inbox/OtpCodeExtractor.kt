package com.pinotrouge.messaging.ui.inbox

/**
 * Pulls a 4–8 digit verification code from an SMS body.
 *
 * Looks for a digit run near words like "code", "otp", "pin", "verification".
 * Returns the first match; null if nothing looks like an OTP.
 */
object OtpCodeExtractor {

    /**
     * Word-near-digits: "code is 882041", "882041 is your code", "OTP: 1234".
     * Case-insensitive. Prefers the word-anchored form over bare digits.
     */
    private val NEAR_CODE_WORD = Regex(
        pattern = """(?i)(?:(?:your\s+)?(?:verification\s+)?(?:code|otp|pin|passcode|password)\s*(?:is|:)?\s*[#:]?\s*)(\d{4,8})\b""" +
            """|(?:\b(\d{4,8})\b\s*(?:is\s+)?(?:your\s+)?(?:verification\s+)?(?:code|otp|pin|passcode))""",
    )

    fun extract(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val match = NEAR_CODE_WORD.find(body) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }
}
