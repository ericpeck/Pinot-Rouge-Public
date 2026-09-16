package com.pinotrouge.messaging.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSelectionTest {

    @Test
    fun enter_startsSelectionWithOneId() {
        val state = BatchSelection<Long>().enter(12L)
        assertTrue(state.active)
        assertEquals(setOf(12L), state.selected)
        assertEquals(1, state.count)
    }

    @Test
    fun toggle_addsAndRemoves() {
        var state = BatchSelection<Long>().enter(1L)
        state = state.toggle(2L)
        assertEquals(setOf(1L, 2L), state.selected)
        state = state.toggle(1L)
        assertEquals(setOf(2L), state.selected)
        assertEquals(1, state.count)
    }

    @Test
    fun toggle_whenInactive_isNoOp() {
        val state = BatchSelection<Long>().toggle(9L)
        assertFalse(state.active)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun clear_resets() {
        val state = BatchSelection(active = true, selected = setOf(1L, 2L, 3L)).clear()
        assertFalse(state.active)
        assertTrue(state.selected.isEmpty())
        assertEquals(0, state.count)
    }

    @Test
    fun isSelected() {
        val state = BatchSelection(active = true, selected = setOf("a", "b"))
        assertTrue(state.isSelected("a"))
        assertFalse(state.isSelected("c"))
    }
}
