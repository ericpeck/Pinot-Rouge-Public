package com.pinotrouge.messaging.ui.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the backtest empty-state wording so "no permission" never looks like
 * "caught 0 of your last 0 messages".
 */
class BacktestPermissionLineTest {

    @Test
    fun `permission line matches the brief`() {
        assertEquals(
            "Backtest needs permission to read your messages",
            BuilderViewModel.BACKTEST_NEEDS_PERMISSION,
        )
    }

    @Test
    fun `permission line is not the zero-catch line`() {
        val zeroCatch = formatBacktestLine(caught = 0, sampled = 0, caughtFromContacts = 0)
        assertNotEquals(zeroCatch, BuilderViewModel.BACKTEST_NEEDS_PERMISSION)
    }
}
