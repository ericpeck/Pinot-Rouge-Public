package com.pinotrouge.messaging.data.telephony

import com.pinotrouge.messaging.ui.components.BatchSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRefTest {

    @Test
    fun toLazyKey_isSaveableStringNotDataClass() {
        assertEquals("sms:42", MessageRef.sms(42).toLazyKey())
        assertEquals("mms:42", MessageRef.mms(42).toLazyKey())
        assertNotEquals(
            MessageRef.sms(42).toLazyKey(),
            MessageRef.mms(42).toLazyKey(),
        )
    }

    @Test
    fun collidingProviderIds_areDistinctRefs() {
        val sms = MessageRef.sms(42)
        val mms = MessageRef.mms(42)
        assertNotEquals(sms, mms)
        val selected = BatchSelection<MessageRef>().enter(mms)
        assertTrue(selected.isSelected(mms))
        assertFalse(selected.isSelected(sms))
    }

    @Test
    fun threadDeleteClearedBothTables_rejectsPartial() {
        assertTrue(
            threadDeleteClearedBothTables(
                smsThrew = false,
                mmsThrew = false,
                smsRemaining = 0,
                mmsRemaining = 0,
            ),
        )
        assertFalse(
            threadDeleteClearedBothTables(
                smsThrew = false,
                mmsThrew = false,
                smsRemaining = 0,
                mmsRemaining = 1,
            ),
        )
        assertFalse(
            threadDeleteClearedBothTables(
                smsThrew = false,
                mmsThrew = true,
                smsRemaining = 0,
                mmsRemaining = 0,
            ),
        )
        assertFalse(
            threadDeleteClearedBothTables(
                smsThrew = true,
                mmsThrew = false,
                smsRemaining = 0,
                mmsRemaining = 0,
            ),
        )
    }
}
