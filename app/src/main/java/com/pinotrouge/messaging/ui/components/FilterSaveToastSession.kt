package com.pinotrouge.messaging.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot save confirmation that outlives [com.pinotrouge.messaging.ui.builder.BuilderViewModel].
 *
 * Saving navigates to Filters (`handleBuilderSaved` / STATE.md case 2). The toast
 * used to live inside the builder and was torn down in the same frame as the
 * write — so *"«name» is on…"* never rendered.
 *
 * Lifecycle (claim / leave):
 * - [show] sets the message and marks it unclaimed (a new save).
 * - Filters calls [claim] when the host composes so the message is owned by
 *   that visit.
 * - [clearOnLeave] drops the message only if claimed — so disposing an
 *   under-stack Filters entry during the save navigation does **not** wipe a
 *   message that has not been claimed by the destination host yet.
 * - [clear] always drops (auto-dismiss while still on Filters).
 */
@Singleton
class FilterSaveToastSession @Inject constructor() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    @Volatile
    private var claimed: Boolean = false

    fun show(text: String) {
        claimed = false
        _message.value = text
    }

    /** Filters host is composed and will display [message] if non-null. */
    fun claim() {
        claimed = true
    }

    /**
     * Host left the composition. Clears only after a successful [claim], so a
     * back-stack dispose during navigate-to-Filters after [show] does not race.
     */
    fun clearOnLeave() {
        if (claimed) {
            _message.value = null
            claimed = false
        }
    }

    fun clear() {
        _message.value = null
        claimed = false
    }
}
