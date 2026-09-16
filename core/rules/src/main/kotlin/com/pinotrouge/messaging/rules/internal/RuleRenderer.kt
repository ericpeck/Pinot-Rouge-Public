package com.pinotrouge.messaging.rules.internal

import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule

/**
 * Renders a [Rule] as English.
 *
 * [summarize] and [plainWords] intentionally use different action wording —
 * both sets are pinned by tests and by the filter-rule spec.
 *
 * Port of the prototype's `ruleSummary()` / `condText()` / `plainWords`.
 */
internal object RuleRenderer {

    fun summarize(rule: Rule): String {
        val conditions = rule.conditions.joinToString(joinWord(rule.match)) { condText(it) }
        val actions = rule.actions.joinToString(", ") { summaryLabel(it) }
            .ifEmpty { "do nothing yet" }
        return "If $conditions → $actions"
    }

    fun plainWords(rule: Rule): String {
        val conditions = rule.conditions.joinToString(joinWord(rule.match)) { condText(it) }
        val actions = if (rule.actions.isEmpty()) {
            "nothing happens yet — pick an action"
        } else {
            rule.actions.joinToString(", ") { plainWordsLabel(it, rule.deleteAfterDays) }
        }
        return "When $conditions, $actions."
    }

    /**
     * Unknown match modes take the stricter [MatchMode.ALL] reading rather
     * than falling through to "or". The rule is already inert; this only
     * stops the Filters sentence claiming a semantics we do not know.
     */
    private fun joinWord(match: MatchMode): String =
        if (match == MatchMode.ANY) " or " else " and "

    /**
     * `"${field} ${op}"`, plus curly-quoted value when the operator needs one
     * and the value is non-empty.
     */
    fun condText(condition: Condition): String {
        if (condition is Condition.Unsupported) return condition.fieldLabel
        val base = "${condition.fieldLabel} ${condition.opLabel}"
        return if (condition.needsValue && condition.value.isNotEmpty()) {
            // U+201C / U+201D curly quotes, as in the prototype.
            "$base \u201C${condition.value}\u201D"
        } else {
            base
        }
    }

    private fun summaryLabel(action: Action): String = when (action) {
        Action.HOLD -> "hold it in Filtered"
        Action.SILENCE -> "stay silent"
        Action.DELETE -> "auto-delete"
        Action.BLOCK -> "block the sender"
        Action.REPLY -> "auto-reply"
        Action.READ -> "mark it read"
        else -> "an action this version can't read"
    }

    private fun plainWordsLabel(action: Action, deleteAfterDays: Int): String = when (action) {
        Action.HOLD -> "hold it in Filtered"
        Action.SILENCE -> "do not make a sound"
        Action.DELETE -> "delete it after $deleteAfterDays days"
        Action.BLOCK -> "block the sender"
        Action.REPLY -> "send an automatic reply"
        Action.READ -> "mark it read"
        else -> "an action this version can't read"
    }
}
