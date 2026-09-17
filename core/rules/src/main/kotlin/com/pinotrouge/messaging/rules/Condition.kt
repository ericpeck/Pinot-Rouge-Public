package com.pinotrouge.messaging.rules

/**
 * One clause of a [Rule].
 *
 * Four fields come from the design prototype's `opsFor()` (PinotPhone.dc.html
 * line 575). [Attachment] is a Wave 17 addition — V5 has no attachment
 * operator. [Unsupported] holds a line this build cannot read.
 *
 * [label] is the phrase used when rendering the rule as English. Note that the
 * builder's dropdown wording differs from the sentence wording on purpose —
 * "the time it arrives" in the `<select>`, "the arrival time" in the sentence —
 * so both are carried: [selectLabel] and [label].
 */
sealed interface Condition {
    /** The operator's human phrase, as it reads inside a sentence. */
    val opLabel: String

    /** The value the operator compares against; empty when [needsValue] is false. */
    val value: String

    /** False for operators that are self-contained ("is a short code"). */
    val needsValue: Boolean

    /** The field's phrase as it reads inside a sentence. */
    val fieldLabel: String

    data class Sender(val op: SenderOp, override val value: String = "") : Condition {
        override val opLabel get() = op.label
        override val needsValue get() = op.needsValue
        override val fieldLabel get() = FIELD_LABEL

        companion object {
            const val FIELD_LABEL = "the sender"
            const val SELECT_LABEL = "the sender"
        }
    }

    data class Text(val op: TextOp, override val value: String = "") : Condition {
        override val opLabel get() = op.label
        override val needsValue get() = op.needsValue
        override val fieldLabel get() = FIELD_LABEL

        companion object {
            const val FIELD_LABEL = "the message text"
            const val SELECT_LABEL = "the message text"
        }
    }

    data class Link(val op: LinkOp, override val value: String = "") : Condition {
        override val opLabel get() = op.label
        override val needsValue get() = op.needsValue
        override val fieldLabel get() = FIELD_LABEL

        companion object {
            const val FIELD_LABEL = "a link"
            const val SELECT_LABEL = "a link in the message"
        }
    }

    data class Time(val op: TimeOp, override val value: String = "") : Condition {
        override val opLabel get() = op.label
        override val needsValue get() = op.needsValue
        override val fieldLabel get() = FIELD_LABEL

        companion object {
            const val FIELD_LABEL = "the arrival time"
            const val SELECT_LABEL = "the time it arrives"
        }
    }

    data class Attachment(val op: AttachmentOp, override val value: String = "") : Condition {
        override val opLabel get() = op.label
        override val needsValue get() = op.needsValue
        override val fieldLabel get() = FIELD_LABEL

        companion object {
            const val FIELD_LABEL = "a picture"
            const val SELECT_LABEL = "a picture in the message"
        }
    }

    /**
     * A serialized condition this build cannot read. [rawLine] is the exact
     * stored bytes — re-serialise it unchanged. The engine skips any rule
     * that contains one; the builder opens the rule read-only.
     */
    data class Unsupported(val rawLine: String) : Condition {
        override val opLabel get() = ""
        override val needsValue get() = false
        override val fieldLabel get() = ROW_LABEL
        override val value get() = ""

        companion object {
            const val ROW_LABEL = "A condition this version can't read"
        }
    }
}

enum class SenderOp(val label: String, val needsValue: Boolean) {
    NOT_IN_CONTACTS("is not in my contacts", needsValue = false),
    IS("is", needsValue = true),
    STARTS_WITH("starts with", needsValue = true),
    IS_SHORT_CODE("is a short code", needsValue = false),
}

enum class TextOp(val label: String, val needsValue: Boolean) {
    /** Value is a comma-separated list; the condition holds if *any* entry appears. */
    CONTAINS_ANY("contains any of", needsValue = true),
    DOES_NOT_CONTAIN("does not contain", needsValue = true),

    /**
     * Value is a user-authored regular expression, matched with RE2 (linear
     * time). Unsupported syntax makes the **rule** [Rule.isUnreadable] rather
     * than treating the condition as false (which would under-filter).
     */
    MATCHES_REGEX("matches the pattern", needsValue = true),
}

enum class LinkOp(val label: String, val needsValue: Boolean) {
    PRESENT("is present", needsValue = false),
    IS_SHORTENED("is a shortened link", needsValue = false),
    ON_DOMAIN("is on this domain", needsValue = true),
}

enum class TimeOp(val label: String, val needsValue: Boolean) {
    /** Value reads "22:00 – 07:00" and may wrap past midnight. */
    BETWEEN("is between", needsValue = true),
    ON_WEEKEND("is on a weekend", needsValue = false),
}

enum class AttachmentOp(val label: String, val needsValue: Boolean) {
    HAS_PHOTO("is present", needsValue = false),
}
