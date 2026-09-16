package com.pinotrouge.messaging.rules

import java.time.Instant
import java.time.ZoneId

/**
 * A message as it arrives, reduced to what a rule can ask about.
 *
 * Multipart SMS is coalesced into one [body] *before* reaching the engine —
 * a rule matching "no credit check" must still fire when that phrase
 * straddles a segment boundary.
 *
 * [hasPhoto] / [hasAnyPart] default false so existing construction sites
 * keep compiling. They are independent: a future video part must not
 * satisfy [AttachmentOp.HAS_PHOTO].
 */
data class IncomingMessage(
    val sender: String,
    val body: String,
    val receivedAt: Instant,
    val hasPhoto: Boolean = false,
    val hasAnyPart: Boolean = false,
)

/**
 * Everything the engine needs from the outside world, passed in rather than
 * looked up. This is what keeps the module pure: no ContentResolver, no
 * `Instant.now()`, no default time zone. It also makes contact- and
 * time-dependent behaviour trivial to test.
 */
data class EvaluationContext(
    /** Whether [IncomingMessage.sender] resolves to a saved contact. */
    val isKnownContact: Boolean,
    /** The "Never filter my contacts" setting. Short-circuits every rule. */
    val neverFilterContacts: Boolean = true,
    /** Time zone used to interpret [TimeOp] conditions. */
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** The outcome of running a message past the user's filters. */
sealed interface FilterDecision {
    /** Nothing matched: deliver to the inbox normally. */
    data object Allow : FilterDecision

    /**
     * [rule] matched and stopped evaluation.
     *
     * @param reason short label shown on the Filtered screen, e.g.
     *   "Filter: Loan and crypto offers".
     */
    data class Matched(val rule: Rule, val reason: String) : FilterDecision
}

/** One historical message plus the context it arrived in, for [RuleEngine.backtest]. */
data class BacktestSample(
    val message: IncomingMessage,
    val isKnownContact: Boolean,
)

/**
 * Real counts for the builder's "This would have caught N of your last 200
 * texts, and none from your contacts" line.
 */
data class BacktestResult(
    val sampled: Int,
    val caught: Int,
    val caughtFromContacts: Int,
)

/**
 * Evaluates messages against the user's filters, and renders filters as English.
 *
 * The plain-English rendering is not decoration — "filters you write, in words
 * you choose" is the product's whole premise, and the sentence shown in the
 * builder is how a user confirms a rule does what they meant.
 */
interface RuleEngine {

    /**
     * Runs [message] past [rules] in ascending [Rule.order], returning on the
     * first match. Disabled rules are skipped. If
     * [EvaluationContext.neverFilterContacts] is set and the sender is a known
     * contact, returns [FilterDecision.Allow] without evaluating anything.
     */
    fun evaluate(
        message: IncomingMessage,
        rules: List<Rule>,
        context: EvaluationContext,
    ): FilterDecision

    /**
     * One-line summary for the Filters list, e.g.
     * "If the sender is not in my contacts and a link is present → hold it in
     * Filtered, stay silent".
     *
     * Port from the prototype's `ruleSummary()` (line 590).
     */
    fun summarize(rule: Rule): String

    /**
     * The builder's "In plain words" paragraph, e.g. "When the sender is not in
     * my contacts and a link is present, hold it in Filtered, do not make a
     * sound."
     *
     * Port from the prototype's `plainWords` (line 782). Note it differs from
     * [summarize] in both phrasing and action wording — both are needed.
     */
    fun plainWords(rule: Rule): String

    /** Evaluates [rule] alone against historical messages for the builder's backtest line. */
    fun backtest(
        rule: Rule,
        samples: List<BacktestSample>,
        context: EvaluationContext,
    ): BacktestResult
}
