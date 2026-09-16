package com.pinotrouge.messaging.ui.inbox

/**
 * Derived inbox categories for chip filtering.
 *
 * Not in the data model — computed per thread from address, contact lookup,
 * and message body. See Log for heuristic details.
 */
enum class ThreadCategory {
    People,
    Money,
    Travel,
    Codes,

    /** Does not match any named chip other than All. */
    Other,
}

/**
 * Chip labels as shown in the prototype (variant a).
 */
enum class InboxChip(val label: String) {
    All("All"),
    People("People"),
    Money("Money"),
    Travel("Travel"),
    Codes("Codes"),
    ;

    fun matches(category: ThreadCategory): Boolean = when (this) {
        All -> true
        People -> category == ThreadCategory.People
        Money -> category == ThreadCategory.Money
        Travel -> category == ThreadCategory.Travel
        Codes -> category == ThreadCategory.Codes
    }
}

/**
 * Classify a thread for chip filtering and avatar colour.
 *
 * Priority:
 * 1. Codes — short-code sender (3–6 digits, no `+`) and body contains a
 *    verification-style code.
 * 2. People — known contact.
 * 3. Money — body/snippet keyword heuristic (bank, card, payment, $ amounts…).
 * 4. Travel — body/snippet keyword heuristic (flight, gate, boarding…).
 * 5. Other — no chip match other than All.
 */
object ThreadCategoryClassifier {

    fun classify(
        address: String?,
        isKnownContact: Boolean,
        bodyOrSnippet: String?,
    ): ThreadCategory {
        val addressSafe = address.orEmpty()
        val text = bodyOrSnippet.orEmpty()
        val code = OtpCodeExtractor.extract(text)

        if (isShortCode(addressSafe) && code != null) {
            return ThreadCategory.Codes
        }
        if (isKnownContact) {
            return ThreadCategory.People
        }
        if (matchesMoney(text)) {
            return ThreadCategory.Money
        }
        if (matchesTravel(text)) {
            return ThreadCategory.Travel
        }
        return ThreadCategory.Other
    }

    /**
     * Mirrors `PhoneNumbers.isShortCode` in `:core:rules` (internal there).
     * 3–6 digit sender, no `+`, all digits.
     */
    fun isShortCode(sender: String): Boolean {
        val trimmed = sender.trim()
        if (trimmed.isEmpty() || trimmed.contains('+')) return false
        if (!trimmed.all { it.isDigit() }) return false
        return trimmed.length in 3..6
    }

    private fun matchesMoney(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (MONEY_KEYWORDS.any { lower.contains(it) }) return true
        // $ amounts like $84.20
        if (DOLLAR_AMOUNT.containsMatchIn(text)) return true
        return false
    }

    private fun matchesTravel(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return TRAVEL_KEYWORDS.any { lower.contains(it) }
    }

    private val DOLLAR_AMOUNT = Regex("""\$\s?\d""")

    private val MONEY_KEYWORDS = listOf(
        "card ending",
        "account",
        "payment",
        "paid",
        "deposit",
        "balance",
        "bank",
        "credit",
        "debit",
        "transaction",
        "charged",
        "invoice",
        "receipt",
        "refund",
        "wire transfer",
        "venmo",
        "paypal",
        "zelle",
    )

    private val TRAVEL_KEYWORDS = listOf(
        "flight",
        "gate ",
        "boarding",
        "depart",
        "arrival",
        "airline",
        "airport",
        "hotel",
        "check-in",
        "check in",
        "itinerary",
        "reservation",
        "booking",
        "baggage",
        "terminal",
        "train",
        "uber",
        "lyft",
        "ride to",
    )
}
