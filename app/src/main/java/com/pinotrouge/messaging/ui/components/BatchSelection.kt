package com.pinotrouge.messaging.ui.components

/**
 * Pure selection-mode state for email-style multi-select.
 *
 * Lives in the ViewModel (not composition) so ContentObserver refreshes
 * cannot wipe a selection mid-gesture.
 */
data class BatchSelection<T>(
    val active: Boolean = false,
    val selected: Set<T> = emptySet(),
) {
    val count: Int get() = selected.size

    fun enter(id: T): BatchSelection<T> =
        BatchSelection(active = true, selected = setOf(id))

    fun toggle(id: T): BatchSelection<T> {
        if (!active) return this
        val next = if (id in selected) selected - id else selected + id
        return copy(selected = next)
    }

    fun clear(): BatchSelection<T> = BatchSelection()

    fun isSelected(id: T): Boolean = id in selected
}

/** Undo-window length for deferred deletes (confirm removes from UI only). */
const val BATCH_DELETE_UNDO_MS = 5_000L
