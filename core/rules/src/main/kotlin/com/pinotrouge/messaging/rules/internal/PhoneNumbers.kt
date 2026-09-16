package com.pinotrouge.messaging.rules.internal

/**
 * Phone and sender comparison helpers.
 *
 * Senders are not always numbers: `LOANFAST`, `CARTLY`, `Mom` and short codes
 * all appear. Compare as phone numbers only when **both** sides are purely
 * numeric (last-10 after stripping formatting). Otherwise compare as trimmed,
 * case-insensitive text. Mixed last-10 would make `Verify-4417` and
 * `Bank-4417` collide.
 */
internal object PhoneNumbers {

    fun digitsOnly(raw: String): String = raw.filter { it.isDigit() }

    /** Last 10 digits, or the whole digit string if shorter. */
    fun last10(raw: String): String {
        val digits = digitsOnly(raw)
        return if (digits.length <= 10) digits else digits.takeLast(10)
    }

    /**
     * True when [raw] is a phone number rather than an alphanumeric sender ID.
     * Strips whitespace and common phone punctuation before checking digits.
     */
    fun isPurelyNumeric(raw: String): Boolean {
        val cleaned = raw.filterNot { it.isWhitespace() || it in "+()-." }
        return cleaned.isNotEmpty() && cleaned.all { it.isDigit() }
    }

    fun sameSender(a: String, b: String): Boolean {
        return if (isPurelyNumeric(a) && isPurelyNumeric(b)) {
            val da = last10(a)
            val db = last10(b)
            if (da.isEmpty() || db.isEmpty()) false
            else da == db
        } else {
            a.trim().equals(b.trim(), ignoreCase = true)
        }
    }

    /**
     * Prefix match. Numeric when both sides are purely numeric (area codes,
     * `+1800`); otherwise case-insensitive text prefix on the trimmed strings.
     */
    fun startsWith(sender: String, prefix: String): Boolean {
        return if (isPurelyNumeric(sender) && isPurelyNumeric(prefix)) {
            val senderDigits = digitsOnly(sender)
            val prefixDigits = digitsOnly(prefix)
            if (prefixDigits.isEmpty()) false
            else senderDigits.startsWith(prefixDigits)
        } else {
            sender.trim().startsWith(prefix.trim(), ignoreCase = true)
        }
    }

    /**
     * 3–6 digit sender, no `+`, all digits (short codes like `88022`).
     * The raw form is checked so a full MSISDN never qualifies.
     */
    fun isShortCode(sender: String): Boolean {
        val trimmed = sender.trim()
        if (trimmed.isEmpty() || trimmed.contains('+')) return false
        if (!trimmed.all { it.isDigit() }) return false
        return trimmed.length in 3..6
    }
}
