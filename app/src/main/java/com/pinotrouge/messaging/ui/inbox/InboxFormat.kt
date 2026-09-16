package com.pinotrouge.messaging.ui.inbox

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure formatting helpers for the inbox list (unit-tested).
 */
object InboxFormat {

    /**
     * Avatar initials from a display label.
     *
     * - Named contact → first letter(s) of words
     * - Alphanumeric sender ID (`LOANFAST`) → first one or two letters
     * - Short code or ordinary phone number → `#` (not trailing digits)
     */
    fun initials(displayName: String, category: ThreadCategory): String {
        if (category == ThreadCategory.Codes) return "#"
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return "#"

        val alphaParts = trimmed
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetter() } }
            .filter { it.isNotEmpty() }

        if (alphaParts.isNotEmpty()) {
            return if (alphaParts.size == 1) {
                val word = alphaParts[0]
                // ALL-CAPS brand IDs (LOANFAST, CARTLY) → two letters; names → one.
                if (word.length >= 2 && word.all { it.isUpperCase() }) {
                    word.take(2).uppercase(Locale.US)
                } else {
                    word.take(1).uppercase(Locale.US)
                }
            } else {
                (alphaParts[0].take(1) + alphaParts[1].take(1)).uppercase(Locale.US)
            }
        }

        // Pure digits / phone formatting / short codes — deliberate hash, not
        // last-two-digits (which read as a rendering fault).
        return "#"
    }

    /**
     * Relative time label matching the prototype: "9:14", "Yesterday", "Mon".
     */
    fun formatTime(dateMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val msgCal = Calendar.getInstance().apply { timeInMillis = dateMillis }

        val startOfToday = (nowCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMsgDay = (msgCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dayDiff = TimeUnit.MILLISECONDS.toDays(
            startOfToday.timeInMillis - startOfMsgDay.timeInMillis,
        )

        return when {
            dayDiff <= 0L -> {
                val hour = msgCal.get(Calendar.HOUR_OF_DAY)
                val minute = msgCal.get(Calendar.MINUTE)
                val h12 = when {
                    hour == 0 -> 12
                    hour > 12 -> hour - 12
                    else -> hour
                }
                String.format(Locale.US, "%d:%02d", h12, minute)
            }
            dayDiff == 1L -> "Yesterday"
            dayDiff < 7L -> {
                val day = msgCal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)
                day ?: String.format(Locale.US, "%d/%d", msgCal.get(Calendar.MONTH) + 1, msgCal.get(Calendar.DAY_OF_MONTH))
            }
            else -> String.format(
                Locale.US,
                "%d/%d",
                msgCal.get(Calendar.MONTH) + 1,
                msgCal.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}
