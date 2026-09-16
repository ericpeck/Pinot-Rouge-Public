package com.pinotrouge.messaging.data

import com.pinotrouge.messaging.data.repo.toDomain
import com.pinotrouge.messaging.data.repo.toEntity
import com.pinotrouge.messaging.data.room.Converters
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.AttachmentOp
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.rules.TimeOp
import com.pinotrouge.messaging.rules.isUnreadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules must survive save/load with conditions and actions intact, and action
 * order must be stable (rule-engine review: HashSet would scramble sentences).
 */
class RuleConvertersTest {

    private val converters = Converters()

    @Test
    fun `conditions round-trip preserves order and values`() {
        val conditions = listOf(
            Condition.Sender(SenderOp.NOT_IN_CONTACTS),
            Condition.Link(LinkOp.PRESENT),
            Condition.Text(TextOp.CONTAINS_ANY, "sale, % off, coupon, deal"),
            Condition.Text(TextOp.MATCHES_REGEX, "(pre-?approved|no credit check)"),
            Condition.Time(TimeOp.BETWEEN, "22:00 – 07:00"),
            Condition.Sender(SenderOp.IS, "Mom"),
        )
        val encoded = converters.fromConditions(conditions)
        val decoded = converters.toConditions(encoded)
        assertEquals(conditions, decoded)
    }

    @Test
    fun `condition value with pipe and newline survives encoding`() {
        val conditions = listOf(
            Condition.Text(TextOp.CONTAINS_ANY, "a|b\nc"),
        )
        val decoded = converters.toConditions(converters.fromConditions(conditions))
        assertEquals(conditions, decoded)
    }

    @Test
    fun `actions round-trip preserves insertion order`() {
        val actions = listOf(Action.HOLD, Action.SILENCE, Action.DELETE, Action.BLOCK, Action.REPLY, Action.READ)
        val encoded = converters.fromActions(actions)
        val decoded = converters.toActions(encoded)
        assertEquals(actions, decoded)
        assertEquals(
            "HOLD,SILENCE,DELETE,BLOCK,REPLY,READ",
            encoded,
        )
    }

    @Test
    fun `empty conditions and actions round-trip`() {
        assertEquals(emptyList<Condition>(), converters.toConditions(converters.fromConditions(emptyList())))
        assertEquals(emptyList<Action>(), converters.toActions(converters.fromActions(emptyList())))
    }

    @Test
    fun `match mode round-trips`() {
        assertEquals(MatchMode.ALL, converters.toMatchMode(converters.fromMatchMode(MatchMode.ALL)))
        assertEquals(MatchMode.ANY, converters.toMatchMode(converters.fromMatchMode(MatchMode.ANY)))
    }

