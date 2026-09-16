package com.pinotrouge.messaging.rules

import com.pinotrouge.messaging.rules.internal.PhoneNumbers
import com.pinotrouge.messaging.rules.internal.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Regression tests for the three bugs in [[fix-sender-and-link-matching]]:
 * alphanumeric senders, prose-as-link false positives, and loose shortener heuristic.
 */
class SenderAndLinkMatchingFixTest {

    private val engine = DefaultRuleEngine()
    private val now = Instant.parse("2024-06-15T12:00:00Z")

    private fun msg(sender: String = "18445550192", body: String = "hello") =
        IncomingMessage(sender, body, now)

    private fun ctx(known: Boolean = false) = EvaluationContext(
        isKnownContact = known,
        neverFilterContacts = true,
        zone = ZoneOffset.UTC,
    )

    private fun senderRule(op: SenderOp, value: String = "") = Rule(
        id = "s",
        name = "Sender",
        order = 0,
        conditions = listOf(Condition.Sender(op, value)),
        actions = setOf(Action.HOLD),
    )

    private fun linkPresentRule() = Rule(
        id = "l",
        name = "Link",
        order = 0,
        conditions = listOf(Condition.Link(LinkOp.PRESENT)),
        actions = setOf(Action.HOLD),
    )

    // --- Bug 1: alphanumeric senders ---

    @Test
    fun `IS matches alphanumeric brand senders`() {
        assertTrue(PhoneNumbers.sameSender("LOANFAST", "LOANFAST"))
        assertTrue(PhoneNumbers.sameSender("CARTLY", "CARTLY"))
        assertTrue(PhoneNumbers.sameSender("Mom", "Mom"))
        assertTrue(PhoneNumbers.sameSender("mom", "MOM"))
        assertTrue(PhoneNumbers.sameSender("  Dev Patel  ", "dev patel"))

        for (pair in listOf(
            "LOANFAST" to "LOANFAST",
            "CARTLY" to "CARTLY",
            "Mom" to "Mom",
        )) {
            val decision = engine.evaluate(
                msg(sender = pair.first),
                listOf(senderRule(SenderOp.IS, pair.second)),
                ctx(),
            )
            assertTrue("${pair.first} IS ${pair.second}", decision is FilterDecision.Matched)
        }
    }

    @Test
    fun `STARTS_WITH matches alphanumeric prefixes`() {
        assertTrue(PhoneNumbers.startsWith("LOANFAST", "LOAN"))
        assertTrue(PhoneNumbers.startsWith("loanfast", "loan"))
        val decision = engine.evaluate(
            msg(sender = "LOANFAST"),
            listOf(senderRule(SenderOp.STARTS_WITH, "LOAN")),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `IS and STARTS_WITH still match pure phone numbers`() {
        assertTrue(PhoneNumbers.sameSender("+1 (844) 555-0192", "8445550192"))
        assertTrue(PhoneNumbers.sameSender("18445550192", "844-555-0192"))
        assertTrue(PhoneNumbers.sameSender("21212", "21212"))
        assertTrue(PhoneNumbers.startsWith("18005551212", "+1800"))

        val decision = engine.evaluate(
            msg(sender = "844-555-0192"),
            listOf(senderRule(SenderOp.IS, "+1 (844) 555-0192")),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `mixed alphanumeric senders do not collide on last digits`() {
        // Would both last-10 to "4417" if we digit-stripped mixed input.
        assertFalse(PhoneNumbers.sameSender("Verify-4417", "Bank-4417"))
        assertFalse(PhoneNumbers.sameSender("Verify-4417", "4417"))

        val decision = engine.evaluate(
            msg(sender = "Verify-4417"),
            listOf(senderRule(SenderOp.IS, "Bank-4417")),
            ctx(),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `IS does not match different alphanumeric names`() {
        assertFalse(PhoneNumbers.sameSender("Mom", "Dad"))
        val decision = engine.evaluate(
            msg(sender = "Mom"),
            listOf(senderRule(SenderOp.IS, "Dad")),
            ctx(),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    // --- Bug 2: prose must not look like a link ---

    @Test
    fun `prose with missing space after period is not a link`() {
        val prose = listOf(
            "Reply STOP.Msg&data rates may apply",
            "See you at 8.Bring the chairs",
            "Call me.Tomorrow works too",
            "Sale ends today. Shop now",
            "Your total is \$84.20 at Rowan Grocery.",
        )
        for (body in prose) {
            assertTrue(
                "should not extract a link from: $body",
                Urls.extract(body).isEmpty(),
            )
            val decision = engine.evaluate(msg(body = body), listOf(linkPresentRule()), ctx())
            assertEquals("PRESENT should not fire for: $body", FilterDecision.Allow, decision)
        }
    }

    @Test
    fun `seed spam bare host still detected as a link`() {
        val body = "Confirm at usps-redelivery-status.co/9182"
        assertTrue(Urls.anyPresent(body))
        assertEquals("usps-redelivery-status.co", Urls.extract(body).single().host)

        val decision = engine.evaluate(msg(body = body), listOf(linkPresentRule()), ctx())
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `scheme and www still accept uncommon TLDs`() {
        assertTrue(Urls.anyPresent("Click https://evil.invalid/phish"))
        assertTrue(Urls.anyPresent("Visit www.weird.zzzz/path"))
    }

    // --- Bug 3: shortener heuristic tightened ---

    @Test
    fun `known shorteners still match`() {
        assertTrue(Urls.isShortenedHost("bit.ly"))
        assertTrue(Urls.isShortenedHost("t.co"))
        assertTrue(Urls.isShortenedHost("goo.gl"))
        assertTrue(Urls.anyShortened("See bit.ly/abc123 for details"))
        assertTrue(Urls.anyShortened("Open https://t.co/xyz"))
    }

    @Test
    fun `real company short hosts are not shorteners`() {
        assertFalse(Urls.isShortenedHost("delta.io"))
        assertFalse(Urls.isShortenedHost("apple.co"))
        assertFalse(Urls.anyShortened("Book at delta.io/flights"))
        assertFalse(Urls.anyShortened("Shop apple.co/iphone"))
    }
}
