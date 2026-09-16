package com.pinotrouge.messaging.ui.builder

import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.AttachmentOp
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.rules.TimeOp

/**
 * Editable draft of a filter. Cancelling discards this copy — never mutates
 * a stored [Rule] in place.
 *
 * State-machine behaviour ports PinotPhone.dc.html lines 738–796:
 * field change resets op + value; last condition cannot be removed; actions
 * are multi-select with insertion-order preserved via [LinkedHashSet].
 */
data class BuilderDraft(
    /** Null when creating a new rule. */
    val existingId: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
    val match: MatchMode = MatchMode.ALL,
    val conditions: List<Condition> = listOf(defaultCondition()),
    val actions: LinkedHashSet<Action> = linkedSetOf(Action.HOLD),
    val deleteAfterDays: Int = Rule.DEFAULT_RETENTION_DAYS,
    val autoReplyText: String = "",
) {
    val isEditing: Boolean get() = existingId != null

    val isReadOnly: Boolean
        get() = !match.isKnown ||
            actions.any { !it.isKnown } ||
            conditions.any { it is Condition.Unsupported }

    fun toRule(id: String, order: Int, name: String): Rule = Rule(
        id = id,
        name = name,
        enabled = enabled,
        order = order,
        match = match,
        conditions = conditions,
        actions = LinkedHashSet(actions),
        deleteAfterDays = deleteAfterDays.coerceIn(Rule.RETENTION_RANGE),
        autoReplyText = if (Action.REPLY in actions) {
            autoReplyText.ifBlank { null }
        } else {
            null
        },
    )

    /** Rule shaped for [com.pinotrouge.messaging.rules.RuleEngine.plainWords] / backtest. */
    fun toPreviewRule(): Rule = toRule(
        id = existingId ?: "draft",
        order = order,
        name = name.ifBlank { "Untitled filter" },
    )

    companion object {
        fun defaultCondition(): Condition = Condition.Sender(SenderOp.NOT_IN_CONTACTS)

        fun newRule(): BuilderDraft = BuilderDraft()

        fun prefillSender(sender: String): BuilderDraft = BuilderDraft(
            name = "Messages from $sender",
            conditions = listOf(Condition.Sender(SenderOp.IS, sender)),
            actions = linkedSetOf(Action.HOLD),
        )

        fun fromRule(rule: Rule): BuilderDraft = BuilderDraft(
            existingId = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            order = rule.order,
            match = rule.match,
            conditions = rule.conditions.ifEmpty { listOf(defaultCondition()) },
            actions = rule.actions.toCollection(LinkedHashSet()),
            deleteAfterDays = rule.deleteAfterDays,
            autoReplyText = rule.autoReplyText.orEmpty(),
        )
    }
}

/** The builder field keys; dropdown labels use [selectLabel]. */
enum class ConditionField {
    Sender,
    Text,
    Link,
    Time,
    Attachment,
    ;

    val selectLabel: String
        get() = when (this) {
            Sender -> Condition.Sender.SELECT_LABEL
            Text -> Condition.Text.SELECT_LABEL
            Link -> Condition.Link.SELECT_LABEL
            Time -> Condition.Time.SELECT_LABEL
            Attachment -> Condition.Attachment.SELECT_LABEL
        }

    val valuePlaceholder: String
        get() = when (this) {
            Text -> "sale, % off, pre-approved"
            Time -> "22:00 – 07:00"
            else -> "Number, name or domain"
        }
}

fun Condition.field(): ConditionField? = when (this) {
    is Condition.Sender -> ConditionField.Sender
    is Condition.Text -> ConditionField.Text
    is Condition.Link -> ConditionField.Link
    is Condition.Time -> ConditionField.Time
    is Condition.Attachment -> ConditionField.Attachment
    is Condition.Unsupported -> null
}

