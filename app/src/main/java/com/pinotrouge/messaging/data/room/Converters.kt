package com.pinotrouge.messaging.data.room

import androidx.room.TypeConverter
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.AttachmentOp
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.rules.TimeOp

/**
 * Room TypeConverters for rule conditions and actions.
 *
 * Wire format is a compact line-oriented encoding (no org.json dependency)
 * so JVM unit tests can round-trip without Robolectric:
 *
 * Conditions: `field|op|value` per line (value is percent-encoded for newlines).
 * Actions: action names joined by commas in **insertion order**.
 *
 * Unknown fields, operators, actions, or match modes never throw. A line this
 * build cannot read becomes [Condition.Unsupported] with the original bytes.
 */
class Converters {

    @TypeConverter
    fun fromMatchMode(mode: MatchMode): String = mode.name

    @TypeConverter
    fun toMatchMode(raw: String): MatchMode = MatchMode.parse(raw)

    @TypeConverter
    fun fromActions(actions: List<Action>): String =
        actions.joinToString(",") { it.name }

    @TypeConverter
    fun toActions(raw: String): List<Action> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').filter { it.isNotEmpty() }.map { Action.parse(it) }
    }

    @TypeConverter
    fun fromConditions(conditions: List<Condition>): String {
        if (conditions.isEmpty()) return ""
        return conditions.joinToString("\n") { conditionToLine(it) }
    }

    @TypeConverter
    fun toConditions(raw: String): List<Condition> {
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').filter { it.isNotEmpty() }.map { conditionFromLine(it) }
    }

    private fun conditionToLine(condition: Condition): String = when (condition) {
        is Condition.Unsupported -> condition.rawLine
        is Condition.Sender -> line("sender", condition.op.name, condition.value)
        is Condition.Text -> line("text", condition.op.name, condition.value)
        is Condition.Link -> line("link", condition.op.name, condition.value)
        is Condition.Time -> line("time", condition.op.name, condition.value)
        is Condition.Attachment -> line("attachment", condition.op.name, condition.value)
    }

    private fun conditionFromLine(line: String): Condition {
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return Condition.Unsupported(line)
        val field = parts[0]
        val op = parts[1]
        val value = decode(parts[2])
        return when (field) {
            "sender" -> {
                val parsed = SenderOp.entries.find { it.name == op }
                    ?: return Condition.Unsupported(line)
                Condition.Sender(parsed, value)
            }
            "text" -> {
                val parsed = TextOp.entries.find { it.name == op }
                    ?: return Condition.Unsupported(line)
                Condition.Text(parsed, value)
            }
            "link" -> {
                val parsed = LinkOp.entries.find { it.name == op }
                    ?: return Condition.Unsupported(line)
                Condition.Link(parsed, value)
            }
            "time" -> {
                val parsed = TimeOp.entries.find { it.name == op }
                    ?: return Condition.Unsupported(line)
                Condition.Time(parsed, value)
            }
            "attachment" -> {
                val parsed = AttachmentOp.entries.find { it.name == op }
                    ?: return Condition.Unsupported(line)
                Condition.Attachment(parsed, value)
            }
            else -> Condition.Unsupported(line)
        }
    }

    private fun line(field: String, op: String, value: String): String =
        "$field|$op|${encode(value)}"

    private fun encode(value: String): String =
        value
            .replace("%", "%25")
            .replace("|", "%7C")
            .replace("\n", "%0A")
            .replace("\r", "%0D")

    private fun decode(value: String): String =
        value
            .replace("%0D", "\r")
            .replace("%0A", "\n")
            .replace("%7C", "|")
            .replace("%25", "%")
}