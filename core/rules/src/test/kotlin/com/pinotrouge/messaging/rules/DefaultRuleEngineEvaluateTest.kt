package com.pinotrouge.messaging.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class DefaultRuleEngineEvaluateTest {

    private val engine = DefaultRuleEngine()
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2024-06-15T12:00:00Z")

    private fun msg(
        sender: String = "18445550192",
        body: String = "hello",
        at: Instant = now,
    ) = IncomingMessage(sender, body, at)

    private fun ctx(
        known: Boolean = false,
        neverFilter: Boolean = true,
        zone: ZoneId = utc,
    ) = EvaluationContext(
        isKnownContact = known,
        neverFilterContacts = neverFilter,
        zone = zone,
    )

    private fun rule(
        id: String = "r",
        name: String = "Test",
        enabled: Boolean = true,
        order: Int = 0,
        match: MatchMode = MatchMode.ALL,
        conditions: List<Condition>,
        actions: Set<Action> = setOf(Action.HOLD),
    ) = Rule(
        id = id,
        name = name,
        enabled = enabled,
        order = order,
        match = match,
        conditions = conditions,
        actions = actions,
    )

    // --- Sender operators ---

    @Test
    fun `NOT_IN_CONTACTS matches unknown sender`() {
        val r = rule(conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)))
        val decision = engine.evaluate(msg(), listOf(r), ctx(known = false))
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `NOT_IN_CONTACTS does not match known contact when short-circuit off`() {
        val r = rule(conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)))
        val decision = engine.evaluate(
            msg(),
            listOf(r),
            ctx(known = true, neverFilter = false),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `IS matches after phone normalisation`() {
        val r = rule(
            conditions = listOf(Condition.Sender(SenderOp.IS, "+1 (844) 555-0192")),
        )
        for (sender in listOf("+1 (844) 555-0192", "18445550192", "844-555-0192")) {
            val decision = engine.evaluate(msg(sender = sender), listOf(r), ctx())
            assertTrue("$sender should match", decision is FilterDecision.Matched)
        }
    }

    @Test
    fun `IS does not match a different number`() {
        val r = rule(
            conditions = listOf(Condition.Sender(SenderOp.IS, "8445550192")),
        )
        val decision = engine.evaluate(msg(sender = "5551234567"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `STARTS_WITH matches area code and plus-1800 prefixes`() {
        val r = rule(
            conditions = listOf(Condition.Sender(SenderOp.STARTS_WITH, "+1800")),
        )
        val decision = engine.evaluate(msg(sender = "18005551212"), listOf(r), ctx())
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `STARTS_WITH does not match unrelated prefix`() {
        val r = rule(
            conditions = listOf(Condition.Sender(SenderOp.STARTS_WITH, "212")),
        )
        val decision = engine.evaluate(msg(sender = "18445550192"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `IS_SHORT_CODE matches 3 to 6 digit bare numbers`() {
        val r = rule(conditions = listOf(Condition.Sender(SenderOp.IS_SHORT_CODE)))
        for (sender in listOf("123", "88022", "123456")) {
            val decision = engine.evaluate(msg(sender = sender), listOf(r), ctx())
            assertTrue("$sender should be short code", decision is FilterDecision.Matched)
        }
    }

    @Test
    fun `IS_SHORT_CODE rejects full numbers and plus-prefixed`() {
        val r = rule(conditions = listOf(Condition.Sender(SenderOp.IS_SHORT_CODE)))
        for (sender in listOf("18445550192", "+1800", "12", "1234567", "88-022")) {
            val decision = engine.evaluate(msg(sender = sender), listOf(r), ctx())
            assertEquals("$sender should not be short code", FilterDecision.Allow, decision)
        }
    }

    // --- Text operators ---

    @Test
    fun `CONTAINS_ANY matches any comma-separated entry case-insensitively`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.CONTAINS_ANY, "sale, % off, coupon, deal"),
            ),
        )
        val decision = engine.evaluate(
            msg(body = "Big SALE this weekend"),
            listOf(r),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `CONTAINS_ANY trims whitespace around list entries`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.CONTAINS_ANY, "  sale ,  deal  "),
            ),
        )
        val decision = engine.evaluate(msg(body = "great deal today"), listOf(r), ctx())
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `CONTAINS_ANY does not match when none of the entries appear`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.CONTAINS_ANY, "sale, coupon, deal"),
            ),
        )
        val decision = engine.evaluate(msg(body = "Your package is arriving"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `DOES_NOT_CONTAIN matches when none of the entries appear`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.DOES_NOT_CONTAIN, "otp, code, verify"),
            ),
        )
        val decision = engine.evaluate(msg(body = "Lunch at noon?"), listOf(r), ctx())
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `DOES_NOT_CONTAIN fails when any entry appears`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.DOES_NOT_CONTAIN, "otp, code, verify"),
            ),
        )
        val decision = engine.evaluate(msg(body = "Your OTP is 1234"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `MATCHES_REGEX is case-insensitive and matches`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(
                    TextOp.MATCHES_REGEX,
                    "(pre-?approved|no credit check|crypto|wallet)",
                ),
            ),
        )
        val decision = engine.evaluate(
            msg(body = "PRE-APPROVED for 5000 dollars - no credit check"),
            listOf(r),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `MATCHES_REGEX does not match unrelated body`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "crypto|wallet"),
            ),
        )
        val decision = engine.evaluate(msg(body = "See you at 5"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `malformed regex pauses the rule instead of treating the condition as false`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "[unterminated"),
            ),
        )
        assertTrue(r.isUnreadable)
        val decision = engine.evaluate(msg(body = "anything"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `lookaround regex pauses the rule so it cannot under-filter`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "(?=pre-approved).+"),
                Condition.Sender(SenderOp.IS_SHORT_CODE),
            ),
            match = MatchMode.ANY,
        )
        assertTrue(r.isUnreadable)
        // ANY would otherwise still match the short-code clause.
        val decision = engine.evaluate(
            msg(sender = "55555", body = "pre-approved"),
            listOf(r),
            ctx(),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `intersection regex pauses ANY so a second clause cannot match`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "[a-z&&[^aeiou]]"),
                Condition.Sender(SenderOp.IS_SHORT_CODE),
            ),
            match = MatchMode.ANY,
        )
        assertTrue(r.isUnreadable)
        assertTrue(r.enabled)
        assertFalse(r.isEffectivelyEnabled)
        val decision = engine.evaluate(
            msg(sender = "55555", body = "bcd"),
            listOf(r),
            ctx(),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `nested class union still matches in ALL and ANY`() {
        val nested = Condition.Text(TextOp.MATCHES_REGEX, "[a-d[m-p]]")
        val allRule = rule(
            match = MatchMode.ALL,
            conditions = listOf(
                nested,
                Condition.Sender(SenderOp.IS_SHORT_CODE),
            ),
        )
        assertFalse(allRule.isUnreadable)
        assertTrue(allRule.isEffectivelyEnabled)
        assertTrue(
            engine.evaluate(msg(sender = "55555", body = "a"), listOf(allRule), ctx())
                is FilterDecision.Matched,
        )
        assertEquals(
            FilterDecision.Allow,
            engine.evaluate(msg(sender = "55555", body = "e"), listOf(allRule), ctx()),
        )

        val anyRule = rule(
            id = "any",
            match = MatchMode.ANY,
            conditions = listOf(
                nested,
                Condition.Sender(SenderOp.IS, "nobody"),
            ),
        )
        assertTrue(
            engine.evaluate(msg(sender = "55555", body = "n"), listOf(anyRule), ctx())
                is FilterDecision.Matched,
        )
    }

    @Test
    fun `dollar regex matches a body that ends with a newline`() {
        val r = rule(
            conditions = listOf(Condition.Text(TextOp.MATCHES_REGEX, "a$")),
        )
        assertFalse(r.isUnreadable)
        assertTrue(
            engine.evaluate(msg(body = "a\n"), listOf(r), ctx()) is FilterDecision.Matched,
        )
    }

    @Test
    fun `oversized regex pauses the rule without throwing`() {
        val oversized = "a".repeat(257)
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, oversized),
            ),
        )
        assertTrue(r.isUnreadable)
        val decision = engine.evaluate(msg(body = "a".repeat(300)), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `nested-quantifier pattern finishes quickly and does not match`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "(a+)+b"),
            ),
        )
        assertFalse(r.isUnreadable)
        val body = "a".repeat(100)
        val started = System.nanoTime()
        val decision = engine.evaluate(msg(body = body), listOf(r), ctx())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(FilterDecision.Allow, decision)
        assertTrue("nested quantifiers took ${elapsedMs}ms", elapsedMs < 250L)
    }

    @Test
    fun `regex at the pattern length cap still matches`() {
        val pattern = "promo" + "x".repeat(251)
        assertEquals(256, pattern.length)
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, pattern),
            ),
        )
        val decision = engine.evaluate(msg(body = pattern), listOf(r), ctx())
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `regex does not search past the body cap`() {
        val body = "a".repeat(8_192) + "secret-token"
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.MATCHES_REGEX, "secret-token"),
            ),
        )
        val decision = engine.evaluate(msg(body = body), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    // --- Link operators ---

    @Test
    fun `PRESENT matches scheme-less bare host from seed spam`() {
        val r = rule(conditions = listOf(Condition.Link(LinkOp.PRESENT)))
        val decision = engine.evaluate(
            msg(body = "USPS: redeliver at usps-redelivery-status.co/9182"),
            listOf(r),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `PRESENT matches https URL`() {
        val r = rule(conditions = listOf(Condition.Link(LinkOp.PRESENT)))
        val decision = engine.evaluate(
            msg(body = "Click https://example.com/offer now"),
            listOf(r),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `PRESENT does not match plain text without a host`() {
        val r = rule(conditions = listOf(Condition.Link(LinkOp.PRESENT)))
        val decision = engine.evaluate(msg(body = "No link here at all"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `IS_SHORTENED matches known shortener host`() {
        val r = rule(conditions = listOf(Condition.Link(LinkOp.IS_SHORTENED)))
        val decision = engine.evaluate(
            msg(body = "See bit.ly/abc123 for details"),
            listOf(r),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    /**
     * Retargeted from `ab.cd` when the shortener heuristic was tightened in
     * fix/sender-and-link-matching. The old rule — any host ≤ 6 chars with a
     * 2-letter TLD — swept up `delta.io` and `apple.co`. The heuristic now
     * requires a TLD that shorteners actually use, so an unknown host like
     * `ab.cd` is deliberately no longer a shortener; `nz.gd` still is.
     */
    @Test
    fun `IS_SHORTENED matches short host on a shortener TLD`() {
        val r = rule(conditions = listOf(Condition.Link(LinkOp.IS_SHORTENED)))
        val decision = engine.evaluate(
            msg(body = "Go to nz.gd/x now"),
            listOf(r),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `IS_SHORTENED does not match short host on a non-shortener TLD`() {
        val r = rule(conditions = listOf(Condition.Link(LinkOp.IS_SHORTENED)))
        val decision = engine.evaluate(
            msg(body = "Go to ab.cd/x now"),
            listOf(r),
            ctx(),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `IS_SHORTENED does not match ordinary long host`() {
        val r = rule(conditions = listOf(Condition.Link(LinkOp.IS_SHORTENED)))
        val decision = engine.evaluate(
            msg(body = "Visit https://example.com/path"),
            listOf(r),
            ctx(),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `ON_DOMAIN matches exact host and subdomain`() {
        val r = rule(
            conditions = listOf(Condition.Link(LinkOp.ON_DOMAIN, "example.com")),
        )
        val exact = engine.evaluate(
            msg(body = "https://example.com/a"),
            listOf(r),
            ctx(),
        )
        val sub = engine.evaluate(
            msg(body = "https://mail.example.com/a"),
            listOf(r),
            ctx(),
        )
        assertTrue(exact is FilterDecision.Matched)
        assertTrue(sub is FilterDecision.Matched)
    }

    @Test
    fun `ON_DOMAIN does not match unrelated domain`() {
        val r = rule(
            conditions = listOf(Condition.Link(LinkOp.ON_DOMAIN, "example.com")),
        )
        val decision = engine.evaluate(
            msg(body = "https://example.org/a"),
            listOf(r),
            ctx(),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    // --- Time operators ---

    @Test
    fun `BETWEEN wrapping midnight includes times before and after midnight`() {
        val r = rule(
            conditions = listOf(Condition.Time(TimeOp.BETWEEN, "22:00 – 07:00")),
        )
        // 23:30 UTC
        val beforeMidnight = Instant.parse("2024-06-15T23:30:00Z")
        // 02:00 UTC
        val afterMidnight = Instant.parse("2024-06-16T02:00:00Z")
        // noon UTC
        val noon = Instant.parse("2024-06-15T12:00:00Z")

        assertTrue(
            engine.evaluate(msg(at = beforeMidnight), listOf(r), ctx()) is FilterDecision.Matched,
        )
        assertTrue(
            engine.evaluate(msg(at = afterMidnight), listOf(r), ctx()) is FilterDecision.Matched,
        )
        assertEquals(
            FilterDecision.Allow,
            engine.evaluate(msg(at = noon), listOf(r), ctx()),
        )
    }

    @Test
    fun `BETWEEN accepts hyphen and em dash separators`() {
        val rHyphen = rule(
            id = "h",
            conditions = listOf(Condition.Time(TimeOp.BETWEEN, "22:00-07:00")),
        )
        val rEm = rule(
            id = "e",
            conditions = listOf(Condition.Time(TimeOp.BETWEEN, "22:00 — 07:00")),
        )
        val late = Instant.parse("2024-06-15T23:00:00Z")
        assertTrue(engine.evaluate(msg(at = late), listOf(rHyphen), ctx()) is FilterDecision.Matched)
        assertTrue(engine.evaluate(msg(at = late), listOf(rEm), ctx()) is FilterDecision.Matched)
    }

    @Test
    fun `BETWEEN non-wrapping daytime window`() {
        val r = rule(
            conditions = listOf(Condition.Time(TimeOp.BETWEEN, "09:00 – 17:00")),
        )
        val midday = Instant.parse("2024-06-15T12:00:00Z")
        val evening = Instant.parse("2024-06-15T20:00:00Z")
        assertTrue(engine.evaluate(msg(at = midday), listOf(r), ctx()) is FilterDecision.Matched)
        assertEquals(FilterDecision.Allow, engine.evaluate(msg(at = evening), listOf(r), ctx()))
    }

    @Test
    fun `ON_WEEKEND honours context zone not JVM default`() {
        // Saturday 2024-06-15 10:00 in America/Los_Angeles (PDT, UTC-7)
        // = 2024-06-15 17:00 UTC. In UTC+14 that same instant is already Sunday.
        val instant = LocalDateTime.of(2024, 6, 15, 10, 0)
            .atZone(ZoneId.of("America/Los_Angeles"))
            .toInstant()

        val r = rule(conditions = listOf(Condition.Time(TimeOp.ON_WEEKEND)))

        val la = engine.evaluate(
            msg(at = instant),
            listOf(r),
            ctx(zone = ZoneId.of("America/Los_Angeles")),
        )
        assertTrue("Saturday in LA should match", la is FilterDecision.Matched)

        // Thursday 2024-06-13 in LA
        val thursday = LocalDateTime.of(2024, 6, 13, 10, 0)
            .atZone(ZoneId.of("America/Los_Angeles"))
            .toInstant()
        val weekday = engine.evaluate(
            msg(at = thursday),
            listOf(r),
            ctx(zone = ZoneId.of("America/Los_Angeles")),
        )
        assertEquals(FilterDecision.Allow, weekday)
    }

    // --- Evaluation semantics ---

    @Test
    fun `first match wins and stops`() {
        val first = rule(
            id = "first",
            name = "First",
            order = 0,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "offer")),
            actions = setOf(Action.HOLD),
        )
        val second = rule(
            id = "second",
            name = "Second",
            order = 1,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "offer")),
            actions = setOf(Action.BLOCK),
        )
        val decision = engine.evaluate(
            msg(body = "Special offer inside"),
            listOf(second, first), // deliberately out of list order
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
        assertEquals("first", (decision as FilterDecision.Matched).rule.id)
        assertEquals("Filter: First", decision.reason)
    }

    @Test
    fun `disabled rules are skipped`() {
        val disabled = rule(
            id = "off",
            name = "Off",
            enabled = false,
            order = 0,
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "sale")),
        )
        val decision = engine.evaluate(msg(body = "big sale"), listOf(disabled), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `contacts short-circuit beats a rule that would otherwise match`() {
        val r = rule(
            name = "Catch everyone",
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "hello")),
        )
        val decision = engine.evaluate(
            msg(body = "hello from Mom"),
            listOf(r),
            ctx(known = true, neverFilter = true),
        )
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `contacts short-circuit can be turned off`() {
        val r = rule(
            name = "Catch everyone",
            conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "hello")),
        )
        val decision = engine.evaluate(
            msg(body = "hello from Mom"),
            listOf(r),
            ctx(known = true, neverFilter = false),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `ALL requires every condition`() {
        val r = rule(
            match = MatchMode.ALL,
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Link(LinkOp.PRESENT),
            ),
        )
        val both = engine.evaluate(
            msg(body = "see https://evil.example/x"),
            listOf(r),
            ctx(known = false),
        )
        val onlyLink = engine.evaluate(
            msg(body = "see https://evil.example/x"),
            listOf(r),
            ctx(known = true, neverFilter = false),
        )
        assertTrue(both is FilterDecision.Matched)
        assertEquals(FilterDecision.Allow, onlyLink)
    }

    @Test
    fun `ANY matches when one condition holds`() {
        val r = rule(
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Text(TextOp.CONTAINS_ANY, "sale"),
                Condition.Text(TextOp.CONTAINS_ANY, "crypto"),
            ),
        )
        val decision = engine.evaluate(msg(body = "buy crypto now"), listOf(r), ctx())
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `rule with zero conditions never matches`() {
        val r = rule(conditions = emptyList())
        val decision = engine.evaluate(msg(body = "anything"), listOf(r), ctx())
        assertEquals(FilterDecision.Allow, decision)
    }

    @Test
    fun `matched reason uses filter name`() {
        val r = SeedRules.loanAndCrypto
        val decision = engine.evaluate(
            msg(body = "PRE-APPROVED for 5000 dollars - no credit check"),
            listOf(r),
            ctx(),
        )
        assertTrue(decision is FilterDecision.Matched)
        assertEquals("Filter: Loan and crypto offers", (decision as FilterDecision.Matched).reason)
        assertSame(r, decision.rule)
    }

    @Test
    fun `seed links rule matches unknown sender with bare host URL`() {
        val decision = engine.evaluate(
            msg(
                sender = "18445550192",
                body = "USPS: redeliver at usps-redelivery-status.co/9182",
            ),
            listOf(SeedRules.linksFromUnknown),
            ctx(known = false),
        )
        assertTrue(decision is FilterDecision.Matched)
    }

    @Test
    fun `DOES_NOT_CONTAIN is false on a media-bearing blank body`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.DOES_NOT_CONTAIN, "delivery"),
            ),
        )
        val picture = IncomingMessage(
            sender = "18445550192",
            body = "",
            receivedAt = now,
            hasPhoto = true,
            hasAnyPart = true,
        )
        assertEquals(FilterDecision.Allow, engine.evaluate(picture, listOf(r), ctx()))
    }

    @Test
    fun `DOES_NOT_CONTAIN still matches a genuinely empty SMS`() {
        val r = rule(
            conditions = listOf(
                Condition.Text(TextOp.DOES_NOT_CONTAIN, "delivery"),
            ),
        )
        val emptySms = IncomingMessage(
            sender = "18445550192",
            body = "",
            receivedAt = now,
        )
        assertTrue(engine.evaluate(emptySms, listOf(r), ctx()) is FilterDecision.Matched)
    }

    @Test
    fun `HAS_PHOTO is true only when hasPhoto is set`() {
        val r = rule(conditions = listOf(Condition.Attachment(AttachmentOp.HAS_PHOTO)))
        assertTrue(
            engine.evaluate(
                IncomingMessage("1", "hi", now, hasPhoto = true, hasAnyPart = true),
                listOf(r),
                ctx(),
            ) is FilterDecision.Matched,
        )
        assertEquals(
            FilterDecision.Allow,
            engine.evaluate(
                IncomingMessage("1", "hi", now, hasPhoto = false, hasAnyPart = true),
                listOf(r),
                ctx(),
            ),
        )
        assertEquals(
            FilterDecision.Allow,
            engine.evaluate(msg(body = "hi"), listOf(r), ctx()),
        )
    }

    @Test
    fun `C1 mixed ALL unknown-photo line does not hold ordinary text`() {
        val r = rule(
            match = MatchMode.ALL,
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Unsupported("attachment|HAS_PHOTO|"),
            ),
        )
        assertEquals(FilterDecision.Allow, engine.evaluate(msg(body = "lunch?"), listOf(r), ctx()))
    }

    @Test
    fun `C1 mixed ANY unknown-photo line does not hold ordinary text`() {
        val r = rule(
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Unsupported("attachment|HAS_PHOTO|"),
            ),
        )
        assertEquals(FilterDecision.Allow, engine.evaluate(msg(body = "lunch?"), listOf(r), ctx()))
    }

    @Test
    fun `unknown action name skips the rule`() {
        val r = rule(
            conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
            actions = linkedSetOf(Action.HOLD, Action.parse("FLY")),
        )
        assertEquals(FilterDecision.Allow, engine.evaluate(msg(body = "lunch?"), listOf(r), ctx()))
    }

    @Test
    fun `unknown match mode skips the rule`() {
        val r = rule(
            match = MatchMode.parse("XOR"),
            conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
        )
        assertEquals(FilterDecision.Allow, engine.evaluate(msg(body = "lunch?"), listOf(r), ctx()))
    }
}
