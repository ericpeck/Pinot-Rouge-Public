package com.pinotrouge.messaging.rules

/**
 * The four starter rules from the prototype (PinotPhone.dc.html lines 511–514).
 * Shared by rendering and evaluation tests so the strings stay in one place.
 */
object SeedRules {

    val linksFromUnknown = Rule(
        id = "r1",
        name = "Links from people I do not know",
        enabled = true,
        order = 0,
        match = MatchMode.ALL,
        conditions = listOf(
            Condition.Sender(SenderOp.NOT_IN_CONTACTS),
            Condition.Link(LinkOp.PRESENT),
        ),
        actions = setOf(Action.HOLD, Action.SILENCE),
        deleteAfterDays = 30,
    )

    val promotionsAndSales = Rule(
        id = "r2",
        name = "Promotions and sales",
        enabled = true,
        order = 1,
        match = MatchMode.ANY,
        conditions = listOf(
            Condition.Text(TextOp.CONTAINS_ANY, "sale, % off, coupon, deal"),
        ),
        actions = linkedSetOf(Action.HOLD, Action.SILENCE, Action.DELETE),
        deleteAfterDays = 14,
    )

    val loanAndCrypto = Rule(
        id = "r3",
        name = "Loan and crypto offers",
        enabled = true,
        order = 2,
        match = MatchMode.ANY,
        conditions = listOf(
            Condition.Text(
                TextOp.MATCHES_REGEX,
                "(pre-?approved|no credit check|crypto|wallet)",
            ),
        ),
        actions = linkedSetOf(Action.HOLD, Action.BLOCK),
        deleteAfterDays = 30,
    )

    val quietAfterTen = Rule(
        id = "r4",
        name = "Quiet after 10 pm",
        enabled = false,
        order = 3,
        match = MatchMode.ALL,
        conditions = listOf(
            Condition.Time(TimeOp.BETWEEN, "22:00 – 07:00"),
        ),
        actions = setOf(Action.SILENCE),
        deleteAfterDays = 30,
    )

    val all: List<Rule> = listOf(
        linksFromUnknown,
        promotionsAndSales,
        loanAndCrypto,
        quietAfterTen,
    )
}
