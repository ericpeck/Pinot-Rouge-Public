package com.pinotrouge.messaging.ui.filtered

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.room.TransportKind
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import com.pinotrouge.messaging.ui.media.MmsTile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HeldMessageDetailUi(
    val id: String,
    val sender: String,
    val body: String,
    val timeLabel: String,
    val ruleName: String,
    /**
     * Secondary why-line for holds that are **not** a named filter catch
     * (blocked sender, could not be filed). Null when [reason] would only
     * repeat "Caught by {ruleName}" (e.g. stored `"Filter: {name}"`).
     */
    val displayReason: String?,
    val tiles: List<MmsTile> = emptyList(),
)

data class HeldMessageUiState(
    val message: HeldMessageDetailUi? = null,
    val loading: Boolean = true,
    val toastMessage: String? = null,
    /** Set after a successful move / block / delete so the route can pop. */
    val finished: Boolean = false,
)

/**
 * Full-screen held message. All mutations go through [QuarantineRepository] —
 * no new quarantine logic.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HeldMessageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val quarantineRepository: QuarantineRepository,
    private val ruleRepository: RuleRepository,
) : ViewModel() {

    private val heldId: String = checkNotNull(savedStateHandle["heldId"]) {
        "held/{heldId} requires heldId"
    }

    private val toastMessage = MutableStateFlow<String?>(null)
    private val finished = MutableStateFlow(false)

    private val mediaTiles = quarantineRepository.observeHeld().mapLatest { held ->
        val entity = held.firstOrNull { it.id == heldId }
        if (entity?.transportKind != TransportKind.MMS) {
            emptyList()
        } else {
            quarantineRepository.heldMedia(heldId).mapIndexedNotNull { seq, ref ->
                ref.toMmsTile(heldId, seq)
            }
        }
    }

    val uiState: StateFlow<HeldMessageUiState> = combine(
        quarantineRepository.observeHeld(),
        ruleRepository.observeRules(),
        ruleRepository.observeStats(),
        toastMessage,
        finished,
    ) { held, rules, stats, toast, done ->
        val entity = held.firstOrNull { it.id == heldId }
        val names = rules.associate { it.id to it.name }
        val statsIds = stats.map { it.ruleId }.toSet()
        HeldMessageUiState(
            message = entity?.let { e ->
                val ruleName = FilteredViewModel.resolveRuleName(
                    ruleId = e.ruleId,
                    namesById = names,
                    statsRuleIds = statsIds,
                )
                HeldMessageDetailUi(
                    id = e.id,
                    sender = e.sender,
                    body = e.body,
                    timeLabel = FilteredViewModel.formatHeldTime(e.receivedAt),
                    ruleName = ruleName,
                    displayReason = displayReason(
                        ruleId = e.ruleId,
                        ruleName = ruleName,
                        reason = e.reason,
                    ),
                )
            },
            loading = false,
            toastMessage = toast,
            finished = done,
        )
    }.let { withoutTiles ->
        combine(withoutTiles, mediaTiles) { state, tiles ->
            state.copy(message = state.message?.copy(tiles = tiles))
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HeldMessageUiState(),
    )

    fun dismissToast() {
        toastMessage.value = null
    }

    fun moveToInbox() {
        viewModelScope.launch {
            when (quarantineRepository.moveToInbox(heldId)) {
                is SmsRepository.WriteResult.Success -> {
                    toastMessage.value = FilteredViewModel.TOAST_MOVED
                    finished.value = true
                }
                is SmsRepository.WriteResult.RoleNotHeld,
                is SmsRepository.WriteResult.Failed,
                -> {
                    toastMessage.value = FilteredViewModel.TOAST_MOVE_FAILED
                }
            }
        }
    }

    fun blockSender() {
        viewModelScope.launch {
            val sender = uiState.value.message?.sender
            quarantineRepository.blockSender(heldId)
            toastMessage.value = sender?.let { "$it is blocked." }
                ?: FilteredViewModel.TOAST_DELETED
            finished.value = true
        }
    }

    fun deleteHeld() {
        viewModelScope.launch {
            quarantineRepository.deleteHeld(heldId)
            toastMessage.value = FilteredViewModel.TOAST_DELETED
            finished.value = true
        }
    }

    companion object {
        /**
         * Why-panel secondary line. Named filter catches already say
         * `Caught by "{ruleName}"` — do not also print `"Filter: {ruleName}"`.
         * System holds keep the stored reason (Blocked sender, etc.).
         */
        internal fun displayReason(
            ruleId: String,
            ruleName: String,
            reason: String,
        ): String? {
            if (reason.isBlank()) return null
            if (ruleId == IncomingMessagePipeline.RULE_ID_SYSTEM) return reason
            val filterPrefixed = "Filter: $ruleName"
            if (reason.equals(filterPrefixed, ignoreCase = true)) return null
            if (reason.equals(ruleName, ignoreCase = true)) return null
            return reason
        }
    }
}
