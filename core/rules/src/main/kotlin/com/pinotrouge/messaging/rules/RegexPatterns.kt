package com.pinotrouge.messaging.rules

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/**
 * User-authored [TextOp.MATCHES_REGEX] patterns.
 *
 * Matching uses RE2 (linear time in pattern × input). Java's
 * `java.util.regex` is **not** used: nested quantifiers on that engine can
 * stall the SMS receiver for seconds. Unsupported syntax (lookaround,
 * backreferences, possessive quantifiers, and similar) does **not** match as
 * false — the whole rule is [Rule.isUnreadable] so it cannot silently
 * under-filter.
 *
 * Supported (the RE2 subset, case-insensitive): literals, `.`, character
 * classes, `* + ? {n,m}`, alternation, capturing groups for grouping only,
 * and `^ $`.
 */
object RegexPatterns {

    const val MAX_PATTERN_LENGTH = 256
    const val MAX_INPUT_LENGTH = 8_192

    /** Shown when a saved or draft rule cannot run. Builder copy uses the same string. */
    const val UNRUNNABLE_REASON =
        "This pattern cannot run: Pinot Rouge uses linear-time regex without lookaround or backreferences. The filter is paused until the pattern is changed."

    sealed class Validity {
        data object Valid : Validity()
        data object Empty : Validity()
        data object TooLong : Validity()
        data class UnsupportedSyntax(val detail: String) : Validity()
    }

    fun validate(pattern: String): Validity {
        if (pattern.isEmpty()) return Validity.Empty
        if (pattern.length > MAX_PATTERN_LENGTH) return Validity.TooLong
        return try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
            Validity.Valid
        } catch (e: PatternSyntaxException) {
            Validity.UnsupportedSyntax(e.message ?: "unsupported syntax")
        }
    }

    fun containsMatch(pattern: String, body: String): Boolean {
        if (validate(pattern) !is Validity.Valid) return false
        val haystack = if (body.length > MAX_INPUT_LENGTH) {
            body.substring(0, MAX_INPUT_LENGTH)
        } else {
            body
        }
        val compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        return compiled.matcher(haystack).find()
    }
}
