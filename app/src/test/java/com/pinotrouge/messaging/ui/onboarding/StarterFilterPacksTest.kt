package com.pinotrouge.messaging.ui.onboarding

import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.MatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterFilterPacksTest {

    @Test
    fun `defaults match prototype packs`() {
        val defaults = StarterFilterPacks.all.associate { it.key to it.defaultEnabled }
        assertTrue(defaults.getValue(StarterPackKey.Links))
        assertTrue(defaults.getValue(StarterPackKey.Promos))
        assertTrue(defaults.getValue(StarterPackKey.Loans))
        assertFalse(defaults.getValue(StarterPackKey.Quiet))
    }

    @Test
    fun `rulesFor applies pack toggles to seed rules`() {
        val rules = StarterFilterPacks.rulesFor(
            mapOf(
                StarterPackKey.Links to true,
                StarterPackKey.Promos to false,
                StarterPackKey.Loans to true,
                StarterPackKey.Quiet to true,
            ),
        )
        assertEquals(4, rules.size)
        assertEquals("r1", rules[0].id)
        assertTrue(rules[0].enabled)
        assertFalse(rules[1].enabled)
        assertTrue(rules[2].enabled)
        assertTrue(rules[3].enabled)
        assertEquals(MatchMode.ALL, rules[0].match)
        assertTrue(rules[0].actions.contains(Action.HOLD))
        assertEquals(14, rules[1].deleteAfterDays)
    }
}
