package com.pinotrouge.messaging.rules.internal

import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.EvaluationContext
import com.pinotrouge.messaging.rules.IncomingMessage
import com.pinotrouge.messaging.rules.AttachmentOp
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.rules.TimeOp
import java.time.DayOfWeek
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Evaluates a single [Condition] against a message and context.
 *
 * A malformed or oversized regex makes its condition **false** — it must not
 * throw. This code runs inside the SMS receiver that owns incoming texts.
 *
 * Pattern length and haystack length are capped so a user-authored rule cannot
 * compile an unbounded expression against an unbounded MMS body. Java's
 * `java.util.regex` engine is still backtracking; the caps bound work, they
 * do not make matching linear-time.
 */
internal object ConditionMatcher {

    /** Reject `MATCHES_REGEX` patterns longer than this, in characters. */
    const val MAX_REGEX_PATTERN_LENGTH = 256

    /** Only the leading slice of a body is searched by `MATCHES_REGEX`. */
    const val MAX_REGEX_INPUT_LENGTH = 8_192

    fun matches(
        condition: Condition,
        message: IncomingMessage,
        context: EvaluationContext,
    ): Boolean = when (condition) {
        is Condition.Sender -> matchSender(condition, message, context)
        is Condition.Text -> matchText(condition, message)
        is Condition.Link -> matchLink(condition, message)
        is Condition.Time -> matchTime(condition, message, context)
        is Condition.Attachment -> matchAttachment(condition, message)
        is Condition.Unsupported -> false
    }

    private fun matchSender(
        condition: Condition.Sender,
        message: IncomingMessage,
        context: EvaluationContext,
    ): Boolean = when (condition.op) {
        SenderOp.NOT_IN_CONTACTS -> !context.isKnownContact
        SenderOp.IS -> PhoneNumbers.sameSender(message.sender, condition.value)
        SenderOp.STARTS_WITH -> PhoneNumbers.startsWith(message.sender, condition.value)
        SenderOp.IS_SHORT_CODE -> PhoneNumbers.isShortCode(message.sender)
    }

    private fun matchText(
        condition: Condition.Text,
        message: IncomingMessage,
    ): Boolean = when (condition.op) {
        TextOp.CONTAINS_ANY -> {
            val needles = parseCommaList(condition.value)
            if (needles.isEmpty()) false
            else {
                val haystack = message.body.lowercase()
                needles.any { haystack.contains(it) }
            }
        }
        TextOp.DOES_NOT_CONTAIN -> {
            // A filter that asks about the text cannot decide about a
            // message that has no text. Blank SMS (no part) is unchanged.
            if (message.body.isBlank() && message.hasAnyPart) false
            else {
                val needles = parseCommaList(condition.value)
                if (needles.isEmpty()) true
                else {
                    val haystack = message.body.lowercase()
                    needles.none { haystack.contains(it) }
                }
            }
        }
        TextOp.MATCHES_REGEX -> matchRegex(condition.value, message.body)
    }

    private fun matchLink(
        condition: Condition.Link,
        message: IncomingMessage,
    ): Boolean = when (condition.op) {
        LinkOp.PRESENT -> Urls.anyPresent(message.body)
        LinkOp.IS_SHORTENED -> Urls.anyShortened(message.body)
        LinkOp.ON_DOMAIN -> Urls.anyOnDomain(message.body, condition.value)
    }

    private fun matchAttachment(
        condition: Condition.Attachment,
        message: IncomingMessage,
    ): Boolean = when (condition.op) {
        AttachmentOp.HAS_PHOTO -> message.hasPhoto
    }

    private fun matchTime(
        condition: Condition.Time,
        message: IncomingMessage,
        context: EvaluationContext,
    ): Boolean {
        val local = message.receivedAt.atZone(context.zone)
        return when (condition.op) {
            TimeOp.BETWEEN -> TimeWindows.isInWindow(condition.value, local.toLocalTime())
            TimeOp.ON_WEEKEND -> {
                val day = local.dayOfWeek
                day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
            }
        }
    }

    /** Comma-separated list: trim each entry, drop empties, lowercase for matching. */
    internal fun parseCommaList(value: String): List<String> =
        value.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    internal fun matchRegex(pattern: String, body: String): Boolean {
        if (pattern.length > MAX_REGEX_PATTERN_LENGTH) return false
        val compiled = try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        } catch (_: PatternSyntaxException) {
            return false
        }
        val haystack = if (body.length > MAX_REGEX_INPUT_LENGTH) {
            body.substring(0, MAX_REGEX_INPUT_LENGTH)
        } else {
            body
        }
        return try {
            compiled.matcher(haystack).find()
        } catch (_: StackOverflowError) {
            false
        }
    }
}
