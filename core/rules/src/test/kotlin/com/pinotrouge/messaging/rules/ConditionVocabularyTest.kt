package com.pinotrouge.messaging.rules

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the condition vocabulary to the design prototype.
 *
 * These strings are user-visible: they are the words the filter builder puts in
 * its dropdowns and the words the "In plain words" sentence is assembled from.
 * Reworded operators silently change what a user believes their filter does, so
 * they are pinned here rather than left to drift.
 *
 * Reference: `design reference/Android SMS messaging app/PinotPhone.dc.html`,
 * `opsFor()` at line 575 and `needsValue()` at line 582.
 */
class ConditionVocabularyTest {

    @Test
    fun `sender operators match the prototype`() {
        assertEquals(
            listOf("is not in my contacts", "is", "starts with", "is a short code"),
            SenderOp.entries.map { it.label },
        )
    }

    @Test
    fun `text operators match the prototype`() {
        assertEquals(
            listOf("contains any of", "does not contain", "matches the pattern"),
            TextOp.entries.map { it.label },
        )
    }

    @Test
    fun `link operators match the prototype`() {
        assertEquals(
            listOf("is present", "is a shortened link", "is on this domain"),
            LinkOp.entries.map { it.label },
        )
    }

    @Test
    fun `time operators match the prototype`() {
        assertEquals(
            listOf("is between", "is on a weekend"),
            TimeOp.entries.map { it.label },
        )
    }

    /**
     * The prototype's `needsValue()` returns false for exactly these five
     * operators; every other one renders a text input beneath the dropdowns.
     */
    @Test
    fun `only the self-contained operators take no value`() {
        val valueless = buildList {
            SenderOp.entries.filterNot { it.needsValue }.forEach { add(it.label) }
            TextOp.entries.filterNot { it.needsValue }.forEach { add(it.label) }
            LinkOp.entries.filterNot { it.needsValue }.forEach { add(it.label) }
            TimeOp.entries.filterNot { it.needsValue }.forEach { add(it.label) }
            AttachmentOp.entries.filterNot { it.needsValue }.forEach { add(it.label) }
        }
        assertEquals(
            listOf(
                "is not in my contacts",
                "is a short code",
                "is present",
                "is a shortened link",
                "is on a weekend",
                "is present",
            ),
            valueless,
        )
    }

    @Test
    fun `condition exposes the field wording used in sentences`() {
        assertEquals("the sender", Condition.Sender(SenderOp.IS, "Mom").fieldLabel)
        assertEquals("the message text", Condition.Text(TextOp.CONTAINS_ANY, "sale").fieldLabel)
        assertEquals("a link", Condition.Link(LinkOp.PRESENT).fieldLabel)
        assertEquals("the arrival time", Condition.Time(TimeOp.ON_WEEKEND).fieldLabel)
        assertEquals("a picture", Condition.Attachment(AttachmentOp.HAS_PHOTO).fieldLabel)
    }

    /** The dropdown says "a link in the message"; the sentence says "a link". Both ship. */
    @Test
    fun `link wording differs between the dropdown and the sentence`() {
        assertEquals("a link in the message", Condition.Link.SELECT_LABEL)
        assertEquals("a link", Condition.Link.FIELD_LABEL)
        assertEquals("the time it arrives", Condition.Time.SELECT_LABEL)
        assertEquals("the arrival time", Condition.Time.FIELD_LABEL)
    }

    @Test
    fun `attachment wording is Eric's accepted copy`() {
        assertEquals("a picture", Condition.Attachment.FIELD_LABEL)
        assertEquals("a picture in the message", Condition.Attachment.SELECT_LABEL)
        assertEquals("is present", AttachmentOp.HAS_PHOTO.label)
        assertEquals("A condition this version can't read", Condition.Unsupported.ROW_LABEL)
    }
}
