package com.pinotrouge.messaging.ui.components

import android.util.Log
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds a whole-conversation deferred delete that outlives [com.pinotrouge.messaging.ui.thread.ThreadViewModel].
 *
 * Version 2 pops the thread on confirm; the 5s undo toast therefore lives on
 * the inbox. The job still runs on [ApplicationScope] so navigate-away cannot
 * drop the commit — same contract as batch delete.
 *
 * Lives under `ui/components/` (with [BatchSelection]) so both inbox and thread
 * can inject it without a package cycle.
 */
@Singleton
class ConversationDeleteSession @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    private val messageRepository: MessageRepository,
) {
    data class Pending(
        val threadId: Long,
        /** False after toast auto-dismisses; the job still owns the write. */
        val toastVisible: Boolean,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    @Volatile
    private var job: Job? = null

    /**
     * Begin deferred delete of [threadId]. Commits any previous pending
     * conversation delete first. Caller should pop the thread immediately.
     */
    fun start(threadId: Long) {
        commitNow()
        _pending.value = Pending(threadId = threadId, toastVisible = true)
        var j: Job? = null
        j = applicationScope.launch {
            delay(BATCH_DELETE_UNDO_MS)
            when (val result = messageRepository.deleteThread(threadId)) {
                is SmsRepository.WriteResult.Success -> Unit
                is SmsRepository.WriteResult.RoleNotHeld ->
                    Log.e(TAG, "deleteThread: ROLE_SMS not held; message still in provider")
                is SmsRepository.WriteResult.Failed ->
                    Log.e(TAG, "deleteThread: provider write failed; message still in provider", result.cause)
            }
            if (job === j) {
                job = null
                _pending.value = null
            }
        }
        job = j
    }

    fun undo() {
        job?.cancel()
        job = null
        _pending.value = null
    }

    /** Hide the toast only — the app-scoped job still commits. */
    fun dismissToast() {
        _pending.update { it?.copy(toastVisible = false) }
    }

    /** Immediately write any pending conversation delete (superseded by another op). */
    fun commitNow() {
        val current = _pending.value ?: return
        job?.cancel()
        job = null
        _pending.value = null
        applicationScope.launch {
            when (val result = messageRepository.deleteThread(current.threadId)) {
                is SmsRepository.WriteResult.Success -> Unit
                is SmsRepository.WriteResult.RoleNotHeld ->
                    Log.e(TAG, "deleteThread: ROLE_SMS not held; message still in provider")
                is SmsRepository.WriteResult.Failed ->
                    Log.e(TAG, "deleteThread: provider write failed; message still in provider", result.cause)
            }
        }
    }

    private companion object {
        const val TAG = "ConversationDeleteSession"
    }
}
