package com.pinotrouge.messaging.ui.filtered

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.room.HeldMessageEntity
import com.pinotrouge.messaging.data.room.TransportKind
import com.pinotrouge.messaging.di.ApplicationScope
import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import com.pinotrouge.messaging.ui.components.BATCH_DELETE_UNDO_MS
import com.pinotrouge.messaging.ui.components.BatchSelection
import com.pinotrouge.messaging.ui.media.MmsTile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class HeldMessageUi(
    val id: String,
    val sender: String,
    val timeLabel: String,
    val preview: String,
    val reason: String,
    val body: String,
    val ruleId: String,
    val ruleName: String,
    val tiles: List<MmsTile> = emptyList(),
)

data class FilteredGroup(
    val ruleId: String,
    val label: String,
    val items: List<HeldMessageUi>,
) {
    val count: Int get() = items.size
}

data class FilteredUiState(
    val groups: List<FilteredGroup> = emptyList(),
    val heldCount: Int = 0,
    val toastMessage: String? = null,
    val showDeleteAllConfirm: Boolean = false,
    val selectionActive: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val selectedCount: Int = 0,
    val showBatchDeleteConfirm: Boolean = false,
    val undoToastCount: Int = 0,
    /** True when the toast is the batch-delete undo (vs a short status toast). */
    val undoToastActive: Boolean = false,
)

