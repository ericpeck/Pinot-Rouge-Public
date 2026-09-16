package com.pinotrouge.messaging.sms

/**
 * Normalise a sender for blocklist / phone matching keys.
 * Keep this as the only copy used by the SMS pipeline so `block()` and
 * `isBlocked()` cannot drift onto different keys.
 *
 * (QuarantineRepository still has an identical private helper for its own
 * DAO access; do not invent a third variant.)
 */
object SenderIds {
    fun normalize(sender: String): String {
        val digits = sender.filter { it.isDigit() }
        return if (digits.length >= 7) digits.takeLast(10) else sender.trim().lowercase()
    }
}
