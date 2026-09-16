package com.pinotrouge.messaging.ui.search

/**
 * Pure matching and suggestion helpers for the Search tab.
 * No Android, no I/O — unit-tested.
 */

/** What kind of result row to render (and how open navigates). */
enum class SearchHitKind {
    Conversation,
    Held,
    /**
     * Seam for Wave 9 [feat/archive]. Never emitted until archive exists;
     * matching still accepts [ConversationCandidate.isArchived] so archive
     * can plug in without a second search path.
     */
    Archived,
}

enum class SearchHitTag {
    HeldByFilter,
    Archived,
}

enum class SearchHitGlyph {
    User,
    UsersThree,
    Funnel,
}

/**
 * Conversation (or archived-thread) candidate before formatting for the UI.
 */
data class ConversationCandidate(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val dateMillis: Long,
    val isGroup: Boolean = false,
    /** Always false until feat/archive lands. */
    val isArchived: Boolean = false,
)

data class HeldCandidate(
    val id: String,
    val sender: String,
    val body: String,
    val dateMillis: Long,
)

data class SearchMatch(
    val kind: SearchHitKind,
    val threadId: Long? = null,
    val heldId: String? = null,
    val name: String,
    val dateMillis: Long,
    val snippet: String,
    val glyph: SearchHitGlyph,
    val tag: SearchHitTag?,
)

/**
 * Case-insensitive substring match on any of [haystacks].
 * Blank [query] never matches (idle is not "everything matched").
 */
fun textMatches(query: String, vararg haystacks: String?): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return false
    return haystacks.any { hay ->
        !hay.isNullOrEmpty() && hay.contains(q, ignoreCase = true)
    }
}

fun conversationMatches(
    query: String,
    displayName: String,
    address: String,
    snippet: String,
    bodyHits: Collection<String> = emptyList(),
): Boolean {
    if (textMatches(query, displayName, address, snippet)) return true
    return bodyHits.any { textMatches(query, it) }
}

fun heldMatches(query: String, sender: String, body: String): Boolean =
    textMatches(query, sender, body)

/**
 * Build conversation hits: name/address/snippet first, then body-matched
 * threads (body text becomes the row snippet when that is the only match).
 *
 * [bodyByThreadId] maps threadId → a matching message body (or best snippet).
 */
fun matchConversations(
    query: String,
    candidates: List<ConversationCandidate>,
    bodyByThreadId: Map<Long, String> = emptyMap(),
): List<SearchMatch> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val hits = ArrayList<SearchMatch>(candidates.size)
    for (c in candidates) {
        val bodyHit = bodyByThreadId[c.threadId]
        val nameOrSnippet = textMatches(q, c.displayName, c.address, c.snippet)
        val bodyMatched = bodyHit != null && textMatches(q, bodyHit)
        if (!nameOrSnippet && !bodyMatched) continue

        val snippet = when {
            nameOrSnippet && c.snippet.isNotBlank() -> c.snippet
            bodyMatched && !bodyHit.isNullOrBlank() -> bodyHit
            else -> c.snippet
        }
        val kind = if (c.isArchived) SearchHitKind.Archived else SearchHitKind.Conversation
        val tag = when {
            c.isArchived -> SearchHitTag.Archived
            else -> null
        }
        hits.add(
            SearchMatch(
                kind = kind,
                threadId = c.threadId,
                name = c.displayName.ifBlank { c.address.ifBlank { "Unknown" } },
                dateMillis = c.dateMillis,
                snippet = snippet.replace('\n', ' ').trim(),
                glyph = if (c.isGroup) SearchHitGlyph.UsersThree else SearchHitGlyph.User,
                tag = tag,
            ),
        )
    }
    // Conversations first (caller already ordered candidates); archive seam
    // keeps archived in the same list tagged — design is one flat column.
    return hits
}

fun matchHeld(
    query: String,
    candidates: List<HeldCandidate>,
): List<SearchMatch> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return candidates.mapNotNull { h ->
        if (!heldMatches(q, h.sender, h.body)) return@mapNotNull null
        SearchMatch(
            kind = SearchHitKind.Held,
            heldId = h.id,
            name = h.sender.ifBlank { "Unknown" },
            dateMillis = h.dateMillis,
            snippet = h.body.replace('\n', ' ').trim(),
            glyph = SearchHitGlyph.Funnel,
            tag = SearchHitTag.HeldByFilter,
        )
    }
}

/**
 * "Jump to" pills from real recent data only.
 *
 * Order: contact / thread display names (skip bare phone numbers), then
 * recent OTP codes. Caps at [max]. Empty input → empty list (omit section).
 */
fun buildJumpToSuggestions(
    recentDisplayNames: List<String>,
    recentCodes: List<String> = emptyList(),
    max: Int = 5,
): List<String> {
    if (max <= 0) return emptyList()
    val seen = LinkedHashSet<String>()
    for (raw in recentDisplayNames) {
        val name = raw.trim()
        if (name.isEmpty()) continue
        if (looksLikePhoneNumber(name)) continue
        // Dedupe case-insensitively but keep first spelling.
        val key = name.lowercase()
        if (seen.any { it.lowercase() == key }) continue
        seen.add(name)
        if (seen.size >= max) return seen.toList()
    }
    for (raw in recentCodes) {
        val code = raw.trim()
        if (code.isEmpty()) continue
        if (seen.any { it.equals(code, ignoreCase = true) }) continue
        seen.add(code)
        if (seen.size >= max) break
    }
    return seen.toList()
}

/**
 * Pure digits (and common phone punctuation) — not a useful Jump-to label.
 */
internal fun looksLikePhoneNumber(label: String): Boolean {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return false
    // Allow a leading +; everything else must be digit / space / dash / paren / dot.
    val body = if (trimmed.startsWith("+")) trimmed.drop(1) else trimmed
    if (body.none { it.isDigit() }) return false
    return body.all { it.isDigit() || it.isWhitespace() || it == '-' || it == '(' || it == ')' || it == '.' }
}

/**
 * Resolves which body the Search tab should show when SMS is unreadable.
 *
 * Held lives in Room and stays searchable without READ_SMS. Conversations
 * do not. Never collapse "cannot read" into a fake empty match list.
 */
enum class SearchResultsBody {
    Idle,
    NeedsPermission,
    Empty,
    Results,
}

fun resolveSearchResultsBody(
    queryBlank: Boolean,
    canReadMessages: Boolean,
    conversationHitCount: Int,
    heldHitCount: Int,
): SearchResultsBody {
    if (queryBlank) return SearchResultsBody.Idle
    val hasHits = conversationHitCount > 0 || heldHitCount > 0
    if (hasHits) return SearchResultsBody.Results
    // No hits: if we could not scan the provider, say so — do not claim "Nothing matched".
    if (!canReadMessages) return SearchResultsBody.NeedsPermission
    return SearchResultsBody.Empty
}
