package com.pinotrouge.messaging

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.prefs.SettingsRepository
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.ui.nav.PinotAppScaffold
import com.pinotrouge.messaging.ui.onboarding.OnboardingScreen
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotThemeKey
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Hosts onboarding or the main shell.
 *
 * Theme and nav badges come from real repositories — previews may hardcode
 * counts, but the running app must not.
 *
 * Notification taps deliver [NotificationHelper.EXTRA_THREAD_ID] and/or a
 * `pinotrouge://thread/{id}` VIEW intent; we hand that id to the scaffold once.
 * The weekly digest uses `pinotrouge://filtered` the same way.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Thread to open from a notification; cleared after NavHost consumes it. */
    private var pendingThreadId by mutableStateOf<Long?>(null)

    /** Filtered tab from the weekly-digest deep link; cleared after NavHost consumes it. */
    private var pendingFiltered by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingThreadId = threadIdFrom(intent)
        pendingFiltered = filteredFrom(intent)
        enableEdgeToEdge()
        setContent {
            val rootVm: RootViewModel = hiltViewModel()
            // Dark-first until the stored value arrives — matches launch theme
            // and SettingsRepository default (dark = true).
            val darkTheme by rootVm.darkTheme.collectAsStateWithLifecycle()
            val accentTheme by rootVm.accentTheme.collectAsStateWithLifecycle()
            val onboardingDone by rootVm.onboardingComplete.collectAsStateWithLifecycle()
            val inboxUnread by rootVm.inboxUnreadCount.collectAsStateWithLifecycle()
            val heldCount by rootVm.filteredHeldCount.collectAsStateWithLifecycle()

            PinotRougeTheme(darkTheme = darkTheme, theme = accentTheme) {
                when (onboardingDone) {
                    null -> {
                        // Waiting on DataStore; launch window is already dark.
                    }
                    false -> OnboardingScreen(
                        onFinished = { /* Settings already set; Flow will flip */ },
                        modifier = Modifier.fillMaxSize(),
                    )
                    true -> PinotAppScaffold(
                        modifier = Modifier.fillMaxSize(),
                        inboxUnreadCount = inboxUnread,
                        filteredHeldCount = heldCount,
                        pendingThreadId = pendingThreadId,
                        onPendingThreadConsumed = { pendingThreadId = null },
                        pendingFiltered = pendingFiltered,
                        onPendingFilteredConsumed = { pendingFiltered = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingThreadId = threadIdFrom(intent)
        pendingFiltered = filteredFrom(intent)
    }

    private fun threadIdFrom(intent: Intent?): Long? {
        if (intent == null) return null
        val extra = intent.getLongExtra(NotificationHelper.EXTRA_THREAD_ID, -1L)
        if (extra > 0L) return extra
        val data = intent.data ?: return null
        if (data.scheme != NotificationHelper.DEEP_LINK_SCHEME) return null
        if (data.host != NotificationHelper.DEEP_LINK_HOST_THREAD) return null
        return data.lastPathSegment?.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun filteredFrom(intent: Intent?): Boolean {
        if (intent == null) return false
        val data = intent.data ?: return false
        if (data.scheme != NotificationHelper.DEEP_LINK_SCHEME) return false
        return data.host == NotificationHelper.DEEP_LINK_HOST_FILTERED
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    messageRepository: MessageRepository,
    quarantineRepository: QuarantineRepository,
) : ViewModel() {
    val onboardingComplete: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Defaults to light until DataStore emits (matches first-launch AppSettings). */
    val darkTheme: StateFlow<Boolean> = settingsRepository.settings
        .map { it.darkTheme }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Accent theme key — defaults to pinot until DataStore emits. */
    val accentTheme: StateFlow<PinotThemeKey> = settingsRepository.settings
        .map { it.accentTheme }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PinotThemeKey.Pinot)

    /**
     * Held-message badge — Room Flow, updates as soon as quarantine changes.
     */
    val filteredHeldCount: StateFlow<Int> = quarantineRepository.observeHeldCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Inbox unread badge — ContentObserver-driven Flow from
     * [MessageRepository.observeUnreadCount], not a poll loop. Same total as
     * before (sum of per-thread unread flags); only the source changed.
     * Unsubscribes when the shell leaves the screen, which unregisters the
     * observer via the repository's [kotlinx.coroutines.channels.awaitClose].
     */
    val inboxUnreadCount: StateFlow<Int> = messageRepository.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Preview(name = "Main · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun MainActivityPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        PinotAppScaffold(
            modifier = Modifier.fillMaxSize(),
            inboxUnreadCount = 2,
            filteredHeldCount = 5,
        )
    }
}

@Preview(name = "Main · Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun MainActivityPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        PinotAppScaffold(
            modifier = Modifier.fillMaxSize(),
            inboxUnreadCount = 2,
            filteredHeldCount = 5,
        )
    }
}
