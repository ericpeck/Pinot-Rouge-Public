package com.pinotrouge.messaging.rules

/**
 * Java [java.util.regex.Pattern] → RE2 migration for saved [TextOp.MATCHES_REGEX]
 * patterns.
 *
 * Policy (do not persist a rewrite; [Condition.value] stays the author's text):
 * 1. Lookaround, backreferences, and other syntax RE2 rejects already pause
 *    the rule via [RegexPatterns.validate].
 * 2. Nested character-class unions (`[a-d[m-p]]`) are flattened to the RE2
 *    class with the same members (`[a-dm-p]`). Matching uses the flatten;
 *    the saved pattern is unchanged.
 * 3. Java `$` without `(?m)` matches before a final newline; RE2 `$` does not.
 *    Matching also searches the haystack with that one trailing newline
 *    removed. The pattern string is not rewritten (RE2 has no lookaround).
 * 4. Intersection / subtraction (`&&` inside a class) and a nested **negated**
 *    class cannot be rewritten without lookaround. Those rules pause.
 * 5. [java.util.regex.Pattern] is never used on the SMS path.
 */
internal data class RegexMigration(
    val effectivePattern: String,
    val stripTrailingNewlineForDollar: Boolean,
)

internal fun migrateJavaRegex(pattern: String): RegexMigration? {
    val out = StringBuilder(pattern.length)
    var i = 0
    var escaped = false
    var dollarOutsideClass = false
    var multiline = false
    while (i < pattern.length) {
        val c = pattern[i]
        if (escaped) {
            out.append(c)
            escaped = false
            i++
            continue
        }
        if (c == '\\') {
            out.append(c)
            escaped = true
            i++
            continue
        }
        if (c == '[') {
            val parsed = migrateJavaCharClass(pattern, i) ?: return null
            out.append(parsed.text)
            i = parsed.end
            continue
        }
        if (c == '(' && i + 1 < pattern.length && pattern[i + 1] == '?') {
            val close = indexOfUnescaped(pattern, ')', i + 2)
            if (close < 0) {
                out.append(c)
                i++
                continue
            }
            val inner = pattern.substring(i + 2, close)
            val colon = inner.indexOf(':')
            val flags = if (colon >= 0) inner.substring(0, colon) else inner
            if (flagsEnableMultiline(flags)) multiline = true
            if (flagsDisableMultiline(flags)) multiline = false
            out.append(pattern, i, close + 1)
            i = close + 1
            continue
        }
        if (c == '$') dollarOutsideClass = true
        out.append(c)
        i++
    }
    return RegexMigration(
        effectivePattern = out.toString(),
        stripTrailingNewlineForDollar = dollarOutsideClass && !multiline,
    )
}

private data class MigratedClass(
    val text: String,
    val body: String,
    val negated: Boolean,
    val end: Int,
)

/**
 * Parse a Java character class starting at `s[start] == '['`. Nested
 * un-negated classes are unions and are spliced into [MigratedClass.body].
 * `&&` or a nested negated class cannot be rewritten — return null.
 */
private fun migrateJavaCharClass(s: String, start: Int): MigratedClass? {
    if (start >= s.length || s[start] != '[') return null
    var i = start + 1
    if (i >= s.length) return null
    val negated = if (s[i] == '^') {
        i++
        true
    } else {
        false
    }
    val body = StringBuilder()
    // Java: a `]` immediately after `[` / `[^` is a literal.
    if (i < s.length && s[i] == ']') {
        body.append(']')
        i++
    }
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            body.append(c).append(s[i + 1])
            i += 2
            continue
        }
        if (c == '&' && i + 1 < s.length && s[i + 1] == '&') {
            return null
        }
        if (c == '[') {
            val inner = migrateJavaCharClass(s, i) ?: return null
            if (inner.negated) return null
            body.append(inner.body)
            i = inner.end
            continue
        }
        if (c == ']') {
            val text = buildString {
                append('[')
                if (negated) append('^')
                append(body)
                append(']')
            }
            return MigratedClass(text, body.toString(), negated, i + 1)
        }
        body.append(c)
        i++
    }
    return null
}

private fun indexOfUnescaped(s: String, ch: Char, from: Int): Int {
    var i = from
    var escaped = false
    while (i < s.length) {
        val c = s[i]
        if (escaped) {
            escaped = false
            i++
            continue
        }
        if (c == '\\') {
            escaped = true
            i++
            continue
        }
        if (c == ch) return i
        i++
    }
    return -1
}

/**
 * Inline flag group, e.g. `im`, `m-i`, `-m`. `m` before `-` (or with no `-`)
 * turns MULTILINE on; `m` after `-` turns it off.
 */
private fun flagsEnableMultiline(flags: String): Boolean {
    val dash = flags.indexOf('-')
    val on = if (dash >= 0) flags.substring(0, dash) else flags
    return 'm' in on
}

private fun flagsDisableMultiline(flags: String): Boolean {
    val dash = flags.indexOf('-')
    if (dash < 0) return false
    return 'm' in flags.substring(dash + 1)
}
