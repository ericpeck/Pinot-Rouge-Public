package com.pinotrouge.messaging.ui.search

import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.repo.ArchiveRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.room.HeldMessageEntity
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.data.telephony.SmsThread
import com.pinotrouge.messaging.ui.inbox.InboxFormat
import com.pinotrouge.messaging.ui.inbox.OtpCodeExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchHitUi(
    val kind: SearchHitKind,
    val threadId: Long? = null,
    val heldId: String? = null,
    val name: String,
    val timeLabel: String,
    val snippet: String,
    @DrawableRes val glyphRes: Int,
    val tag: SearchHitTag?,
)

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val conversationHits: List<SearchHitUi> = emptyList(),
    val heldHits: List<SearchHitUi> = emptyList(),
    /** Archived conversation hits — tagged "Archived", still openable. */
    val archivedHits: List<SearchHitUi> = emptyList(),
    val canReadMessages: Boolean = true,
    val isSearching: Boolean = false,
) {
    val resultsBody: SearchResultsBody
        get() = resolveSearchResultsBody(
            queryBlank = query.isBlank(),
            canReadMessages = canReadMessages,
            conversationHitCount = conversationHits.size + archivedHits.size,
            heldHitCount = heldHits.size,
        )
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val smsRepository: SmsRepository,
    private val contactsRepository: ContactsRepository,
    private val quarantineRepository: QuarantineRepository,
    private val archiveRepository: ArchiveRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val canReadMessages = MutableStateFlow(smsRepository.hasReadSmsPermission())
    private val suggestions = MutableStateFlow<List<String>>(emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searchResults = combine(
        query.debounce(QUERY_DEBOUNCE_MS).distinctUntilChanged(),
        canReadMessages,
        quarantineRepository.observeHeld(),
        archiveRepository.observeArchivedIds(),
        smsRepository.observeSmsChanges().debounce(MessageRepository.SMS_CHANGE_DEBOUNCE_MS),
    ) { q, canRead, held, archivedIds, _ ->
        SearchInputs(q, canRead, held, archivedIds)
    }.flatMapLatest { inputs ->
        flow {
            if (inputs.query.isBlank()) {
                emit(SearchOutput(emptyList(), emptyList(), emptyList(), isSearching = false))
                return@flow
            }
            emit(SearchOutput(emptyList(), emptyList(), emptyList(), isSearching = true))
            val output = withContext(Dispatchers.IO) {
                runSearch(inputs)
            }
            emit(output)
        }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<SearchUiState> = combine(
        query,
        suggestions,
        canReadMessages,
        searchResults,
    ) { q, sugg, canRead, results ->
        SearchUiState(
            query = q,
            suggestions = sugg,
            conversationHits = results.conversationHits,
            heldHits = results.heldHits,
            archivedHits = results.archivedHits,
            canReadMessages = canRead,
            isSearching = results.isSearching,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SearchUiState(canReadMessages = smsRepository.hasReadSmsPermission()),
    )

    init {
        refreshPermissions()
        viewModelScope.launch {
            reloadSuggestions()
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    fun applySuggestion(label: String) {
        query.value = label
    }

    /**
     * Re-check READ_SMS. Call on resume and after a permission launcher returns
     * so search can leave NeedsPermission without a restart.
     */
    fun refreshPermissions() {
        canReadMessages.value = smsRepository.hasReadSmsPermission()
        viewModelScope.launch { reloadSuggestions() }
    }

    fun previewPermissionsToRequest(): Array<String> =
        smsRepository.previewReadPermissions()

    private suspend fun reloadSuggestions() {
        val canRead = smsRepository.hasReadSmsPermission()
        if (!canRead) {
            suggestions.value = emptyList()
            return
        }
        suggestions.value = withContext(Dispatchers.IO) {
            buildSuggestionsFromProvider()
        }
    }

    private suspend fun buildSuggestionsFromProvider(): List<String> {
        val threads = runCatching { messageRepository.getThreads(limit = SUGGESTION_THREAD_LIMIT) }
            .getOrDefault(emptyList())
        val names = ArrayList<String>(threads.size)
        for (thread in threads) {
            val address = thread.address.orEmpty()
            val contactName = if (address.isNotBlank()) {
                contactsRepository.resolveDisplayName(address)
            } else {
                null
            }
            val label = when {
                !contactName.isNullOrBlank() -> contactName
                !thread.address.isNullOrBlank() -> thread.address
                else -> null
            }
            if (!label.isNullOrBlank()) names.add(label)
        }
        val recent = runCatching { smsRepository.getRecentInbox(limit = SUGGESTION_MESSAGE_LIMIT) }
            .getOrDefault(emptyList())
        val codes = recent.mapNotNull { OtpCodeExtractor.extract(it.body) }
        return buildJumpToSuggestions(
            recentDisplayNames = names,
            recentCodes = codes,
            max = SUGGESTION_MAX,
        )
    }

    private suspend fun runSearch(inputs: SearchInputs): SearchOutput {
        val q = inputs.query.trim()
        if (q.isEmpty()) {
            return SearchOutput(emptyList(), emptyList(), emptyList(), isSearching = false)
        }

        // Held is Room-backed — always searchable (private; open does not mark read).
        val heldCandidates = inputs.held.map { it.toCandidate() }
        val heldMatches = matchHeld(q, heldCandidates).map { it.toUi() }

        if (!inputs.canRead) {
            // Cannot scan the provider — do not invent conversation results.
            return SearchOutput(
                conversationHits = emptyList(),
                heldHits = heldMatches,
                archivedHits = emptyList(),
                isSearching = false,
            )
        }

        val bodyByThreadHits = runCatching { smsRepository.searchMessageBodies(q) }
            .getOrDefault(emptyMap())
        val bodyByThread = bodyByThreadHits.mapValues { it.value.body }

        val recentThreads = runCatching {
            messageRepository.getThreads(limit = SEARCH_THREAD_LIMIT)
        }.getOrDefault(emptyList())
        val extraIds = LinkedHashSet<Long>().apply {
            addAll(inputs.archivedIds)
            addAll(bodyByThreadHits.keys)
        }
        val extraThreads = runCatching { messageRepository.getThreadsByIds(extraIds) }
            .getOrDefault(emptyList())

        val byId = LinkedHashMap<Long, SmsThread>()
        for (thread in recentThreads) byId[thread.threadId] = thread
        for (thread in extraThreads) byId.putIfAbsent(thread.threadId, thread)

        val candidates = ArrayList<ConversationCandidate>(byId.size + bodyByThreadHits.size)
        val seen = HashSet<Long>(byId.size)
        for (thread in byId.values) {
            seen.add(thread.threadId)
            candidates.add(thread.toCandidate(inputs.archivedIds))
        }
        for ((threadId, hit) in bodyByThreadHits) {
            if (threadId in seen) continue
            candidates.add(
                ConversationCandidate(
                    threadId = threadId,
                    address = "",
                    displayName = "Unknown",
                    snippet = hit.body,
                    dateMillis = hit.dateMillis,
                    isArchived = threadId in inputs.archivedIds,
                ),
            )
        }

        val allConversationMatches = matchConversations(q, candidates, bodyByThread)
        // Same matching path; isArchived splits conversation vs archive results.
        val conversationHits = allConversationMatches
            .filter { it.kind == SearchHitKind.Conversation }
            .map { it.toUi() }
        val archivedHits = allConversationMatches
            .filter { it.kind == SearchHitKind.Archived }
            .map { it.toUi() }

        return SearchOutput(
            conversationHits = conversationHits,
            heldHits = heldMatches,
            archivedHits = archivedHits,
            isSearching = false,
        )
    }

    private suspend fun SmsThread.toCandidate(archivedIds: Set<Long>): ConversationCandidate {
        val address = address.orEmpty()
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
        return ConversationCandidate(
            threadId = threadId,
            address = address,
            displayName = displayName,
            snippet = snippet.orEmpty(),
            dateMillis = date,
            isGroup = false,
            isArchived = threadId in archivedIds,
        )
    }

    private fun HeldMessageEntity.toCandidate(): HeldCandidate =
        HeldCandidate(
            id = id,
            sender = sender,
            body = body,
            dateMillis = receivedAt,
        )

    private fun SearchMatch.toUi(): SearchHitUi =
        SearchHitUi(
            kind = kind,
            threadId = threadId,
            heldId = heldId,
            name = name,
            timeLabel = InboxFormat.formatTime(dateMillis),
            snippet = snippet,
            glyphRes = glyph.toDrawableRes(),
            tag = tag,
        )

    private data class SearchInputs(
        val query: String,
        val canRead: Boolean,
        val held: List<HeldMessageEntity>,
        val archivedIds: Set<Long>,
    )

    private data class SearchOutput(
        val conversationHits: List<SearchHitUi>,
        val heldHits: List<SearchHitUi>,
        val archivedHits: List<SearchHitUi>,
        val isSearching: Boolean,
    )

    companion object {
        const val QUERY_DEBOUNCE_MS = 300L
        private const val SEARCH_THREAD_LIMIT = 200
        private const val SUGGESTION_THREAD_LIMIT = 40
        private const val SUGGESTION_MESSAGE_LIMIT = 40
        private const val SUGGESTION_MAX = 5
    }
}

@DrawableRes
private fun SearchHitGlyph.toDrawableRes(): Int = when (this) {
    SearchHitGlyph.User -> R.drawable.ic_user
    SearchHitGlyph.UsersThree -> R.drawable.ic_users_three
    SearchHitGlyph.Funnel -> R.drawable.ic_funnel_simple
}
