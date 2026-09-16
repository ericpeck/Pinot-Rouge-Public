package com.pinotrouge.messaging.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchingTest {

    @Test
    fun `textMatches is case-insensitive substring`() {
        assertTrue(textMatches("Mom", "Call Mom later"))
        assertTrue(textMatches("mom", "MOM"))
        assertFalse(textMatches("xyz", "hello"))
        assertFalse(textMatches("  ", "hello"))
        assertFalse(textMatches("", "hello"))
    }

    @Test
    fun `conversationMatches name address snippet and body`() {
        assertTrue(
            conversationMatches(
                query = "Alice",
                displayName = "Alice Smith",
                address = "+1555",
                snippet = "hi",
            ),
        )
        assertTrue(
            conversationMatches(
                query = "555",
                displayName = "Unknown",
                address = "+15551212",
                snippet = "",
            ),
        )
        assertTrue(
            conversationMatches(
                query = "photos",
                displayName = "Bob",
                address = "1",
                snippet = "send photos please",
            ),
        )
        assertTrue(
            conversationMatches(
                query = "flight",
                displayName = "Airline",
                address = "123",
                snippet = "thanks",
                bodyHits = listOf("Your flight is delayed"),
            ),
        )
        assertFalse(
            conversationMatches(
                query = "zzz",
                displayName = "Alice",
                address = "1",
                snippet = "hi",
                bodyHits = listOf("nope"),
            ),
        )
    }

    @Test
    fun `matchConversations prefers name match snippet then body`() {
        val candidates = listOf(
            ConversationCandidate(
                threadId = 1L,
                address = "+1",
                displayName = "Mom",
                snippet = "see you sunday",
                dateMillis = 100L,
            ),
            ConversationCandidate(
                threadId = 2L,
                address = "+2",
                displayName = "Work",
                snippet = "ok",
                dateMillis = 200L,
            ),
        )
        val bodyHits = mapOf(2L to "boarding pass for flight AA1")

        val mom = matchConversations("mom", candidates, bodyHits)
        assertEquals(1, mom.size)
        assertEquals("Mom", mom[0].name)
        assertEquals("see you sunday", mom[0].snippet)
        assertEquals(SearchHitKind.Conversation, mom[0].kind)
        assertEquals(null, mom[0].tag)

        val flight = matchConversations("flight", candidates, bodyHits)
        assertEquals(1, flight.size)
        assertEquals(2L, flight[0].threadId)
        assertEquals("boarding pass for flight AA1", flight[0].snippet)
    }

    @Test
    fun `matchConversations tags archived via seam`() {
        val candidates = listOf(
            ConversationCandidate(
                threadId = 9L,
                address = "+9",
                displayName = "Old chat",
                snippet = "archive me",
                dateMillis = 1L,
                isArchived = true,
            ),
        )
        val hits = matchConversations("old", candidates)
        assertEquals(1, hits.size)
        assertEquals(SearchHitKind.Archived, hits[0].kind)
        assertEquals(SearchHitTag.Archived, hits[0].tag)
    }

    @Test
    fun `matchHeld finds sender and body and tags`() {
        val held = listOf(
            HeldCandidate("h1", "SPAMCO", "Win a free cruise", 10L),
            HeldCandidate("h2", "Bank", "Your code is 1234", 20L),
        )
        val cruise = matchHeld("cruise", held)
        assertEquals(1, cruise.size)
        assertEquals("h1", cruise[0].heldId)
        assertEquals(SearchHitKind.Held, cruise[0].kind)
        assertEquals(SearchHitTag.HeldByFilter, cruise[0].tag)
        assertEquals(SearchHitGlyph.Funnel, cruise[0].glyph)

        val bank = matchHeld("bank", held)
        assertEquals(1, bank.size)
        assertEquals("h2", bank[0].heldId)
    }

    @Test
    fun `buildJumpToSuggestions uses real names and codes skips phones`() {
        val suggestions = buildJumpToSuggestions(
            recentDisplayNames = listOf(
                "Mom",
                "+1 (555) 123-4567",
                "  Alice  ",
                "Mom",
                "18445550192",
            ),
            recentCodes = listOf("882041", "Mom"),
            max = 5,
        )
        assertEquals(listOf("Mom", "Alice", "882041"), suggestions)
    }

    @Test
    fun `buildJumpToSuggestions empty when no honest data`() {
        assertTrue(
            buildJumpToSuggestions(
                recentDisplayNames = listOf("+1555", "  "),
                recentCodes = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun `looksLikePhoneNumber detects digits and punctuation`() {
        assertTrue(looksLikePhoneNumber("+1 555-0100"))
        assertTrue(looksLikePhoneNumber("18445550192"))
        assertFalse(looksLikePhoneNumber("Mom"))
        assertFalse(looksLikePhoneNumber("LOANFAST"))
    }

    @Test
    fun `resolveSearchResultsBody never fakes empty when unread SMS`() {
        assertEquals(
            SearchResultsBody.Idle,
            resolveSearchResultsBody(
                queryBlank = true,
                canReadMessages = false,
                conversationHitCount = 0,
                heldHitCount = 0,
            ),
        )
        assertEquals(
            SearchResultsBody.NeedsPermission,
            resolveSearchResultsBody(
                queryBlank = false,
                canReadMessages = false,
                conversationHitCount = 0,
                heldHitCount = 0,
            ),
        )
        // Held still searchable without READ_SMS.
        assertEquals(
            SearchResultsBody.Results,
            resolveSearchResultsBody(
                queryBlank = false,
                canReadMessages = false,
                conversationHitCount = 0,
                heldHitCount = 2,
            ),
        )
        assertEquals(
            SearchResultsBody.Empty,
            resolveSearchResultsBody(
                queryBlank = false,
                canReadMessages = true,
                conversationHitCount = 0,
                heldHitCount = 0,
            ),
        )
        assertEquals(
            SearchResultsBody.Results,
            resolveSearchResultsBody(
                queryBlank = false,
                canReadMessages = true,
                conversationHitCount = 1,
                heldHitCount = 0,
            ),
        )
    }
}
