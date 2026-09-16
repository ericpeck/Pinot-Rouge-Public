package com.pinotrouge.messaging.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.repo.ArchiveRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.data.telephony.SmsThread
import com.pinotrouge.messaging.ui.inbox.InboxFormat
import com.pinotrouge.messaging.ui.inbox.ThreadCategoryClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArchiveRowUi(
    val threadId: Long,
    val displayName: String,
    val initials: String,
    val preview: String,
    val timeLabel: String,
)

data class ArchiveUiState(
    val rows: List<ArchiveRowUi> = emptyList(),
    val count: Int = 0,
    val loading: Boolean = true,
    val canReadMessages: Boolean = true,
)

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val archiveRepository: ArchiveRepository,
    private val messageRepository: MessageRepository,
    private val contactsRepository: ContactsRepository,
    private val smsRepository: SmsRepository,
) : ViewModel() {

    private val canRead = MutableStateFlow(smsRepository.hasReadSmsPermission())
    private val providerThreads = MutableStateFlow<List<SmsThread>>(emptyList())
    private val loading = MutableStateFlow(true)

    val uiState: StateFlow<ArchiveUiState> = combine(
        archiveRepository.observeArchivedIds(),
        providerThreads,
        canRead,
        loading,
    ) { archivedIds, threads, readable, isLoading ->
        val byId = threads.associateBy { it.threadId }
        val rows = archivedIds.mapNotNull { id ->
            byId[id]?.let { thread -> enrich(thread) }
        }.sortedByDescending { byId[it.threadId]?.date ?: 0L }
        ArchiveUiState(
            rows = rows,
            count = archivedIds.size,
            loading = isLoading,
            canReadMessages = readable,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ArchiveUiState(),
    )

    init {
        viewModelScope.launch {
            archiveRepository.observeArchivedIds().collect { ids ->
                reload(ids)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            reload(archiveRepository.observeArchivedIds().first())
        }
    }

    private suspend fun reload(archivedIds: Set<Long>) {
        loading.value = true
        canRead.value = smsRepository.hasReadSmsPermission()
        providerThreads.value = if (canRead.value) {
            runCatching { messageRepository.getThreadsByIds(archivedIds) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        loading.value = false
    }

    fun unarchive(threadId: Long) {
        viewModelScope.launch {
            archiveRepository.unarchive(threadId)
        }
    }

    private suspend fun enrich(thread: SmsThread): ArchiveRowUi {
        val address = thread.address.orEmpty()
        val snippet = thread.snippet.orEmpty()
        val isKnown = address.isNotBlank() && contactsRepository.isKnownContact(address)
        val contactName = if (address.isNotBlank()) {
            contactsRepository.resolveDisplayName(address)
        } else {
            null
        }
        val displayName = when {
            !contactName.isNullOrBlank() -> contactName
            address.isNotBlank() -> address
            else -> "Unknown"
        }
        val category = ThreadCategoryClassifier.classify(
            address = address,
            isKnownContact = isKnown,
            bodyOrSnippet = snippet,
        )
        return ArchiveRowUi(
            threadId = thread.threadId,
            displayName = displayName,
            initials = InboxFormat.initials(displayName, category),
            preview = snippet,
            timeLabel = InboxFormat.formatTime(thread.date),
        )
    }
}
