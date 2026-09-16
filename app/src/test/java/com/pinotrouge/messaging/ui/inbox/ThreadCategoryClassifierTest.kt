package com.pinotrouge.messaging.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadCategoryClassifierTest {

    @Test
    fun `short code plus otp is Codes`() {
        val cat = ThreadCategoryClassifier.classify(
            address = "88022",
            isKnownContact = false,
            bodyOrSnippet = "Your Northgate Bank code is 882041. Never share it.",
        )
        assertEquals(ThreadCategory.Codes, cat)
    }

    @Test
    fun `known contact is People even with money words`() {
        val cat = ThreadCategoryClassifier.classify(
            address = "+15551234567",
            isKnownContact = true,
            bodyOrSnippet = "I paid the invoice already",
        )
        assertEquals(ThreadCategory.People, cat)
    }

    @Test
    fun `money keywords classify as Money`() {
        val cat = ThreadCategoryClassifier.classify(
            address = "BANKALERT",
            isKnownContact = false,
            bodyOrSnippet = "Card ending 4417: $84.20 at Rowan Grocery.",
        )
        assertEquals(ThreadCategory.Money, cat)
    }

    @Test
    fun `travel keywords classify as Travel`() {
        val cat = ThreadCategoryClassifier.classify(
            address = "DELTA",
            isKnownContact = false,
            bodyOrSnippet = "Flight DL428 now departs gate B12.",
        )
        assertEquals(ThreadCategory.Travel, cat)
    }

    @Test
    fun `unknown plain text is Other`() {
        val cat = ThreadCategoryClassifier.classify(
            address = "+18445550192",
            isKnownContact = false,
            bodyOrSnippet = "Hey, free lunch tomorrow?",
        )
        assertEquals(ThreadCategory.Other, cat)
    }

    @Test
    fun `isShortCode matches 3 to 6 digit senders`() {
        assertTrue(ThreadCategoryClassifier.isShortCode("88022"))
        assertTrue(ThreadCategoryClassifier.isShortCode("123"))
        assertTrue(ThreadCategoryClassifier.isShortCode("123456"))
        assertFalse(ThreadCategoryClassifier.isShortCode("12"))
        assertFalse(ThreadCategoryClassifier.isShortCode("1234567"))
        assertFalse(ThreadCategoryClassifier.isShortCode("+188022"))
        assertFalse(ThreadCategoryClassifier.isShortCode("CARTLY"))
    }

    @Test
    fun `chip All matches every category`() {
        ThreadCategory.entries.forEach {
            assertTrue(InboxChip.All.matches(it))
        }
    }

    @Test
    fun `chip Codes matches only Codes`() {
        assertTrue(InboxChip.Codes.matches(ThreadCategory.Codes))
        assertFalse(InboxChip.Codes.matches(ThreadCategory.People))
        assertFalse(InboxChip.Codes.matches(ThreadCategory.Other))
    }
}
