package com.pinotrouge.messaging.ui.sweep

import com.pinotrouge.messaging.data.repo.SweepCandidate
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.EvaluationContext
import com.pinotrouge.messaging.rules.FilterDecision
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.RuleEngine

/**
 * Pure scan logic for *Run filters on my inbox*.
 *
 * Matching uses [RuleEngine.evaluate] — same order, first-match-wins, and
 * contacts short-circuit as arrival. One message is attributed to at most one
 * rule (never sum per-rule backtests).
 */
object InboxSweep {

    data class Match(
        val providerMessageId: Long,
        val sender: String,
        val body: String,
        val receivedAtMillis: Long,
        val ruleId: String,
        val ruleName: String,
        val reason: String,
        val deleteAfterDays: Int,
        val isKnownContact: Boolean,
        /** Provider read flag at scan time — restored on undo for SWEEP holds. */
        val wasRead: Boolean,
    )

    data class RuleGroup(
        val ruleId: String,
        val ruleName: String,
        val count: Int,
    )

    data class Result(
        val scanned: Int,
        val matches: List<Match>,
        /** Matches whose sender is a known contact (warning surface). */
        val caughtFromContacts: Int,
        val groups: List<RuleGroup>,
    ) {
        val wouldHold: Int get() = matches.size
    }

    fun scan(
        candidates: List<SweepCandidate>,
        rules: List<Rule>,
        neverFilterContacts: Boolean,
        engine: RuleEngine,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Result {
        val enabled = rules.filter { it.enabled }.sortedBy { it.order }
        val matches = ArrayList<Match>()
        var fromContacts = 0

        for (candidate in candidates) {
            val context = EvaluationContext(
                isKnownContact = candidate.isKnownContact,
                neverFilterContacts = neverFilterContacts,
                zone = zone,
            )
            when (
                val decision = engine.evaluate(candidate.message, enabled, context)
            ) {
                FilterDecision.Allow -> Unit
                is FilterDecision.Matched -> {
                    if (Action.HOLD !in decision.rule.actions) continue
                    matches += Match(
                        providerMessageId = candidate.providerMessageId,
                        sender = candidate.message.sender,
                        body = candidate.message.body,
                        receivedAtMillis = candidate.message.receivedAt.toEpochMilli(),
                        ruleId = decision.rule.id,
                        ruleName = decision.rule.name,
                        reason = decision.reason,
                        deleteAfterDays = decision.rule.deleteAfterDays,
                        isKnownContact = candidate.isKnownContact,
                        wasRead = candidate.wasRead,
                    )
                    if (candidate.isKnownContact) fromContacts++
                }
            }
        }

        val groups = matches
            .groupBy { it.ruleId }
            .map { (ruleId, list) ->
                RuleGroup(
                    ruleId = ruleId,
                    ruleName = list.first().ruleName,
                    count = list.size,
                )
            }
            // Stable order: first appearance in evaluation order among matches
            .sortedBy { g ->
                enabled.indexOfFirst { it.id == g.ruleId }.takeIf { it >= 0 } ?: Int.MAX_VALUE
            }

        return Result(
            scanned = candidates.size,
            matches = matches,
            caughtFromContacts = fromContacts,
            groups = groups,
        )
    }
}
