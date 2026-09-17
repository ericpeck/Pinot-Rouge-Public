package com.pinotrouge.messaging.ui.inbox

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.prefs.AppSettings
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.ArchiveRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.data.telephony.SmsThread
import com.pinotrouge.messaging.di.ApplicationScope
import com.pinotrouge.messaging.notify.OtpClipboard
import com.pinotrouge.messaging.sms.SmsRoleManager
import com.pinotrouge.messaging.ui.components.BATCH_DELETE_UNDO_MS
import com.pinotrouge.messaging.ui.components.BatchSelection
import com.pinotrouge.messaging.ui.components.ConversationDeleteSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class InboxThreadUi(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val initials: String,
    val preview: String,
    val timeLabel: String,
    val dateMillis: Long,
    val unread: Boolean,
    val category: ThreadCategory,
    /** Detected OTP in the latest snippet, if any. */
    val otpCode: String?,
    /** Multi-recipient thread — drives users-three avatar and people pill. */
    val isGroup: Boolean = false,
    /** Other parties only (not "you"); used for the `{n} people` pill. */
    val participantCount: Int = 0,
)

data class InboxUiState(
    val threads: List<InboxThreadUi> = emptyList(),
    val filteredThreads: List<InboxThreadUi> = emptyList(),
    val selectedChip: InboxChip = InboxChip.All,
    val unreadCount: Int = 0,
    val showOtpCopy: Boolean = true,
    /** Thread id whose OTP was just copied; drives "Copied N" label. */
    val copiedThreadId: Long? = null,
    val isLoading: Boolean = true,
    /**
     * When false, filtering is inert — show the non-dismissible preview banner.
     * Live from [SmsRoleManager], not a stored preference.
     */
    val roleHeld: Boolean = true,
    /**
     * Runtime [android.Manifest.permission.READ_SMS]. Without it the provider
     * is unreadable — never treat that as a real empty inbox.
     */
    val canReadMessages: Boolean = true,
    /** Email-style multi-select. */
    val selectionActive: Boolean = false,
    val selectedThreadIds: Set<Long> = emptySet(),
    val selectedCount: Int = 0,
    /** Threads hidden pending the real provider delete (undo window). */
    val pendingDeleteCount: Int = 0,
    val showDeleteConfirm: Boolean = false,
    /** Non-zero while an undo toast (delete or archive) is active. */
    val undoToastCount: Int = 0,
    /** When true, the undo toast is for archive (not delete). */
    val undoIsArchive: Boolean = false,
    /**
     * Still-held messages held in the last 7 days. Drives the quiet held line;
     * hidden when zero. Not the lifetime quarantine total.
     */
    val heldThisWeekCount: Int = 0,
    /** Provider has older conversations than [threads]; scroll-driven load-more. */
    val hasMoreThreads: Boolean = false,
)

/**
 * Pure decision for what the inbox list body should render.
 * Keeps "no messages" distinct from "cannot read messages".
 */
enum class InboxListBody {
    Loading,
    Threads,
    Empty,
    EmptyFilter,
    NeedsPermission,
}

