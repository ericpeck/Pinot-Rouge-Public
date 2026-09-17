package com.pinotrouge.messaging.rules

/**
 * A single user-authored filter.
 *
 * Rules are evaluated in [order], ascending. The **first** rule that matches
 * wins and evaluation stops — the Filters screen tells the user exactly this
 * ("Filters run in order, top to bottom. A message stops at the first one that
 * matches."), so the implementation must not drift from it.
 *
 * @param order position in the user's list; lower runs first.
 * @param actions what happens when this rule matches. A rule with no actions
 *   is legal (the builder lets you save one) and simply does nothing.
 * @param deleteAfterDays retention window for [Action.HOLD]. What happens at
 *   the end of it depends on whether [Action.DELETE] is also set — see the
 *   quarantine table in README.md (What happens to filtered messages).
 * @param autoReplyText body sent when [Action.REPLY] is set; null otherwise.
 */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val order: Int,
    val match: MatchMode = MatchMode.ALL,
    val conditions: List<Condition>,
    val actions: Set<Action> = emptySet(),
    val deleteAfterDays: Int = DEFAULT_RETENTION_DAYS,
    val autoReplyText: String? = null,
) {
    companion object {
        const val DEFAULT_RETENTION_DAYS = 30

        /** The builder's stepper clamps to this range (prototype `fewerDays`/`moreDays`). */
        val RETENTION_RANGE = 1..90
    }
}

/** True when this build must not evaluate the rule (unknown lines or unrunnable regex). */
val Rule.isUnreadable: Boolean
    get() = !match.isKnown ||
        actions.any { !it.isKnown } ||
        conditions.any { it is Condition.Unsupported || it.hasUnrunnableRegex }

/** A MATCHES_REGEX condition this engine will not execute. */
val Condition.hasUnrunnableRegex: Boolean
    get() = this is Condition.Text &&
        op == TextOp.MATCHES_REGEX &&
        RegexPatterns.validate(value) !is RegexPatterns.Validity.Valid

/** Whether every condition must hold, or just one. Unknown names stay inert. */
data class MatchMode(val name: String) {
    val isKnown: Boolean get() = this == ALL || this == ANY

    companion object {
        val ALL = MatchMode("ALL")
        val ANY = MatchMode("ANY")

        fun parse(raw: String): MatchMode = when (raw) {
            ALL.name -> ALL
            ANY.name -> ANY
            else -> MatchMode(raw)
        }
    }
}

/**
 * What a matching rule does. Multiple actions may apply at once — the builder
 * presents these as multi-select chips.
 *
 * Unknown names (a newer build's action) round-trip as themselves so a Filters
 * toggle cannot drop them, and [Rule.isUnreadable] skips the rule.
 */
data class Action(val name: String) {
    val isKnown: Boolean get() = name in KNOWN_NAMES

    companion object {
        /** Divert to the Filtered screen instead of the inbox. */
        val HOLD = Action("HOLD")

        /** Deliver, but post no notification and make no sound. */
        val SILENCE = Action("SILENCE")

        /** At the end of the retention window, delete permanently rather than filing. */
        val DELETE = Action("DELETE")

        /** Add the sender to the blocklist. */
        val BLOCK = Action("BLOCK")

        /** Send [Rule.autoReplyText] back to the sender. */
        val REPLY = Action("REPLY")

        /** Mark as already read on arrival. */
        val READ = Action("READ")

        private val KNOWN_NAMES = setOf(
            HOLD.name,
            SILENCE.name,
            DELETE.name,
            BLOCK.name,
            REPLY.name,
            READ.name,
        )

        fun parse(token: String): Action = when (token) {
            HOLD.name -> HOLD
            SILENCE.name -> SILENCE
            DELETE.name -> DELETE
            BLOCK.name -> BLOCK
            REPLY.name -> REPLY
            READ.name -> READ
            else -> Action(token)
        }
    }
}
