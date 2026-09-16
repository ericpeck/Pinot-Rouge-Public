package com.pinotrouge.messaging.data.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SqlLikeTest {

    @Test
    fun blank_is_null() {
        assertNull(sqlLikeContains(""))
        assertNull(sqlLikeContains("   "))
    }

    @Test
    fun wraps_substring() {
        assertEquals("%hello%", sqlLikeContains("hello"))
        assertEquals("%hello%", sqlLikeContains("  hello  "))
    }

    @Test
    fun escapes_wildcards_and_escape_char() {
        assertEquals("%a!%b!_c!!d%", sqlLikeContains("a%b_c!d"))
    }
}