fun resolveInboxListBody(
    isLoading: Boolean,
    canReadMessages: Boolean,
    filteredEmpty: Boolean,
    hasAnyThreads: Boolean,
    pendingDeleteCount: Int = 0,
): InboxListBody = when {
    // First load only. A populated list keeps its rows while a reload runs —
    // resume used to flash the empty Spacer over existing threads.
    isLoading && !hasAnyThreads -> InboxListBody.Loading
    !canReadMessages -> InboxListBody.NeedsPermission
    // Mid-undo-window the visible list can be empty; stashed rows still count.
    filteredEmpty && pendingDeleteCount > 0 -> InboxListBody.Threads
    filteredEmpty && hasAnyThreads -> InboxListBody.EmptyFilter
    filteredEmpty -> InboxListBody.Empty
    else -> InboxListBody.Threads
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val contactsRepository: ContactsRepository,
    private val settingsRepository: SettingsRepository,
    private val quarantineRepository: QuarantineRepository,
    private val archiveRepository: ArchiveRepository,
    private val otpClipboard: OtpClipboard,
    private val smsRoleManager: SmsRoleManager,
    private val smsRepository: SmsRepository,
    private val conversationDeleteSession: ConversationDeleteSession,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val rawThreads = MutableStateFlow<List<InboxThreadUi>>(emptyList())
    private val selectedChip = MutableStateFlow(InboxChip.All)
    private val copiedThreadId = MutableStateFlow<Long?>(null)
    private val isLoading = MutableStateFlow(true)
    private val hasMoreThreads = MutableStateFlow(false)
    private val canReadMessages = MutableStateFlow(smsRepository.hasReadSmsPermission())
    private val selection = MutableStateFlow(BatchSelection<Long>())
    private val pendingDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    private val showDeleteConfirm = MutableStateFlow(false)
    private val undoToast = MutableStateFlow<UndoToast?>(null)

    /** Serialises publishes into [rawThreads] so a slow [refresh] cannot overwrite a newer load. */
    private val publishLock = Mutex()
    private val loadEpoch = AtomicInteger(0)
    /** Last epoch that actually wrote [rawThreads]. Guarded by [publishLock]. */
    private var lastPublishedEpoch = 0
    /**
     * Epoch of the in-flight load that turned the spinner on; 0 if none.
     * Guarded by [publishLock].
     */
    private var loadingOwnerEpoch = 0
    /** Opens the provider observer only after the init [refresh] has published. */
    private val firstRefreshDone = MutableSharedFlow<Unit>(replay = 1)

    @Volatile
    private var pendingDeleteJob: Job? = null

    @Volatile
    private var pendingArchiveUndoJob: Job? = null

    private val loadMoreInFlight = AtomicBoolean(false)

    // Search lives on the Search screen; chips are the only inbox list filter.
    private val listState = combine(
        rawThreads,
        selectedChip,
        copiedThreadId,
        isLoading,
        hasMoreThreads,
    ) { threads, chip, copied, loading, more ->
        ListSlice(
            threads = threads,
            chip = chip,
            copied = copied,
            loading = loading,
            hasMoreThreads = more,
        )
    }

    /**
     * Quiet reload on a provider change so an arriving message appears in the
     * list without a resume. Folded into [uiState] so [SharingStarted.WhileSubscribed]
     * starts and stops it with the screen. The observer is gated on the init
     * [refresh] and its seed is dropped, so the 250 ms debounce seed is not a
     * second full read on open.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val listStateWithProvider = combine(
        listState,
        firstRefreshDone
            .take(1)
            .flatMapLatest {
                smsRepository.observeSmsChanges()
                    .drop(1)
                    .debounce(MessageRepository.SMS_CHANGE_DEBOUNCE_MS)
                    .mapLatest { replaceRawThreads(announceLoading = false) }
            }
            .onStart { emit(Unit) },
    ) { slice, _ -> slice }

    private val selectionSlice = combine(
        selection,
        pendingDeleteIds,
        showDeleteConfirm,
        undoToast,
        conversationDeleteSession.pending,
    ) { sel, pending, confirm, toast, convPending ->
        SelectionSlice(sel, pending, confirm, toast, convPending)
    }

    /**
     * Rolling 7-day window bound at subscribe time. Room re-emits on table
     * changes so the line appears/disappears without polling.
     */
    private val heldThisWeekCount = quarantineRepository.observeHeldCountSince(
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HELD_WEEK_DAYS),
    )

    private val selectionHeldArchive = combine(
        selectionSlice,
        heldThisWeekCount,
        archiveRepository.observeArchivedIds(),
    ) { sel, heldCount, archivedIds ->
        Triple(sel, heldCount, archivedIds)
    }

    val uiState: StateFlow<InboxUiState> = combine(
        listStateWithProvider,
        settingsRepository.settings,
        smsRoleManager.roleHeld,
        canReadMessages,
        selectionHeldArchive,
    ) { slice, settings, roleHeld, canRead, heldArchive ->
        val (selSlice, heldCount, archivedIds) = heldArchive
        buildUiState(slice, settings, roleHeld, canRead, selSlice, heldCount, archivedIds)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(WHILE_SUBSCRIBED_MS),
        InboxUiState(
            roleHeld = smsRoleManager.isRoleHeld(),
            canReadMessages = smsRepository.hasReadSmsPermission(),
        ),
    )

    init {
        smsRoleManager.refresh()
        refreshPermissions()
        refresh()
    }

    /**
     * Reloads threads and re-checks read permission.
     *
     * [quiet] is the resume path: skip the loading flag when rows are already
     * on screen so Chats does not blank. Leave it false for the role and
     * permission launchers, where empty-to-populated still needs a spinner.
     */
    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            replaceRawThreads(announceLoading = !quiet || rawThreads.value.isEmpty())
        }
    }

    /**
     * Single path that writes [rawThreads]. [refresh] and the provider
     * observer both call this. A load publishes when it is newer than
     * anything already published, so a cancelled later start cannot
     * invalidate an earlier finish. The load that raised [isLoading]
     * always lowers it on the way out, including on cancellation.
     */
    private suspend fun replaceRawThreads(announceLoading: Boolean) {
        val epoch = loadEpoch.incrementAndGet()
        if (announceLoading) {
            publishLock.withLock {
                loadingOwnerEpoch = epoch
                isLoading.value = true
            }
        }
        try {
            // Re-check before every load — permissions can change while we are
            // backgrounded (Settings, or the system role grant).
            canReadMessages.value = smsRepository.hasReadSmsPermission()
            val requestLimit = maxOf(THREAD_PAGE_SIZE, rawThreads.value.size)
            val threads = if (canReadMessages.value) {
                loadThreads(requestLimit)
            } else {
                emptyList()
            }
            publishLock.withLock {
                if (epoch <= lastPublishedEpoch) return@withLock
                lastPublishedEpoch = epoch
                rawThreads.value = threads
                hasMoreThreads.value = canReadMessages.value &&
                    threads.size >= requestLimit &&
                    requestLimit >= THREAD_PAGE_SIZE
                firstRefreshDone.tryEmit(Unit)
            }
        } finally {
            if (announceLoading) {
                // mapLatest / WhileSubscribed cancel in-flight work; lock()
                // itself is cancellable, so the clear has to outrun that.
                withContext(NonCancellable) {
                    publishLock.withLock {
                        if (loadingOwnerEpoch == epoch) {
                            loadingOwnerEpoch = 0
                            isLoading.value = false
                        }
                    }
                }
            }
        }
    }

    /** Re-check ROLE_SMS after resume or the system role picker. */
    fun refreshRole() {
        smsRoleManager.refresh()
    }

    /**
     * Re-read runtime SMS/contacts grants. Call on resume and after a
     * permission launcher returns so the list repopulates without a restart.
     */
    fun refreshPermissions() {
        canReadMessages.value = smsRepository.hasReadSmsPermission()
    }

    /**
     * Next older page of conversations. Scroll-driven; no extra chrome.
     */
    fun loadMoreThreads() {
        if (isLoading.value || !hasMoreThreads.value) return
        if (!loadMoreInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                canReadMessages.value = smsRepository.hasReadSmsPermission()
                if (!canReadMessages.value) {
                    hasMoreThreads.value = false
                    return@launch
                }
                val oldest = rawThreads.value.lastOrNull() ?: return@launch
                val page = runCatching {
                    messageRepository.getThreadsOlderThan(
                        beforeDateMillis = oldest.dateMillis,
                        limit = THREAD_PAGE_SIZE,
                    )
                }.getOrDefault(emptyList())
                val enriched = page.map { enrich(it) }
                publishLock.withLock {
                    val existing = rawThreads.value.map { it.threadId }.toSet()
                    val unique = enriched.filter { it.threadId !in existing }
                    if (unique.isEmpty()) {
                        hasMoreThreads.value = false
                    } else {
                        rawThreads.value = rawThreads.value + unique
                        hasMoreThreads.value = page.size >= THREAD_PAGE_SIZE
                    }
                }
            } finally {
                loadMoreInFlight.set(false)
            }
        }
    }

    /**
     * Permission grants do not notify the contacts [android.database.ContentObserver],
     * so a pre-grant "not a contact" miss would otherwise stick for the process.
     */
    fun clearContactsCache() {
        contactsRepository.clearCache()
    }

    fun roleRequestIntent() = smsRoleManager.createRequestRoleIntent()

    /**
     * Permissions to request for preview-mode browsing. Empty when the role
     * is held — those grants already come with ROLE_SMS; do not re-prompt.
     */
    fun previewPermissionsToRequest(): Array<String> {
        if (smsRoleManager.isRoleHeld()) return emptyArray()
        return smsRepository.previewReadPermissions()
    }

    fun selectChip(chip: InboxChip) {
        selectedChip.value = chip
    }

    /**
     * Copies [code] via [OtpClipboard] (sensitive + best-effort WorkManager clear).
     */
    fun copyOtp(threadId: Long, code: String) {
        otpClipboard.copyCode(code)
        copiedThreadId.value = threadId
    }

    /** Long-press — only when we can actually delete (ROLE_SMS). */
    fun startSelection(threadId: Long) {
        if (!smsRoleManager.isRoleHeld()) return
        selection.value = BatchSelection<Long>().enter(threadId)
    }

    fun toggleSelection(threadId: Long) {
        selection.update { it.toggle(threadId) }
    }

    fun exitSelection() {
        selection.value = BatchSelection()
        showDeleteConfirm.value = false
    }

    fun requestDeleteSelected() {
        if (!smsRoleManager.isRoleHeld()) return
        if (selection.value.selected.isEmpty()) return
        showDeleteConfirm.value = true
    }

    fun dismissDeleteConfirm() {
        showDeleteConfirm.value = false
    }

    /**
     * Multi-select archive: Room write only (Telephony untouched). Immediate
     * so Search tags correctly; 5s toast undoes via [ArchiveRepository.unarchive].
     * Same undo feel as batch delete without a third session type.
     */
    fun archiveSelected() {
        val ids = selection.value.selected
        if (ids.isEmpty()) return
        selection.value = BatchSelection()
        showDeleteConfirm.value = false

        // Settle any pending destructive work first.
        commitPendingDeleteNow()
        conversationDeleteSession.commitNow()
        clearArchiveUndoWindow()

        viewModelScope.launch {
            archiveRepository.archive(ids)
        }
        undoToast.value = UndoToast(
            count = ids.size,
            kind = UndoKind.Archive,
            archiveIds = ids,
        )
        var job: Job? = null
        job = applicationScope.launch {
            delay(BATCH_DELETE_UNDO_MS)
            if (undoToast.value?.kind == UndoKind.Archive &&
                undoToast.value?.archiveIds == ids
            ) {
                undoToast.value = null
            }
            if (pendingArchiveUndoJob === job) pendingArchiveUndoJob = null
        }
        pendingArchiveUndoJob = job
    }

    /**
     * Confirm: hide rows in UI only, start 5s app-scoped job for the real delete.
     * Undo cancels the job; nothing is written until the window expires.
     */
    fun confirmDeleteSelected() {
        if (!smsRoleManager.isRoleHeld()) {
            showDeleteConfirm.value = false
            return
        }
        val ids = selection.value.selected
        if (ids.isEmpty()) {
            showDeleteConfirm.value = false
            return
        }
        showDeleteConfirm.value = false
        selection.value = BatchSelection()

        // Commit any prior pending batch / conversation-from-thread / archive toast.
        commitPendingDeleteNow()
        conversationDeleteSession.commitNow()
        clearArchiveUndoWindow()

        pendingDeleteIds.value = ids
        undoToast.value = UndoToast(count = ids.size, kind = UndoKind.Delete)

        var job: Job? = null
        job = applicationScope.launch {
            delay(BATCH_DELETE_UNDO_MS)
            performProviderDeletes(ids)
            // Only clear if we are still the active batch.
            if (pendingDeleteIds.value == ids) {
                pendingDeleteIds.value = emptySet()
                undoToast.value = null
            }
            // Compare-and-clear: never null a newer batch's handle.
            if (pendingDeleteJob === job) pendingDeleteJob = null
        }
        pendingDeleteJob = job
    }

    fun undoPendingDelete() {
        // Prefer conversation-from-thread session if its toast is showing.
        val conv = conversationDeleteSession.pending.value
        if (conv != null && conv.toastVisible) {
            conversationDeleteSession.undo()
            return
        }
        val toast = undoToast.value
        if (toast != null && toast.kind == UndoKind.Archive) {
            pendingArchiveUndoJob?.cancel()
            pendingArchiveUndoJob = null
            val ids = toast.archiveIds
            undoToast.value = null
            viewModelScope.launch {
                archiveRepository.unarchive(ids)
            }
            return
        }
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteIds.value = emptySet()
        undoToast.value = null
    }

    fun dismissUndoToast() {
        // UI only — the application-scoped job still owns the real delete / archive.
        val conv = conversationDeleteSession.pending.value
        if (conv != null && conv.toastVisible) {
            conversationDeleteSession.dismissToast()
            return
        }
        undoToast.value = null
    }

    private fun clearArchiveUndoWindow() {
        pendingArchiveUndoJob?.cancel()
        pendingArchiveUndoJob = null
        if (undoToast.value?.kind == UndoKind.Archive) {
            undoToast.value = null
        }
    }

    private fun commitPendingDeleteNow() {
        val ids = pendingDeleteIds.value
        if (ids.isEmpty()) return
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteIds.value = emptySet()
        undoToast.value = null
        applicationScope.launch {
            performProviderDeletes(ids)
        }
    }

    private suspend fun performProviderDeletes(ids: Set<Long>) {
        ids.forEach { id ->
            when (val result = messageRepository.deleteThread(id)) {
                is SmsRepository.WriteResult.Success -> Unit
                is SmsRepository.WriteResult.RoleNotHeld ->
                    Log.e(TAG, "deleteThread($id): ROLE_SMS not held; message still in provider")
                is SmsRepository.WriteResult.Failed ->
                    Log.e(TAG, "deleteThread($id): provider write failed; message still in provider", result.cause)
            }
        }
    }

    private companion object {
        const val TAG = "InboxViewModel"
        const val HELD_WEEK_DAYS = 7L
        const val THREAD_PAGE_SIZE = 200
        /** Must match [SharingStarted.WhileSubscribed] so the observer stops with the UI. */
        const val WHILE_SUBSCRIBED_MS = 5_000L
    }

    private suspend fun loadThreads(limit: Int): List<InboxThreadUi> {
        val threads = messageRepository.getThreads(limit = limit)
        return threads.map { enrich(it) }
    }

    private suspend fun enrich(thread: SmsThread): InboxThreadUi {
        val address = thread.address.orEmpty()
        val snippet = thread.snippet.orEmpty()
        val participants = thread.participantAddresses
        val isGroup = thread.isGroup
        // Group: known if *any* participant is a contact (Filter Rule Spec §1).
        val isKnown = when {
            isGroup -> {
                participants.any { contactsRepository.isKnownContact(it) }
            }
            address.isNotBlank() -> contactsRepository.isKnownContact(address)
            else -> false
        }
        val displayName = if (isGroup) {
            groupDisplayName(participants)
        } else {
            val contactName = if (address.isNotBlank()) {
                contactsRepository.resolveDisplayName(address)
            } else {
                null
            }
            when {
                !contactName.isNullOrBlank() -> contactName
                address.isNotBlank() -> address
                else -> "Unknown"
            }
        }
        val category = ThreadCategoryClassifier.classify(
            address = address,
            isKnownContact = isKnown,
            bodyOrSnippet = snippet,
        )
        val otp = OtpCodeExtractor.extract(snippet)
        return InboxThreadUi(
            threadId = thread.threadId,
            address = address,
            displayName = displayName,
            initials = InboxFormat.initials(displayName, category),
            preview = snippet,
            timeLabel = InboxFormat.formatTime(thread.date),
            dateMillis = thread.date,
            unread = thread.unreadCount > 0,
            category = category,
            otpCode = otp,
            isGroup = isGroup,
            participantCount = participants.size,
        )
    }

    /** Contact name when saved, otherwise the number — joined for the row title. */
    private suspend fun groupDisplayName(participants: List<String>): String {
        if (participants.isEmpty()) return "Group"
        val labels = participants.map { address ->
            contactsRepository.resolveDisplayName(address)?.takeIf { it.isNotBlank() }
                ?: address
        }
        return labels.joinToString(", ")
    }

    private data class ListSlice(
        val threads: List<InboxThreadUi>,
        val chip: InboxChip,
        val copied: Long?,
        val loading: Boolean,
        val hasMoreThreads: Boolean,
    )

    private data class SelectionSlice(
        val selection: BatchSelection<Long>,
        val pending: Set<Long>,
        val showConfirm: Boolean,
        val undoToast: UndoToast?,
        val conversationPending: ConversationDeleteSession.Pending?,
    )

    private enum class UndoKind { Delete, Archive }

    private data class UndoToast(
        val count: Int,
        val kind: UndoKind,
        val archiveIds: Set<Long> = emptySet(),
    )

    private fun buildUiState(
        slice: ListSlice,
        settings: AppSettings,
        roleHeld: Boolean,
        canRead: Boolean,
        sel: SelectionSlice,
        heldThisWeekCount: Int,
        archivedIds: Set<Long>,
    ): InboxUiState {
        val convId = sel.conversationPending?.threadId
        val pendingIds = if (convId != null) sel.pending + convId else sel.pending
        // Chats list = provider threads minus pending deletes minus archived.
        val visible = slice.threads.filter {
            it.threadId !in pendingIds && it.threadId !in archivedIds
        }
        // Header follows the tab badge during the undo window: count from the
        // full provider-backed list (including pending rows), not the optimistic
        // visible list. Badge still reads the provider until the real delete.
        // Archived threads still contribute to unread until deleted for real.
        val unread = slice.threads
            .filter { it.threadId !in archivedIds }
            .count { it.unread }
        // Category chips only — full-text search is the Search screen.
        val filtered = visible.filter { slice.chip.matches(it.category) }
        // Conversation-from-thread undo toast takes priority over batch toast.
        val toastCount = when {
            sel.conversationPending?.toastVisible == true -> 1
            else -> sel.undoToast?.count ?: 0
        }
        val toastIsArchive = sel.conversationPending?.toastVisible != true &&
            sel.undoToast?.kind == UndoKind.Archive
        return InboxUiState(
            threads = visible,
            filteredThreads = filtered,
            selectedChip = slice.chip,
            unreadCount = unread,
            showOtpCopy = settings.preserveOtps && !sel.selection.active,
            copiedThreadId = slice.copied,
            isLoading = slice.loading,
            roleHeld = roleHeld,
            canReadMessages = canRead,
            selectionActive = sel.selection.active,
            selectedThreadIds = sel.selection.selected,
            selectedCount = sel.selection.count,
            pendingDeleteCount = pendingIds.size,
            showDeleteConfirm = sel.showConfirm,
            undoToastCount = toastCount,
            undoIsArchive = toastIsArchive,
            heldThisWeekCount = heldThisWeekCount,
            hasMoreThreads = slice.hasMoreThreads,
        )
    }
}
