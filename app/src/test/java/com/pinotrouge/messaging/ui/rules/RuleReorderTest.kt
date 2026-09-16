package com.pinotrouge.messaging.ui.rules

import com.pinotrouge.messaging.data.repo.withDenseOrder
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.TextOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the reorder renumbering path: after any drag drop the repository
 * writes dense 0..n-1 orders with no duplicates, in the dropped sequence.
 */
class RuleReorderTest {

    private fun rule(id: String, order: Int) = Rule(
        id = id,
        name = id,
        order = order,
        match = MatchMode.ANY,
        conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "x")),
        actions = setOf(Action.HOLD),
    )

    @Test
    fun withDenseOrder_rewritesSparseOrdersToZeroBased() {
        val sparse = listOf(
            rule("c", order = 99),
            rule("a", order = 5),
            rule("b", order = 10),
        )
        val dense = withDenseOrder(sparse)
        assertEquals(listOf("c", "a", "b"), dense.map { it.id })
        assertEquals(listOf(0, 1, 2), dense.map { it.order })
    }

    @Test
    fun withDenseOrder_isIdempotentWhenAlreadyDense() {
        val already = listOf(
            rule("a", order = 0),
            rule("b", order = 1),
            rule("c", order = 2),
        )
        val dense = withDenseOrder(already)
        assertEquals(listOf(0, 1, 2), dense.map { it.order })
        // Unchanged rows keep identity so Room replace is a no-op for content.
        assertSame(already[0], dense[0])
        assertSame(already[1], dense[1])
        assertSame(already[2], dense[2])
    }

    @Test
    fun withDenseOrder_emptyAndSingle() {
        assertEquals(emptyList<Rule>(), withDenseOrder(emptyList()))
        val one = listOf(rule("only", order = 7))
        assertEquals(0, withDenseOrder(one).single().order)
        assertEquals("only", withDenseOrder(one).single().id)
    }

    @Test
    fun moveItem_reordersListForLiveDrag() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), moveItem(items, fromIndex = 1, toIndex = 0))
        assertEquals(listOf("a", "c", "d", "b"), moveItem(items, fromIndex = 1, toIndex = 3))
        assertEquals(listOf("a", "c", "b", "d"), moveItem(items, fromIndex = 2, toIndex = 1))
    }

    @Test
    fun moveItem_noOpOnEqualOrOutOfRange() {
        val items = listOf("a", "b", "c")
        assertSame(items, moveItem(items, fromIndex = 1, toIndex = 1))
        assertSame(items, moveItem(items, fromIndex = -1, toIndex = 0))
        assertSame(items, moveItem(items, fromIndex = 0, toIndex = 9))
    }

    @Test
    fun dropPath_moveThenDense_matchesPersistenceContract() {
        // Simulate: user drags "c" (order 2) to the top, then drop renumbers.
        val before = listOf(
            rule("a", 0),
            rule("b", 1),
            rule("c", 2),
        )
        val afterDrag = moveItem(before, fromIndex = 2, toIndex = 0)
        val persisted = withDenseOrder(afterDrag)
        assertEquals(listOf("c", "a", "b"), persisted.map { it.id })
        assertEquals(listOf(0, 1, 2), persisted.map { it.order })
        // No duplicate orders after several reorders.
        val again = withDenseOrder(moveItem(persisted, fromIndex = 0, toIndex = 2))
        assertEquals(listOf("a", "b", "c"), again.map { it.id })
        assertEquals(listOf(0, 1, 2), again.map { it.order })
        assertEquals(again.map { it.order }.toSet().size, again.size)
    }
}
