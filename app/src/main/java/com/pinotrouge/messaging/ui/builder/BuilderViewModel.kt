package com.pinotrouge.messaging.ui.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.EvaluationContext
import com.pinotrouge.messaging.rules.RuleEngine
import com.pinotrouge.messaging.ui.components.FilterSaveToastSession
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class BuilderUiState(
    val draft: BuilderDraft = BuilderDraft.newRule(),
    val plainWords: String = "",
    val backtestLine: String = formatBacktestLine(0, 0, 0),
    val backtestLoading: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    /** Confirm dialog while deleting an existing rule. */
    val showDeleteConfirm: Boolean = false,
    /** Real held-message count for the rule being deleted (dialog body). */
    val deleteHeldCount: Int = 0,
    val deleting: Boolean = false,
)

@HiltViewModel
class BuilderViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val quarantineRepository: QuarantineRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val ruleEngine: RuleEngine,
    private val smsRepository: SmsRepository,
    private val filterSaveToastSession: FilterSaveToastSession,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BuilderUiState())
    val uiState: StateFlow<BuilderUiState> = _uiState.asStateFlow()

    private var loadKey: String? = null
    private var backtestJob: Job? = null

    /**
     * Loads edit target, sender prefill, or a blank new-rule draft.
     * Safe to call again with the same key — ignored; different key reloads.
     */
    fun load(ruleId: String?, prefillSender: String?) {
        val key = "id=${ruleId.orEmpty()}|prefill=${prefillSender.orEmpty()}"
        if (key == loadKey && _uiState.value.loaded) return
        loadKey = key
        viewModelScope.launch {
            val draft = when {
                !ruleId.isNullOrBlank() && ruleId != "new" -> {
                    val existing = withContext(Dispatchers.IO) {
                        ruleRepository.getRule(ruleId)
                    }
                    existing?.let { BuilderDraft.fromRule(it) } ?: BuilderDraft.newRule()
                }
                !prefillSender.isNullOrBlank() -> BuilderDraft.prefillSender(prefillSender)
                else -> BuilderDraft.newRule()
            }
            applyDraft(draft, loaded = true)
        }
    }

    fun setName(name: String) = patch { if (it.isReadOnly) it else it.copy(name = name) }

    fun toggleMatch() = patch { it.toggleMatch() }

    fun setField(index: Int, field: ConditionField) = patch { it.setField(index, field) }

    fun setOperator(index: Int, opKey: String) = patch { it.setOperator(index, opKey) }

    fun setValue(index: Int, value: String) = patch { it.setValue(index, value) }

    fun removeCondition(index: Int) = patch { it.removeCondition(index) }

    fun addCondition() = patch { it.addCondition() }

    fun toggleAction(action: Action) = patch { it.toggleAction(action) }

    fun fewerDeleteDays() = patch { it.adjustDeleteDays(-7) }

    fun moreDeleteDays() = patch { it.adjustDeleteDays(+7) }

    /**
     * Opens the delete confirmation for an existing rule. No-op while creating.
     * Counts held rows for this rule id so the dialog body is real.
     *
     * No undo: a rule is small and rewritable; held messages are not deleted.
     */
    fun requestDelete() {
        val draft = _uiState.value.draft
        val id = draft.existingId ?: return
        if (_uiState.value.deleting || _uiState.value.showDeleteConfirm) return
        viewModelScope.launch {
            val heldCount = withContext(Dispatchers.IO) {
                quarantineRepository.observeHeld().first().count { it.ruleId == id }
            }
            _uiState.update {
                it.copy(
                    showDeleteConfirm = true,
                    deleteHeldCount = heldCount,
                )
            }
        }
    }

    fun dismissDeleteConfirm() {
        _uiState.update {
            it.copy(showDeleteConfirm = false, deleteHeldCount = 0)
        }
    }

    /**
     * Deletes the rule row only. Does not touch [held_messages] or [rule_stats].
     * Invokes [onDeleted] after a successful write so the caller can pop.
     */
    fun confirmDelete(onDeleted: () -> Unit) {
        val id = _uiState.value.draft.existingId ?: return
        if (_uiState.value.deleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(deleting = true, showDeleteConfirm = false) }
            try {
                withContext(Dispatchers.IO) {
                    ruleRepository.delete(id)
                }
                onDeleted()
            } finally {
                _uiState.update {
                    it.copy(deleting = false, deleteHeldCount = 0)
                }
            }
        }
    }

    /**
     * Persists the draft through [RuleRepository]. Blank name → "Untitled filter".
     * Invokes [onSaved] after a successful write.
     */
    fun save(onSaved: () -> Unit) {
        if (_uiState.value.saving) return
        if (_uiState.value.draft.isReadOnly) return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            try {
                val draft = _uiState.value.draft
                val name = draft.name.trim().ifEmpty { UNTITLED_FILTER_NAME }
                val id = draft.existingId ?: UUID.randomUUID().toString()
                val order = if (draft.existingId != null) {
                    draft.order
                } else {
                    withContext(Dispatchers.IO) {
                        val existing = ruleRepository.getRules()
                        (existing.maxOfOrNull { it.order } ?: -1) + 1
                    }
                }
                val rule = draft.toRule(id = id, order = order, name = name)
                withContext(Dispatchers.IO) {
                    ruleRepository.save(rule)
                }
                // Toast is hosted on Filters — this screen is about to navigate away.
                filterSaveToastSession.show(saveToastMessage(name))
                onSaved()
            } finally {
                _uiState.update { it.copy(saving = false) }
            }
        }
    }

    private fun patch(transform: (BuilderDraft) -> BuilderDraft) {
        val next = transform(_uiState.value.draft)
        applyDraft(next, loaded = _uiState.value.loaded)
    }

    private fun applyDraft(draft: BuilderDraft, loaded: Boolean) {
        val plain = ruleEngine.plainWords(draft.toPreviewRule())
        _uiState.update {
            it.copy(
                draft = draft,
                plainWords = plain,
                loaded = loaded,
            )
        }
        scheduleBacktest(draft)
    }

    /**
     * Debounce ~300ms after the last edit, then run
     * [MessageRepository.backtestSamples] + [RuleEngine.backtest] off main.
     * [plainWords] stays live on every keystroke above.
     *
     * Without [android.Manifest.permission.READ_SMS] the sample list is empty
     * for the wrong reason — report that honestly instead of "caught 0 of 0".
     */
    private fun scheduleBacktest(draft: BuilderDraft) {
        backtestJob?.cancel()
        backtestJob = viewModelScope.launch {
            _uiState.update { it.copy(backtestLoading = true) }
            delay(BACKTEST_DEBOUNCE_MS)
            val line = withContext(Dispatchers.IO) {
                if (!smsRepository.hasReadSmsPermission()) {
                    BACKTEST_NEEDS_PERMISSION
                } else {
                    val samples = messageRepository.backtestSamples(BACKTEST_SAMPLE_LIMIT)
                    val neverFilter = settingsRepository.settings.first().neverFilterContacts
                    val result = ruleEngine.backtest(
                        rule = draft.toPreviewRule(),
                        samples = samples,
                        context = EvaluationContext(
                            isKnownContact = false,
                            neverFilterContacts = neverFilter,
                            zone = ZoneId.systemDefault(),
                        ),
                    )
                    formatBacktestLine(
                        caught = result.caught,
                        sampled = result.sampled,
                        caughtFromContacts = result.caughtFromContacts,
                    )
                }
            }
            _uiState.update {
                it.copy(
                    backtestLine = line,
                    backtestLoading = false,
                )
            }
        }
    }

    companion object {
        const val BACKTEST_DEBOUNCE_MS = 300L
        const val BACKTEST_SAMPLE_LIMIT = 200

        /** Shown when the provider is unreadable — never a confident zero. */
        const val BACKTEST_NEEDS_PERMISSION =
            "Backtest needs permission to read your messages"
    }
}
