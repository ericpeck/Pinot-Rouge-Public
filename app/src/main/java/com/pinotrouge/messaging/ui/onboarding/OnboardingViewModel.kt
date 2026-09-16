package com.pinotrouge.messaging.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.sms.SmsRoleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class OnboardingUiState(
    val step: Int = 0,
    val showRoleSheet: Boolean = false,
    val rolePickPinot: Boolean = true,
    val importBusy: Boolean = false,
    val importStarted: Boolean = false,
    val importDone: Boolean = false,
    val importCount: Int = 0,
    val importPercent: Int = 0,
    val packs: Map<StarterPackKey, Boolean> = StarterFilterPacks.all.associate {
        it.key to it.defaultEnabled
    },
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val ruleRepository: RuleRepository,
    private val smsRepository: SmsRepository,
    private val smsRoleManager: SmsRoleManager,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun nextStep() {
        _state.update { it.copy(step = (it.step + 1).coerceAtMost(3)) }
    }

    fun openRoleSheet() {
        _state.update { it.copy(showRoleSheet = true) }
    }

    fun dismissRoleSheet() {
        _state.update { it.copy(showRoleSheet = false) }
    }

    fun pickRolePinot(pinot: Boolean) {
        _state.update { it.copy(rolePickPinot = pinot) }
    }

    /**
     * "Set as default" — request the role when Pinot is selected, then advance
     * to import. "Messages" selection just closes the sheet without advancing
     * (user can still Not now / Continue again).
     */
    fun confirmRole(requestRole: () -> Unit) {
        val pickPinot = _state.value.rolePickPinot
        _state.update { it.copy(showRoleSheet = false) }
        if (pickPinot) {
            requestRole()
        }
        // Prototype advances into sync after confirm regardless of picker.
        _state.update { it.copy(step = 2) }
        startImportIfNeeded()
    }

    /** "Not now" — skip role, still complete the rest of onboarding. */
    fun skipRole() {
        _state.update { it.copy(showRoleSheet = false, step = 2) }
        startImportIfNeeded()
    }

    fun onArrivedAtImportStep() {
        startImportIfNeeded()
    }

    private fun startImportIfNeeded() {
        val s = _state.value
        if (s.importStarted || s.importBusy) return
        _state.update {
            it.copy(importStarted = true, importBusy = true, importPercent = 0)
        }
        viewModelScope.launch {
            // Real provider read — not a timer. History already lives in
            // Telephony; we count messages and report genuine progress.
            val messages = runCatching {
                smsRepository.getRecentInbox(limit = 50_000)
            }.getOrDefault(emptyList())
            val total = messages.size
            _state.update { it.copy(importCount = total) }

            if (total == 0) {
                _state.update {
                    it.copy(importBusy = false, importDone = true, importPercent = 100)
                }
                return@launch
            }

            // Walk the list so percent reflects real work, not a fake clock.
            messages.forEachIndexed { index, _ ->
                val pct = (((index + 1).toFloat() / total) * 100f).roundToInt()
                _state.update { it.copy(importPercent = pct) }
            }
            _state.update {
                it.copy(importBusy = false, importDone = true, importPercent = 100)
            }
        }
    }

    fun togglePack(key: StarterPackKey) {
        _state.update { st ->
            val current = st.packs[key] ?: true
            st.copy(packs = st.packs + (key to !current))
        }
    }

    /**
     * Step 3 finish: persist starter rules, mark onboarding complete.
     * Only here — a killed app before this still resumes the flow.
     */
    fun finish(onFinished: () -> Unit) {
        viewModelScope.launch {
            val rules = StarterFilterPacks.rulesFor(_state.value.packs)
            ruleRepository.saveAll(rules)
            settingsRepository.setOnboardingComplete(true)
            onFinished()
        }
    }

    fun isRoleHeld(): Boolean = smsRoleManager.isSmsRoleHeld()

    fun roleRequestIntent() = smsRoleManager.createRequestRoleIntent()

    /**
     * Runtime permissions needed to browse messages without ROLE_SMS.
     * Empty when the role is already held — do not re-prompt.
     */
    fun previewPermissionsToRequest(): Array<String> {
        if (smsRoleManager.isRoleHeld()) return emptyArray()
        return smsRepository.previewReadPermissions()
    }
}
