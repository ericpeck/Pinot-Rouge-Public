package com.pinotrouge.messaging.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern as JavaPattern

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

    @Test
    fun `nested class union is converted and matches like Java`() {
        val pattern = "[a-d[m-p]]"
        assertTrue(RegexPatterns.validate(pattern) is RegexPatterns.Validity.Valid)
        val migrated = migrateJavaRegex(pattern)
        assertNotNull(migrated)
        assertEquals("[a-dm-p]", migrated!!.effectivePattern)
        assertTrue(javaFind(pattern, "a"))
        assertTrue(RegexPatterns.containsMatch(pattern, "a"))
        assertTrue(RegexPatterns.containsMatch(pattern, "n"))
        assertFalse(RegexPatterns.containsMatch(pattern, "e"))
    }

    @Test
    fun `intersection character class pauses rather than compiling as RE2 literals`() {
        val pattern = "[a-z&&[^aeiou]]"
        assertTrue(javaFind(pattern, "b"))
        assertFalse(javaFind(pattern, "a"))
        assertTrue(RegexPatterns.validate(pattern) is RegexPatterns.Validity.UnsupportedSyntax)
        assertNull(migrateJavaRegex(pattern))
        assertFalse(RegexPatterns.containsMatch(pattern, "b"))
    }

    @Test
    fun `dollar matches a trailing newline the way Java did`() {
        val pattern = "a$"
        assertTrue(RegexPatterns.validate(pattern) is RegexPatterns.Validity.Valid)
        assertTrue(javaFind(pattern, "a\n"))
        assertTrue(RegexPatterns.containsMatch(pattern, "a\n"))
        assertTrue(RegexPatterns.containsMatch(pattern, "a"))
        assertTrue(javaFind(pattern, "xa"))
        assertTrue(RegexPatterns.containsMatch(pattern, "xa"))
        assertFalse(javaFind(pattern, "ax"))
        assertFalse(RegexPatterns.containsMatch(pattern, "ax"))
        assertEquals(javaFind(pattern, "a\n\n"), RegexPatterns.containsMatch(pattern, "a\n\n"))
    }

    @Test
    fun `saved pattern text is not rewritten by migration`() {
        val nested = "[a-d[m-p]]"
        val dollar = "a$"
        assertEquals("[a-dm-p]", migrateJavaRegex(nested)!!.effectivePattern)
        assertEquals("a$", migrateJavaRegex(dollar)!!.effectivePattern)
        assertTrue(migrateJavaRegex(dollar)!!.stripTrailingNewlineForDollar)
    }

    private fun javaFind(pattern: String, body: String): Boolean =
        JavaPattern.compile(pattern, JavaPattern.CASE_INSENSITIVE).matcher(body).find()
}
