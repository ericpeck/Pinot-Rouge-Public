package com.pinotrouge.messaging.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexPatternsTest {

    @Test
    fun `seed loan-and-crypto pattern is valid RE2`() {
        val pattern = "(pre-?approved|no credit check|crypto|wallet)"
        assertTrue(RegexPatterns.validate(pattern) is RegexPatterns.Validity.Valid)
        assertTrue(RegexPatterns.containsMatch(pattern, "PRE-APPROVED for 5000"))
        assertFalse(RegexPatterns.containsMatch(pattern, "see you at 5"))
    }

    @Test
    fun `lookaround is unsupported syntax`() {
        assertTrue(
            RegexPatterns.validate("(?=pre-approved).+") is RegexPatterns.Validity.UnsupportedSyntax,
        )
    }

    @Test
    fun `backreference is unsupported syntax`() {
        assertTrue(
            RegexPatterns.validate("(sale)\\1") is RegexPatterns.Validity.UnsupportedSyntax,
        )
    }

    @Test
    fun `empty and oversized patterns are not valid`() {
        assertTrue(RegexPatterns.validate("") is RegexPatterns.Validity.Empty)
        assertTrue(
            RegexPatterns.validate("a".repeat(RegexPatterns.MAX_PATTERN_LENGTH + 1))
                is RegexPatterns.Validity.TooLong,
        )
    }
}
