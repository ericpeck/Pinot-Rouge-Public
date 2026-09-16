package com.pinotrouge.messaging.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.repo.RuleRepository
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.RuleEngine
import com.pinotrouge.messaging.ui.components.FilterSaveToastSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleListItem(
    val rule: Rule,
    val summary: String,
    val caughtLabel: String,
    val lastCaughtLabel: String,
)

data class RulesListUiState(
    val items: List<RuleListItem> = emptyList(),
    /** Save confirmation from the builder — survives navigation to this screen. */
    val toastMessage: String? = null,
)

@HiltViewModel
class RulesListViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val ruleEngine: RuleEngine,
    private val filterSaveToastSession: FilterSaveToastSession,
) : ViewModel() {

    val uiState: StateFlow<RulesListUiState> = combine(
        ruleRepository.observeRules(),
        ruleRepository.observeStats(),
        filterSaveToastSession.message,
    ) { rules, stats, toastMessage ->
        val statsById = stats.associateBy { it.ruleId }
        val now = System.currentTimeMillis()
        RulesListUiState(
            items = rules
                .sortedBy { it.order }
                .map { rule ->
                    val s = statsById[rule.id]
                    RuleListItem(
                        rule = rule,
                        summary = ruleEngine.summarize(rule),
                        caughtLabel = formatCaughtLabel(
                            caught = s?.caughtCount ?: 0,
                            swept = s?.sweptCount ?: 0,
                        ),
                        lastCaughtLabel = formatLastCaught(
                            lastCaughtAt = s?.lastCaughtAt,
                            nowMillis = now,
                            sweptCount = s?.sweptCount ?: 0,
                        ),
                    )
                },
            toastMessage = toastMessage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        RulesListUiState(),
    )

    /** Filters host composed — owns any pending save confirmation. */
    fun onHostEntered() {
        filterSaveToastSession.claim()
    }

    /**
     * Filters host left. Clears only after [onHostEntered] so a back-stack
     * dispose during save navigation does not race [FilterSaveToastSession.show].
     */
    fun onHostLeft() {
        filterSaveToastSession.clearOnLeave()
    }

    fun dismissToast() {
        filterSaveToastSession.clear()
    }

    fun setEnabled(rule: Rule, enabled: Boolean) {
        if (rule.enabled == enabled) return
        viewModelScope.launch {
            ruleRepository.save(rule.copy(enabled = enabled))
        }
    }

    /**
     * Persist a new top-to-bottom evaluation order. [rules] is the full list
     * in the order the user dropped; repository renumbers densely in one write.
     */
    fun reorder(rules: List<Rule>) {
        if (rules.isEmpty()) return
        viewModelScope.launch {
            ruleRepository.reorder(rules)
        }
    }
}

/** "N caught this month" — arrival catches only. */
internal fun formatCaughtThisMonth(count: Int): String = "$count caught this month"

/**
 * Arrival catches, with swept total when non-zero:
 * `"2 caught this month · 18 swept"`. A swept-only rule must not look idle.
 */
internal fun formatCaughtLabel(caught: Int, swept: Int): String {
    return if (swept <= 0) {
        formatCaughtThisMonth(caught)
    } else {
        "${formatCaughtThisMonth(caught)} · $swept swept"
    }
}

/**
 * Prototype relative times: "last caught 12 min ago", etc.
 * "never run" when no arrival catch and no sweeps.
 * "swept" when there are sweeps but [lastCaughtAt] is null (arrival-only clock).
 */
internal fun formatLastCaught(
    lastCaughtAt: Long?,
    nowMillis: Long,
    sweptCount: Int = 0,
): String {
    if (lastCaughtAt == null) {
        return if (sweptCount > 0) "swept" else "never run"
    }
    val deltaMs = (nowMillis - lastCaughtAt).coerceAtLeast(0L)
    val minutes = deltaMs / 60_000L
    val hours = deltaMs / 3_600_000L
    val days = deltaMs / 86_400_000L
    val relative = when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        hours < 24L -> "$hours h ago"
        else -> "$days d ago"
    }
    return if (relative == "just now") {
        "last caught just now"
    } else {
        "last caught $relative"
    }
}