    @Test
    fun `Rule entity domain round-trip keeps seed rule shape`() {
        val original = Rule(
            id = "r1",
            name = "Links from people I do not know",
            enabled = true,
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Link(LinkOp.PRESENT),
            ),
            actions = linkedSetOf(Action.HOLD, Action.SILENCE),
            deleteAfterDays = 30,
            autoReplyText = null,
        )
        val entity = original.toEntity()
        // Simulate Room converters on the entity fields
        val storedConditions = converters.toConditions(converters.fromConditions(entity.conditions))
        val storedActions = converters.toActions(converters.fromActions(entity.actions))
        val restoredEntity = entity.copy(conditions = storedConditions, actions = storedActions)
        val restored = restoredEntity.toDomain()

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.order, restored.order)
        assertEquals(original.match, restored.match)
        assertEquals(original.conditions, restored.conditions)
        assertEquals(original.deleteAfterDays, restored.deleteAfterDays)
        assertEquals(original.autoReplyText, restored.autoReplyText)
        assertEquals(listOf(Action.HOLD, Action.SILENCE), restored.actions.toList())
        assertTrue(restored.actions is LinkedHashSet || restored.actions.toList() == listOf(Action.HOLD, Action.SILENCE))
    }

    @Test
    fun `promotions seed rule with deleteAfterDays 14 survives`() {
        val original = Rule(
            id = "r2",
            name = "Promotions and sales",
            enabled = true,
            order = 1,
            match = MatchMode.ANY,
            conditions = listOf(
                Condition.Text(TextOp.CONTAINS_ANY, "sale, % off, coupon, deal"),
            ),
            actions = linkedSetOf(Action.HOLD, Action.SILENCE, Action.DELETE),
            deleteAfterDays = 14,
        )
        val entity = original.toEntity()
        val restored = entity.copy(
            conditions = converters.toConditions(converters.fromConditions(entity.conditions)),
            actions = converters.toActions(converters.fromActions(entity.actions)),
        ).toDomain()

        assertEquals(original, restored.copy(actions = original.actions)) // Set equality
        assertEquals(listOf(Action.HOLD, Action.SILENCE, Action.DELETE), restored.actions.toList())
        assertEquals(14, restored.deleteAfterDays)
    }

    @Test
    fun `unknown match mode does not throw and preserves the original name`() {
        val decoded = converters.toMatchMode("XOR")
        assertEquals("XOR", decoded.name)
        assertFalse(decoded.isKnown)
        assertEquals("XOR", converters.fromMatchMode(decoded))
    }

    @Test
    fun `unknown match mode survives a filters-list enable toggle`() {
        val original = Rule(
            id = "r-xor",
            name = "Future match mode",
            enabled = true,
            order = 0,
            match = converters.toMatchMode("XOR"),
            conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
            actions = linkedSetOf(Action.HOLD),
        )
        val entity = original.toEntity().copy(enabled = false)
        val restored = entity.copy(
            match = converters.toMatchMode(converters.fromMatchMode(entity.match)),
        ).toDomain()
        assertEquals("XOR", restored.match.name)
        assertTrue(restored.isUnreadable)
        assertFalse(restored.enabled)
    }

    @Test
    fun `unknown action does not throw and preserves the raw csv`() {
        val unknownActions = converters.toActions("HOLD,FLY")
        assertEquals("HOLD,FLY", converters.fromActions(unknownActions))
        assertEquals(listOf(Action.HOLD, Action.parse("FLY")), unknownActions)
        assertTrue(unknownActions.any { !it.isKnown })
    }

    @Test
    fun `unknown action csv survives LinkedHashSet mapping and a filters toggle`() {
        // RuleMappers.toDomain copies via LinkedHashSet, which drops List subclasses.
        // Unknown names have to be Action values themselves or a Filters-list
        // toggle rewrites the row and loses them (#199).
        val afterMapper = converters.toActions("HOLD,FLY").toCollection(LinkedHashSet())
        assertEquals("HOLD,FLY", converters.fromActions(afterMapper.toList()))

        val original = Rule(
            id = "r-fly",
            name = "Future action",
            enabled = true,
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
            actions = afterMapper,
        )
        val entity = original.toEntity().copy(enabled = false)
        val restored = entity.copy(
            actions = converters.toActions(converters.fromActions(entity.actions)),
        ).toDomain()
        assertEquals(listOf("HOLD", "FLY"), restored.actions.map { it.name })
        assertTrue(restored.isUnreadable)
        assertFalse(restored.enabled)
    }

    @Test
    fun `malformed condition line becomes Unsupported and round-trips`() {
        assertEquals(
            listOf(Condition.Unsupported("not-a-line")),
            converters.toConditions("not-a-line"),
        )
        assertEquals(
            "not-a-line",
            converters.fromConditions(converters.toConditions("not-a-line")),
        )
    }

    @Test
    fun `unknown operator becomes Unsupported and round-trips`() {
        assertEquals(
            listOf(Condition.Unsupported("sender|IS_ROBOT|x")),
            converters.toConditions("sender|IS_ROBOT|x"),
        )
        assertEquals(
            "sender|IS_ROBOT|x",
            converters.fromConditions(converters.toConditions("sender|IS_ROBOT|x")),
        )
    }

    @Test
    fun `unknown field becomes Unsupported and round-trips`() {
        assertEquals(
            listOf(Condition.Unsupported("attachment|HAS_VIDEO|")),
            converters.toConditions("attachment|HAS_VIDEO|"),
        )
        assertEquals(
            "attachment|HAS_VIDEO|",
            converters.fromConditions(converters.toConditions("attachment|HAS_VIDEO|")),
        )
    }

    @Test
    fun `HAS_PHOTO condition round-trips`() {
        val photo = listOf(Condition.Attachment(AttachmentOp.HAS_PHOTO))
        assertEquals(photo, converters.toConditions(converters.fromConditions(photo)))
    }

    @Test
    fun `unsupported line survives a filters-list enable toggle round-trip`() {
        val original = Rule(
            id = "r-old",
            name = "Unknown sender with a photo",
            enabled = true,
            order = 0,
            match = MatchMode.ALL,
            conditions = listOf(
                Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                Condition.Unsupported("attachment|HAS_VIDEO|"),
            ),
            actions = linkedSetOf(Action.HOLD),
        )
        val entity = original.toEntity().copy(enabled = false)
        val stored = converters.toConditions(converters.fromConditions(entity.conditions))
        val restored = entity.copy(conditions = stored).toDomain()
        assertEquals("attachment|HAS_VIDEO|", (restored.conditions[1] as Condition.Unsupported).rawLine)
        assertEquals(
            "attachment|HAS_VIDEO|",
            converters.fromConditions(listOf(restored.conditions[1])),
        )
        assertTrue(restored.isUnreadable)
        assertFalse(restored.enabled)
    }
}
