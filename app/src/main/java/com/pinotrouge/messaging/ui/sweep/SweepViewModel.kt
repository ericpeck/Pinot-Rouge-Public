package com.pinotrouge.messaging.ui.sweep

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.room.HeldBy
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.di.ApplicationScope
import com.pinotrouge.messaging.rules.RuleEngine
import com.pinotrouge.messaging.ui.components.BATCH_DELETE_UNDO_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SweepPhase {
    /** Loading inbox + rules (read-only). */
    Scanning,
    /** Preview results; provider and held_messages still untouched. */
    Preview,
    /** User confirmed; holding + deleting. */
    Moving,
    /** Done; optional undo toast. */
    Done,
}

data class SweepUiState(
    val phase: SweepPhase = SweepPhase.Scanning,
    val scanned: Int = 0,
    val wouldHold: Int = 0,
    val groups: List<InboxSweep.RuleGroup> = emptyList(),
    val matches: List<InboxSweep.Match> = emptyList(),
    val caughtFromContacts: Int = 0,
    val showReview: Boolean = false,
    val noEnabledRules: Boolean = false,
    val movedCount: Int = 0,
    val failedCount: Int = 0,
    /** Progress while [SweepPhase.Moving]: completed so far (0-based next index). */
    val moveProgress: Int = 0,
    val moveTotal: Int = 0,
    val undoToastVisible: Boolean = false,
    /** String resource id for scan/IO failure, resolved in the UI. */
    val errorMessageRes: Int? = null,
)

@HiltViewModel
class SweepViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val ruleRepository: RuleRepository,
    private val quarantineRepository: QuarantineRepository,
    private val settingsRepository: SettingsRepository,
    private val ruleEngine: RuleEngine,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SweepUiState())
    val uiState: StateFlow<SweepUiState> = _uiState.asStateFlow()

    private var scanResult: InboxSweep.Result? = null
    private var heldIdsForUndo: List<String> = emptyList()

    @Volatile
    private var undoJob: Job? = null

    init {
        startScan()
    }

    fun startScan() {
        viewModelScope.launch {
            _uiState.value = SweepUiState(phase = SweepPhase.Scanning)
            scanResult = null
            heldIdsForUndo = emptyList()
            try {
                val result = withContext(Dispatchers.IO) {
                    val rules = ruleRepository.getRules()
                    val enabled = rules.filter { it.enabled }
                    if (enabled.isEmpty()) {
                        return@withContext null to true
                    }
                    val settings = settingsRepository.settings.first()
                    val candidates = messageRepository.sweepInboxCandidates()
                    val scan = InboxSweep.scan(
                        candidates = candidates,
                        rules = rules,
                        neverFilterContacts = settings.neverFilterContacts,
                        engine = ruleEngine,
                    )
                    scan to false
                }
                val (scan, noRules) = result
                if (noRules) {
                    _uiState.value = SweepUiState(
                        phase = SweepPhase.Preview,
                        noEnabledRules = true,
                        scanned = 0,
                    )
                    return@launch
                }
                scanResult = scan
                _uiState.value = SweepUiState(
                    phase = SweepPhase.Preview,
                    scanned = scan!!.scanned,
                    wouldHold = scan.wouldHold,
                    groups = scan.groups,
                    matches = scan.matches,
                    caughtFromContacts = scan.caughtFromContacts,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "scan failed", t)
                _uiState.value = SweepUiState(
                    phase = SweepPhase.Preview,
                    errorMessageRes = R.string.sweep_scan_failed,
                )
            }
        }
    }

    fun showReview(show: Boolean) {
        _uiState.update { it.copy(showReview = show) }
    }

    fun confirmMove() {
        val scan = scanResult ?: return
        if (scan.matches.isEmpty()) return
        viewModelScope.launch {
            val total = scan.matches.size
            _uiState.update {
                it.copy(
                    phase = SweepPhase.Moving,
                    showReview = false,
                    errorMessageRes = null,
                    moveProgress = 0,
                    moveTotal = total,
                )
            }
            val heldIds = ArrayList<String>(total)
            var failed = 0
            withContext(Dispatchers.IO) {
                scan.matches.forEachIndexed { index, match ->
                    val held = quarantineRepository.holdOnArrival(
                        sender = match.sender,
                        body = match.body,
                        receivedAtMillis = match.receivedAtMillis,
                        ruleId = match.ruleId,
                        reason = match.reason,
                        deleteAfterDays = match.deleteAfterDays,
                        heldBy = HeldBy.SWEEP,
                        wasRead = match.wasRead,
                    )
                    when (
                        val write = messageRepository.deleteMessages(
                            listOf(MessageRef.sms(match.providerMessageId)),
                        )
                    ) {
                        is SmsRepository.WriteResult.Success -> {
                            heldIds += held.id
                            ruleRepository.recordSweep(match.ruleId)
                        }
                        is SmsRepository.WriteResult.RoleNotHeld,
                        is SmsRepository.WriteResult.Failed,
                        -> {
                            // Roll back hold — message must not exist in both places.
                            quarantineRepository.deleteHeld(held.id)
                            failed++
                            if (write is SmsRepository.WriteResult.Failed) {
                                Log.e(TAG, "delete after hold failed", write.cause)
                            } else {
                                Log.e(TAG, "delete after hold: ROLE_SMS not held")
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(moveProgress = index + 1, moveTotal = total)
                    }
                }
            }
            heldIdsForUndo = heldIds
            _uiState.update {
                it.copy(
                    phase = SweepPhase.Done,
                    movedCount = heldIds.size,
                    failedCount = failed,
                    undoToastVisible = heldIds.isNotEmpty(),
                )
            }
            if (heldIds.isNotEmpty()) {
                scheduleUndoCommit()
            }
        }
    }

    fun undoMove() {
        val ids = heldIdsForUndo
        if (ids.isEmpty()) return
        undoJob?.cancel()
        undoJob = null
        heldIdsForUndo = emptyList()
        _uiState.update { it.copy(undoToastVisible = false) }
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) {
                when (val result = quarantineRepository.moveToInbox(id)) {
                    is SmsRepository.WriteResult.Success -> Unit
                    is SmsRepository.WriteResult.RoleNotHeld ->
                        Log.e(TAG, "undo moveToInbox: ROLE_SMS not held")
                    is SmsRepository.WriteResult.Failed ->
                        Log.e(TAG, "undo moveToInbox failed", result.cause)
                }
            }
        }
        _uiState.update {
            it.copy(movedCount = 0)
        }
        // Rescan so preview reflects restored inbox.
        startScan()
    }

    fun dismissUndoToast() {
        _uiState.update { it.copy(undoToastVisible = false) }
    }

    /**
     * Undo window ends: holds stay in Filtered, provider copies already gone.
     * Nothing more to commit — the confirm step already wrote both sides.
     */
    private fun scheduleUndoCommit() {
        undoJob?.cancel()
        var j: Job? = null
        j = applicationScope.launch {
            delay(BATCH_DELETE_UNDO_MS)
            if (undoJob === j) {
                undoJob = null
                heldIdsForUndo = emptyList()
                _uiState.update { it.copy(undoToastVisible = false) }
            }
        }
        undoJob = j
    }

    private companion object {
        const val TAG = "SweepViewModel"
    }
}
