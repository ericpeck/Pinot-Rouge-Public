package com.pinotrouge.messaging.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a conversation marks it read. Compose-new (`threadId == 0`) does not.
 *
 * [ThreadViewModel] cannot be constructed on the JVM (final Android
 * collaborators, no mockk on the unit-test classpath), so this uses a
 * recording fake against the same [ThreadViewModel.dispatchMarkThreadReadOnOpen]
 * gate `init` calls.
 */
class ThreadViewModelMarkReadTest {

    private class FakeSmsRepository {
        val markedThreadIds = mutableListOf<Long>()
        fun markThreadRead(threadId: Long) {
            markedThreadIds += threadId
        }
    }

    @Test
    fun existing_thread_marks_read_once() {
        val sms = FakeSmsRepository()
        ThreadViewModel.dispatchMarkThreadReadOnOpen(threadId = 42L) { id ->
            sms.markThreadRead(id)
        }
        assertEquals(listOf(42L), sms.markedThreadIds)
    }

    @Test
    fun compose_new_threadId_zero_does_not_mark_read() {
        val sms = FakeSmsRepository()
        ThreadViewModel.dispatchMarkThreadReadOnOpen(threadId = 0L) { id ->
            sms.markThreadRead(id)
        }
        assertTrue(sms.markedThreadIds.isEmpty())
    }
}
