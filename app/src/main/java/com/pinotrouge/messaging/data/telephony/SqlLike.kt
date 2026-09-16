package com.pinotrouge.messaging.data.telephony

/**
 * Builds a SQLite `LIKE` pattern for case-insensitive substring match.
 * Callers must use `ESCAPE '!'` so `%`, `_`, and `!` in the user's query
 * are literals, not wildcards.
 *
 * Blank [needle] returns null — do not run a provider query for idle search.
 */
internal fun sqlLikeContains(needle: String): String? {
    val trimmed = needle.trim()
    if (trimmed.isEmpty()) return null
    return buildString(trimmed.length + 2) {
        append('%')
        for (c in trimmed) {
            when (c) {
                '!', '%', '_' -> {
                    append('!')
                    append(c)
                }
                else -> append(c)
            }
        }
        append('%')
    }
}