/** Operators available for [field], in prototype order. */
fun operatorsFor(field: ConditionField): List<Pair<String, String>> = when (field) {
    ConditionField.Sender -> SenderOp.entries.map { it.name to it.label }
    ConditionField.Text -> TextOp.entries.map { it.name to it.label }
    ConditionField.Link -> LinkOp.entries.map { it.name to it.label }
    ConditionField.Time -> TimeOp.entries.map { it.name to it.label }
    ConditionField.Attachment -> AttachmentOp.entries.map { it.name to it.label }
}

fun Condition.opKey(): String = when (this) {
    is Condition.Sender -> op.name
    is Condition.Text -> op.name
    is Condition.Link -> op.name
    is Condition.Time -> op.name
    is Condition.Attachment -> op.name
    is Condition.Unsupported -> ""
}

fun firstConditionFor(field: ConditionField): Condition = when (field) {
    ConditionField.Sender -> Condition.Sender(SenderOp.entries.first())
    ConditionField.Text -> Condition.Text(TextOp.entries.first())
    ConditionField.Link -> Condition.Link(LinkOp.entries.first())
    ConditionField.Time -> Condition.Time(TimeOp.entries.first())
    ConditionField.Attachment -> Condition.Attachment(AttachmentOp.entries.first())
}

fun Condition.withOperator(opKey: String): Condition = when (this) {
    is Condition.Sender -> {
        val op = SenderOp.entries.find { it.name == opKey } ?: SenderOp.entries.first()
        copy(op = op, value = if (op.needsValue) value else "")
    }
    is Condition.Text -> {
        val op = TextOp.entries.find { it.name == opKey } ?: TextOp.entries.first()
        copy(op = op, value = if (op.needsValue) value else "")
    }
    is Condition.Link -> {
        val op = LinkOp.entries.find { it.name == opKey } ?: LinkOp.entries.first()
        copy(op = op, value = if (op.needsValue) value else "")
    }
    is Condition.Time -> {
        val op = TimeOp.entries.find { it.name == opKey } ?: TimeOp.entries.first()
        copy(op = op, value = if (op.needsValue) value else "")
    }
    is Condition.Attachment -> {
        val op = AttachmentOp.entries.find { it.name == opKey } ?: AttachmentOp.entries.first()
        copy(op = op, value = if (op.needsValue) value else "")
    }
    is Condition.Unsupported -> this
}

fun Condition.withValue(newValue: String): Condition = when (this) {
    is Condition.Sender -> copy(value = newValue)
    is Condition.Text -> copy(value = newValue)
    is Condition.Link -> copy(value = newValue)
    is Condition.Time -> copy(value = newValue)
    is Condition.Attachment -> copy(value = newValue)
    is Condition.Unsupported -> this
}

/**
 * Changing the field resets the operator to that field's first and clears the
 * value (prototype line 759).
 */
fun BuilderDraft.setField(index: Int, field: ConditionField): BuilderDraft {
    if (isReadOnly) return this
    if (index !in conditions.indices) return this
    val next = conditions.toMutableList()
    next[index] = firstConditionFor(field)
    return copy(conditions = next)
}

fun BuilderDraft.setOperator(index: Int, opKey: String): BuilderDraft {
    if (isReadOnly) return this
    if (index !in conditions.indices) return this
    val next = conditions.toMutableList()
    next[index] = next[index].withOperator(opKey)
    return copy(conditions = next)
}

fun BuilderDraft.setValue(index: Int, value: String): BuilderDraft {
    if (isReadOnly) return this
    if (index !in conditions.indices) return this
    val next = conditions.toMutableList()
    next[index] = next[index].withValue(value)
    return copy(conditions = next)
}

/** Never removes the last condition (prototype line 762). */
fun BuilderDraft.removeCondition(index: Int): BuilderDraft {
    if (isReadOnly) return this
    if (conditions.size <= 1) return this
    if (index !in conditions.indices) return this
    return copy(conditions = conditions.toMutableList().also { it.removeAt(index) })
}

