package com.pinotrouge.messaging.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.prefs.AppSettings
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.ArchiveRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.sms.SmsRoleManager
import com.pinotrouge.messaging.ui.theme.PinotThemeKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isDefaultSmsApp: Boolean = false,
    /** Archived conversation count — trailing value on Settings › Archived. */
    val archivedCount: Int = 0,
    /** Enabled rules only — trailing `"{n} on"` on Settings › Filters. */
    val filtersOnCount: Int = 0,
    /**
     * Total held messages — same source as the Filtered nav badge
     * ([QuarantineRepository.observeHeldCount]), not the 7-day window.
     */
    val heldCount: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val smsRoleManager: SmsRoleManager,
    private val archiveRepository: ArchiveRepository,
    private val ruleRepository: RuleRepository,
    private val quarantineRepository: QuarantineRepository,
) : ViewModel() {

    private val filtersOnCount = ruleRepository.observeRules().map { rules ->
        rules.count { it.enabled }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        smsRoleManager.roleHeld,
        archiveRepository.observeCount(),
        quarantineRepository.observeHeldCount(),
        filtersOnCount,
    ) { settings, held, archived, heldCount, filtersOn ->
        SettingsUiState(
            settings = settings,
            isDefaultSmsApp = held,
            archivedCount = archived,
            filtersOnCount = filtersOn,
            heldCount = heldCount,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(isDefaultSmsApp = smsRoleManager.isRoleHeld()),
    )

    init {
        smsRoleManager.refresh()
    }

    fun refreshRole() {
        smsRoleManager.refresh()
    }

    fun roleRequestIntent() = smsRoleManager.createRequestRoleIntent()

    fun setWeeklyDigest(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHoldByDefault(value) }
    }

    fun setNeverFilterContacts(value: Boolean) {
        viewModelScope.launch { settingsRepository.setNeverFilterContacts(value) }
    }

    fun setShowOtpCopy(value: Boolean) {
        viewModelScope.launch { settingsRepository.setPreserveOtps(value) }
    }

    fun setAutoDownloadPictures(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoDownloadPictures(value) }
    }

    fun setDownloadWhileRoaming(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDownloadWhileRoaming(value) }
    }

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(value) }
    }

    fun setAccentTheme(value: PinotThemeKey) {
        viewModelScope.launch { settingsRepository.setAccentTheme(value) }
    }
}
