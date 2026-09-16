package com.pinotrouge.messaging.ui.compose

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.sms.MmsSendEvent
import com.pinotrouge.messaging.sms.MmsSendVerdict
import com.pinotrouge.messaging.sms.SendToActivity
import com.pinotrouge.messaging.sms.SmsRoleManager
import com.pinotrouge.messaging.sms.SmsSender
import com.pinotrouge.messaging.sms.SmsSubmitResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ComposeUiState(
    /** Free-text To field — comma-separated numbers/names for multi-recipient. */
    val recipient: String = "",
    val body: String = "",
    val roleHeld: Boolean = false,
    val sending: Boolean = false,
    val sendError: String? = null,
    /** Set after a successful send so the Route can navigate and toast. */
    val sentThreadId: Long? = null,
    val showSentToast: Boolean = false,
)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val smsSender: SmsSender,
    private val smsRoleManager: SmsRoleManager,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ComposeUiState(roleHeld = smsRoleManager.isRoleHeld()),
    )

    val uiState: StateFlow<ComposeUiState> = combine(
        _state,
        smsRoleManager.roleHeld,
    ) { state, held ->
        state.copy(roleHeld = held)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        _state.value,
    )

    init {
        smsRoleManager.refresh()
        consumePendingCompose()
        restorePendingMms()
        viewModelScope.launch {
            smsSender.sendCompletions.collect { event ->
                onMmsSendCompleted(event)
            }
        }
    }

    fun refreshRole() {
        smsRoleManager.refresh()
    }

    fun onRecipientChange(value: String) {
        _state.update { it.copy(recipient = value, sendError = null) }
    }

    /**
     * Append a phone number from the system contact picker (does not invent UI —
     * [android.provider.ContactsContract] ACTION_PICK).
     */
    fun addRecipientFromPicker(address: String) {
        val number = address.trim()
        if (number.isEmpty()) return
        _state.update { state ->
            val existing = parseRecipients(state.recipient)
            if (existing.any { it.equals(number, ignoreCase = true) }) {
                state
            } else {
                val next = (existing + number).joinToString(", ")
                state.copy(recipient = next, sendError = null)
            }
        }
    }

    fun onBodyChange(value: String) {
        _state.update { it.copy(body = value, sendError = null) }
    }

    fun consumeSentNavigation() {
        _state.update { it.copy(sentThreadId = null) }
    }

    fun dismissSentToast() {
        _state.update { it.copy(showSentToast = false) }
    }

    /**
     * Reads and clears [SendToActivity] pending prefs (recipient + body from
     * ACTION_SENDTO). Safe to call multiple times — empty after first consume.
     */
    fun consumePendingCompose() {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences(
                    SendToActivity.PREFS,
                    Context.MODE_PRIVATE,
                )
                val recipient = prefs.getString(SendToActivity.KEY_RECIPIENT, null)
                val body = prefs.getString(SendToActivity.KEY_BODY, null)
                if (recipient.isNullOrBlank() && body.isNullOrBlank()) {
                    null
                } else {
                    prefs.edit().clear().apply()
                    PendingCompose(
                        recipient = recipient.orEmpty(),
                        body = body.orEmpty(),
                    )
                }
            }
            if (pending != null) {
                _state.update {
                    it.copy(
                        recipient = pending.recipient.ifBlank { it.recipient },
                        body = pending.body.ifBlank { it.body },
                    )
                }
            }
        }
    }

    /**
     * One recipient → SMS ([SmsSender.send]). Two or more → MMS
     * ([SmsSender.sendMms]) via the platform MMSC path.
     */
    fun send() {
        val snapshot = _state.value
        val recipients = parseRecipients(snapshot.recipient)
        val body = snapshot.body.trim()
        if (recipients.isEmpty() || body.isEmpty() || snapshot.sending) return
        if (!smsRoleManager.isRoleHeld()) {
            _state.update { it.copy(sendError = DISABLED_REASON) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, sendError = null) }
            if (recipients.size == 1) {
                when (smsSender.send(recipients.single(), body)) {
                    is SmsSubmitResult.Submitted -> {
                        val threadId = withContext(Dispatchers.IO) {
                            runCatching {
                                Telephony.Threads.getOrCreateThreadId(context, recipients.single())
                            }.getOrDefault(0L).takeIf { it != 0L }
                        }
                        _state.update {
                            it.copy(
                                sending = false,
                                sendError = null,
                                sentThreadId = threadId,
                                showSentToast = threadId != null,
                                recipient = if (threadId != null) "" else it.recipient,
                                body = if (threadId != null) "" else it.body,
                            )
                        }
                    }
                    is SmsSubmitResult.FailedBeforeSubmit -> {
                        _state.update {
                            it.copy(
                                sending = false,
                                sendError = "Message could not be sent.",
                            )
                        }
                    }
                }
            } else {
                val submitted = smsSender.sendMms(recipients, body)
                if (submitted == null) {
                    _state.update {
                        it.copy(
                            sending = false,
                            sendError = "Message could not be sent.",
                        )
                    }
                } else {
                    savedStateHandle[KEY_PENDING_MMS_URI] = submitted.messageUri.toString()
                    savedStateHandle[KEY_PENDING_THREAD_ID] = submitted.threadId
                }
            }
        }
    }

    private fun restorePendingMms() {
        if (savedStateHandle.get<String>(KEY_PENDING_MMS_URI) != null) {
            _state.update { it.copy(sending = true) }
        }
    }

    private fun onMmsSendCompleted(event: MmsSendEvent) {
        val waiting = savedStateHandle.get<String>(KEY_PENDING_MMS_URI) ?: return
        if (!urisMatch(event.messageUri, waiting)) return
        savedStateHandle.remove<String>(KEY_PENDING_MMS_URI)
        val threadId = savedStateHandle.get<Long>(KEY_PENDING_THREAD_ID)?.takeIf { it != 0L }
        savedStateHandle.remove<Long>(KEY_PENDING_THREAD_ID)
        when (event.verdict) {
            MmsSendVerdict.Sent -> _state.update {
                it.copy(
                    sending = false,
                    sendError = null,
                    sentThreadId = threadId,
                    showSentToast = threadId != null,
                    recipient = "",
                    body = "",
                )
            }
            MmsSendVerdict.Unconfirmed -> _state.update {
                it.copy(
                    sending = false,
                    sendError = null,
                    sentThreadId = threadId,
                    showSentToast = false,
                    recipient = "",
                    body = "",
                )
            }
            MmsSendVerdict.Failed -> _state.update {
                it.copy(
                    sending = false,
                    sendError = "Message could not be sent.",
                )
            }
        }
    }

    private data class PendingCompose(val recipient: String, val body: String)

    companion object {
        const val DISABLED_REASON = "Set Pinot Rouge as your default texting app to send."
        const val TOAST_SENT = "Message sent."
        const val TITLE = "New message"
        const val LABEL_TO = "To"
        const val PLACEHOLDER_TO = "Name or number"
        const val LABEL_MESSAGE = "Message"
        const val PLACEHOLDER_MESSAGE = "Write something"
        const val SEND = "Send"
        private const val KEY_PENDING_MMS_URI = "pending_mms_uri"
        private const val KEY_PENDING_THREAD_ID = "pending_thread_id"

        /** Split To field into distinct recipients (comma / semicolon / newline). */
        fun parseRecipients(raw: String): List<String> =
            raw.split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        private fun urisMatch(eventUri: Uri?, waiting: String): Boolean {
            if (eventUri == null) return false
            if (eventUri.toString() == waiting) return true
            val waitingUri = runCatching { Uri.parse(waiting) }.getOrNull() ?: return false
            return runCatching {
                ContentUris.parseId(eventUri) == ContentUris.parseId(waitingUri)
            }.getOrDefault(false)
        }
    }
}
