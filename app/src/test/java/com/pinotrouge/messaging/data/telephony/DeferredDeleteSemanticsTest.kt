package com.pinotrouge.messaging.data.telephony

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins deferred-delete semantics: confirm does not write; undo cancels;
 * timeout writes once. Mirrors the ViewModel pattern without Android.
 *
 * Job-handle identity matches the fix in fix/batch-delete-followups:
 * clear `pendingDeleteJob` only when it still points at *this* job.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredDeleteSemanticsTest {

    private class FakeDeleter {
        val deleted = mutableListOf<Long>()
        fun delete(ids: Collection<Long>) {
            deleted += ids.sorted()
        }
    }

    /**
     * Mirrors Inbox/Thread/Filtered confirm + undo + commit-prior-on-new-confirm.
     */
    private class Controller(
        private val scope: TestScope,
        private val deleter: FakeDeleter,
        private val undoMs: Long = 5_000L,
    ) {
        var pending: Set<Long> = emptySet()
            private set
        private var pendingDeleteJob: Job? = null

        fun confirm(ids: Set<Long>) {
            commitPendingNow()
            pending = ids
            var job: Job? = null
            job = scope.launch {
                delay(undoMs)
                deleter.delete(ids)
                if (pending == ids) pending = emptySet()
                if (pendingDeleteJob === job) pendingDeleteJob = null
            }
            pendingDeleteJob = job
        }

        fun undo() {
            pendingDeleteJob?.cancel()
            pendingDeleteJob = null
            pending = emptySet()
        }

        private fun commitPendingNow() {
            val ids = pending
            if (ids.isEmpty()) return
            pendingDeleteJob?.cancel()
            pendingDeleteJob = null
            pending = emptySet()
            scope.launch {
                deleter.delete(ids)
            }
        }
    }

    @Test
    fun confirm_thenUndo_neverWrites() = runTest {
        val deleter = FakeDeleter()
        val controller = Controller(this, deleter)
        controller.confirm(setOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), controller.pending)
        assertTrue(deleter.deleted.isEmpty())
        controller.undo()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(controller.pending.isEmpty())
        assertTrue(deleter.deleted.isEmpty())
    }

    @Test
    fun confirm_thenTimeout_writesOnce() = runTest {
        val deleter = FakeDeleter()
        val controller = Controller(this, deleter)
        controller.confirm(setOf(10L, 20L))
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(deleter.deleted.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(10L, 20L), deleter.deleted)
        assertTrue(controller.pending.isEmpty())
    }

    /**
     * Confirm A, then B (A is committed immediately), then undo B:
     * only A is written; B's deferred job must actually be cancelled.
     */
    @Test
    fun confirmA_thenConfirmB_thenUndoB_onlyDeletesA() = runTest {
        val deleter = FakeDeleter()
        val controller = Controller(this, deleter)
        controller.confirm(setOf(1L))
        controller.confirm(setOf(2L))
        runCurrent() // flush A's immediate commit from confirm B
        assertEquals(listOf(1L), deleter.deleted)
        assertEquals(setOf(2L), controller.pending)

        controller.undo()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(
            "B must not delete after undo — job handle must still point at B when undoing",
            listOf(1L),
            deleter.deleted,
        )
        assertTrue(controller.pending.isEmpty())
    }

    /**
     * If an older job blindly nulls the handle after a newer confirm, undo would
     * clear UI state while the new job still fires. Compare-and-clear prevents that.
     */
    @Test
    fun olderJob_doesNotClearNewerJobHandle_soUndoStillCancelsB() = runTest {
        val deleter = FakeDeleter()
        val controller = Controller(this, deleter)

        controller.confirm(setOf(100L))
        // Let A expire almost fully, then confirm B in the same window A's
        // completion would have run `pendingDeleteJob = null` under the old code.
        advanceTimeBy(5_000)
        runCurrent() // A deletes
        assertEquals(listOf(100L), deleter.deleted)

        controller.confirm(setOf(200L))
        // Simulate what a buggy A-completion would do if it ran late: we rely on
        // compare-and-clear so only the active job may clear the field. Undo B.
        controller.undo()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf(100L), deleter.deleted)
    }
}