/**
 * Quarantine screen state. Actions call [QuarantineRepository] only —
 * never reimplement quarantine table rows.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class FilteredViewModel @Inject constructor(
    private val quarantineRepository: QuarantineRepository,
    private val ruleRepository: RuleRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val toastMessage = MutableStateFlow<String?>(null)
    private val showDeleteAllConfirm = MutableStateFlow(false)
    private val selection = MutableStateFlow(BatchSelection<String>())
    private val pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    private val showBatchDeleteConfirm = MutableStateFlow(false)
    private val undoToastCount = MutableStateFlow(0)

    @Volatile
    private var pendingDeleteJob: Job? = null

    /**
     * Held count for the nav badge. Shell can also use
     * [QuarantineRepository.observeHeldCount] directly.
     */
    val heldCount: StateFlow<Int> = quarantineRepository.observeHeldCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val mediaByHeldId = quarantineRepository.observeHeld().mapLatest { held ->
        buildMap {
            for (entity in held) {
                if (entity.transportKind != TransportKind.MMS) continue
                put(
                    entity.id,
                    quarantineRepository.heldMedia(entity.id).mapIndexedNotNull { seq, ref ->
                        ref.toMmsTile(entity.id, seq)
                    },
                )
            }
        }
    }

    val uiState: StateFlow<FilteredUiState> = combine(
        quarantineRepository.observeHeld(),
        ruleRepository.observeRules(),
        ruleRepository.observeStats(),
        toastMessage,
        showDeleteAllConfirm,
    ) { held, rules, stats, toast, confirm ->
        HeldSlice(held, rules, stats.map { it.ruleId }.toSet(), toast, confirm)
    }.let { heldFlow ->
        combine(heldFlow, mediaByHeldId) { slice, media ->
            slice.copy(mediaById = media)
        }
    }.let { heldFlow ->
        combine(
            heldFlow,
            selection,
            pendingDeleteIds,
            showBatchDeleteConfirm,
            undoToastCount,
        ) { heldSlice, sel, pending, batchConfirm, undoCount ->
            val namesById = heldSlice.rules.associate { it.id to it.name }
            val visibleHeld = heldSlice.held.filter { it.id !in pending }
            val uiItems = visibleHeld.map {
                it.toUi(
                    namesById = namesById,
                    statsRuleIds = heldSlice.statsRuleIds,
                    tiles = heldSlice.mediaById[it.id].orEmpty(),
                )
            }
            val groups = uiItems
                .groupBy { it.ruleId }
                .map { (ruleId, items) ->
                    FilteredGroup(
                        ruleId = ruleId,
                        label = items.first().ruleName,
                        items = items,
                    )
                }
                .sortedByDescending { group ->
                    visibleHeld.filter { it.ruleId == group.ruleId }
                        .maxOfOrNull { it.heldAt } ?: 0L
                }
            FilteredUiState(
                groups = groups,
                heldCount = visibleHeld.size,
                toastMessage = heldSlice.toast,
                showDeleteAllConfirm = heldSlice.deleteAllConfirm,
                selectionActive = sel.active,
                selectedIds = sel.selected,
                selectedCount = sel.count,
                showBatchDeleteConfirm = batchConfirm,
                undoToastCount = undoCount,
                undoToastActive = undoCount > 0,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FilteredUiState(),
    )

    fun dismissToast() {
        toastMessage.value = null
        // If this was the undo toast dismissing after timeout, leave the job alone.
        if (undoToastCount.value > 0) {
            undoToastCount.value = 0
        }
    }

    fun startSelection(id: String) {
        selection.value = BatchSelection<String>().enter(id)
    }

    fun toggleSelection(id: String) {
        selection.update { it.toggle(id) }
    }

    fun exitSelection() {
        selection.value = BatchSelection()
        showBatchDeleteConfirm.value = false
    }

    fun requestBatchDelete() {
        if (selection.value.selected.isEmpty()) return
        showBatchDeleteConfirm.value = true
    }

    fun dismissBatchDeleteConfirm() {
        showBatchDeleteConfirm.value = false
    }

    fun confirmBatchDelete() {
        val ids = selection.value.selected
        if (ids.isEmpty()) {
            showBatchDeleteConfirm.value = false
            return
        }
        showBatchDeleteConfirm.value = false
        selection.value = BatchSelection()
        commitPendingDeleteNow()
        pendingDeleteIds.value = ids
        undoToastCount.value = ids.size
        toastMessage.value = null
        var job: Job? = null
        job = applicationScope.launch {
            delay(BATCH_DELETE_UNDO_MS)
            ids.forEach { quarantineRepository.deleteHeld(it) }
            if (pendingDeleteIds.value == ids) {
                pendingDeleteIds.value = emptySet()
                undoToastCount.value = 0
            }
            if (pendingDeleteJob === job) pendingDeleteJob = null
        }
        pendingDeleteJob = job
    }

    fun undoPendingDelete() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteIds.value = emptySet()
        undoToastCount.value = 0
        toastMessage.value = null
    }

    fun requestDeleteAll() {
        if (uiState.value.heldCount == 0) return
        showDeleteAllConfirm.value = true
    }

    fun dismissDeleteAllConfirm() {
        showDeleteAllConfirm.value = false
    }

    fun confirmDeleteAll() {
        viewModelScope.launch {
            showDeleteAllConfirm.value = false
            quarantineRepository.deleteAllHeld()
            toastMessage.value = TOAST_DELETED_ALL
        }
    }

    private fun commitPendingDeleteNow() {
        val ids = pendingDeleteIds.value
        if (ids.isEmpty()) return
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteIds.value = emptySet()
        undoToastCount.value = 0
        applicationScope.launch {
            ids.forEach { quarantineRepository.deleteHeld(it) }
        }
    }

    private data class HeldSlice(
        val held: List<HeldMessageEntity>,
        val rules: List<com.pinotrouge.messaging.rules.Rule>,
        /** rule_stats ids — orphans prove a rule once existed (then was deleted). */
        val statsRuleIds: Set<String>,
        val toast: String?,
        val deleteAllConfirm: Boolean,
        val mediaById: Map<String, List<MmsTile>> = emptyMap(),
    )

    companion object {
        // Prototype exact strings (PinotPhone.dc.html 694–696, 699).
        const val TOAST_MOVED =
            "Moved to your inbox. Want a filter to stop holding this sender?"
        const val TOAST_DELETED = "Deleted."
        const val TOAST_DELETED_ALL = "Held messages deleted."
        const val TOAST_MOVE_FAILED =
            "Couldn't move to inbox. The message is still held."

        const val LABEL_UNKNOWN_FILTER = "Unknown filter"
        const val LABEL_DELETED_FILTER = "Deleted filter"
        const val LABEL_SYSTEM = "System"

        private val clockTime: DateTimeFormatter =
            DateTimeFormatter.ofPattern("H:mm", Locale.getDefault())
        private val weekday: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        private val monthDay: DateTimeFormatter =
            DateTimeFormatter.ofPattern("M/d", Locale.getDefault())

        /** Prototype-style labels: "10:41", "Yesterday", "Mon". */
        internal fun formatHeldTime(
            receivedAtMillis: Long,
            nowMillis: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String {
            val received = Instant.ofEpochMilli(receivedAtMillis).atZone(zone)
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val day = received.toLocalDate()
            return when {
                day == today -> received.format(clockTime)
                day == today.minusDays(1) -> "Yesterday"
                day.isAfter(today.minusDays(7)) -> received.format(weekday)
                else -> received.format(monthDay)
            }
        }

        /**
         * Group heading for held messages.
         *
         * - Live rule → its name
         * - System hold → [LABEL_SYSTEM]
         * - Missing rule but [rule_stats] still has the id → [LABEL_DELETED_FILTER]
         *   (delete leaves stats rows on purpose; that orphan is proof the rule existed)
         * - Missing rule and no stats → [LABEL_UNKNOWN_FILTER] (corruption / seed drift)
         */
        internal fun resolveRuleName(
            ruleId: String,
            namesById: Map<String, String>,
            statsRuleIds: Set<String> = emptySet(),
        ): String = when {
            namesById[ruleId] != null -> namesById.getValue(ruleId)
            ruleId == IncomingMessagePipeline.RULE_ID_SYSTEM -> LABEL_SYSTEM
            ruleId in statsRuleIds -> LABEL_DELETED_FILTER
            else -> LABEL_UNKNOWN_FILTER
        }

        private fun HeldMessageEntity.toUi(
            namesById: Map<String, String>,
            statsRuleIds: Set<String>,
            tiles: List<MmsTile>,
        ): HeldMessageUi =
            HeldMessageUi(
                id = id,
                sender = sender,
                timeLabel = formatHeldTime(receivedAt),
                preview = heldPreviewText(body, tiles),
                reason = reason,
                body = body,
                ruleId = ruleId,
                ruleName = resolveRuleName(ruleId, namesById, statsRuleIds),
                tiles = tiles,
            )
    }
}