fun BuilderDraft.addCondition(): BuilderDraft {
    if (isReadOnly) return this
    return copy(
        conditions = conditions + Condition.Text(TextOp.CONTAINS_ANY, ""),
    )
}

fun BuilderDraft.toggleMatch(): BuilderDraft {
    if (isReadOnly) return this
    return copy(
        match = if (match == MatchMode.ALL) MatchMode.ANY else MatchMode.ALL,
    )
}

/**
 * Multi-select actions. Insertion order is preserved so [RuleEngine.plainWords]
 * assembles the sentence in the order the user picked chips — except when
 * DELETE is set, the set is rebuilt in [ACTION_CHIP_ORDER] so HOLD is named
 * before the deletion.
 *
 * DELETE only has meaning for a held message: commitExpired is the only
 * reader, and it reads `held_messages`. Selecting DELETE selects HOLD;
 * deselecting HOLD deselects DELETE. A blanket "DELETE implies HOLD" after
 * the toggle would lock HOLD on (the user could never clear the pair);
 * the pairing is applied to the chip that was just flipped instead.
 */
fun BuilderDraft.toggleAction(action: Action): BuilderDraft {
    if (isReadOnly) return this
    val next = LinkedHashSet(actions)
    val selected = next.add(action)
    if (!selected) next.remove(action)
    when {
        action == Action.DELETE && selected -> next.add(Action.HOLD)
        action == Action.HOLD && !selected -> next.remove(Action.DELETE)
    }
    val ordered = if (Action.DELETE in next) {
        LinkedHashSet<Action>().apply {
            ACTION_CHIP_ORDER.forEach { if (it in next) add(it) }
            next.filterNot { it in this }.forEach { add(it) }
        }
    } else {
        next
    }
    return copy(actions = ordered)
}

fun BuilderDraft.adjustDeleteDays(delta: Int): BuilderDraft {
    if (isReadOnly) return this
    val next = (deleteAfterDays + delta).coerceIn(Rule.RETENTION_RANGE)
    return copy(deleteAfterDays = next)
}

/**
 * Fixed chip display order — not the same as set iteration order.
 * [Action.REPLY] is intentionally absent (fix/remove-auto-reply); saved rules
 * may still carry it in [BuilderDraft.actions] for round-trip.
 */
val ACTION_CHIP_ORDER: List<Action> = listOf(
    Action.HOLD,
    Action.SILENCE,
    Action.DELETE,
    Action.BLOCK,
    Action.READ,
)

fun Action.chipLabel(): String = when (this) {
    Action.HOLD -> "Hold in Filtered"
    Action.SILENCE -> "Stay silent"
    Action.DELETE -> "Auto-delete"
    Action.BLOCK -> "Block sender"
    Action.REPLY -> "Auto-reply" // not offered; kept for any leftover UI
    Action.READ -> "Mark as read"
    else -> ""
}

/**
 * Backtest line — Eric, 2026-08-21. Samples are SMS-only (`getRecentInbox`).
 *
 * > This would have caught 23 of your last 200 texts, and none from your contacts.
 */
fun formatBacktestLine(caught: Int, sampled: Int, caughtFromContacts: Int): String {
    val contactPart = if (caughtFromContacts == 0) {
        "and none from your contacts"
    } else {
        "and $caughtFromContacts from your contacts"
    }
    return "This would have caught $caught of your last $sampled texts, $contactPart."
}

const val UNTITLED_FILTER_NAME = "Untitled filter"
const val SAVE_TOAST_SUFFIX = " is on. It starts with the next message."

/** Shown above a read-only builder when a condition cannot be parsed. */
const val UNSUPPORTED_RULE_REASON =
    "This filter was written in a newer version of Pinot Rouge. It is paused until you update, and nothing in it has been changed."

fun saveToastMessage(name: String): String =
    "\u201C$name\u201D$SAVE_TOAST_SUFFIX"
