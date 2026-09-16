package com.pinotrouge.messaging.rules

import com.pinotrouge.messaging.rules.internal.ConditionMatcher
import com.pinotrouge.messaging.rules.internal.RuleRenderer

/**
 * Production [RuleEngine]. Pure Kotlin — no Android, no I/O, no clocks.
 *
 * Evaluation order, contacts short-circuit, and first-match-wins behaviour are
 * documented in README.md (Filters you can read).
 */
class DefaultRuleEngine : RuleEngine {

    override fun evaluate(
        message: IncomingMessage,
        rules: List<Rule>,
        context: EvaluationContext,
    ): FilterDecision {
        // "People you know always reach the inbox."
        if (context.neverFilterContacts && context.isKnownContact) {
            return FilterDecision.Allow
        }

        val ordered = rules
            .filter { it.enabled }
            .sortedBy { it.order }

        for (rule in ordered) {
            if (rule.conditions.isEmpty()) continue
            if (rule.isUnreadable) continue
            if (ruleMatches(rule, message, context)) {
                return FilterDecision.Matched(
                    rule = rule,
                    reason = "Filter: ${rule.name}",
                )
            }
        }
        return FilterDecision.Allow
    }

    override fun summarize(rule: Rule): String = RuleRenderer.summarize(rule)

    override fun plainWords(rule: Rule): String = RuleRenderer.plainWords(rule)

    /**
     * Evaluates [rule] alone against historical samples for the builder line.
     *
     * The contacts short-circuit is **not** applied here: a non-zero
     * [BacktestResult.caughtFromContacts] is a warning the user should see
     * ("this would have caught N from your contacts"). Contact status still
     * feeds [SenderOp.NOT_IN_CONTACTS] via each sample's flag.
     */
    override fun backtest(
        rule: Rule,
        samples: List<BacktestSample>,
        context: EvaluationContext,
    ): BacktestResult {
        if (rule.conditions.isEmpty() || rule.isUnreadable) {
            return BacktestResult(sampled = samples.size, caught = 0, caughtFromContacts = 0)
        }

        var caught = 0
        var caughtFromContacts = 0

        for (sample in samples) {
            val sampleContext = context.copy(isKnownContact = sample.isKnownContact)
            if (ruleMatches(rule, sample.message, sampleContext)) {
                caught++
                if (sample.isKnownContact) caughtFromContacts++
            }
        }

        return BacktestResult(
            sampled = samples.size,
            caught = caught,
            caughtFromContacts = caughtFromContacts,
        )
    }

    private fun ruleMatches(
        rule: Rule,
        message: IncomingMessage,
        context: EvaluationContext,
    ): Boolean = when (rule.match) {
        MatchMode.ALL -> rule.conditions.all { ConditionMatcher.matches(it, message, context) }
        MatchMode.ANY -> rule.conditions.any { ConditionMatcher.matches(it, message, context) }
        else -> false
    }
}
