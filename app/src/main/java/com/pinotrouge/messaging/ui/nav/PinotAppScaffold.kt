package com.pinotrouge.messaging.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme

/**
 * Written by [com.pinotrouge.messaging.ui.inbox.InboxRoute] so the scaffold can
 * hide the compose FAB during batch selection without reading the ViewModel.
 *
 * Default is a no-op holder so previews and screen-only tests still compose
 * without the scaffold.
 */
private val FallbackInboxSelectionActive = mutableStateOf(false)

val LocalInboxSelectionActive = staticCompositionLocalOf<MutableState<Boolean>> {
    FallbackInboxSelectionActive
}

/**
 * App frame: three-slot bottom bar (Chats · Filtered · Settings) + NavHost +
 * compose FAB (Chats only).
 *
 * Tabs use [PinotDestination.fromRoute]. Filtered is a bar destination that is
 * *not* a tab — [PinotDestination.showsBottomBar] is true for it so the bar
 * stays visible with Filtered lit, while [fromRoute] stays null so the FAB
 * stays off. Other overlays (thread, rules, builder, compose, archive, search)
 * hide the bar entirely.
 *
 * System bar regions are painted with [LocalPinotColors.bg] so they follow the
 * *app* theme under edge-to-edge (not `windowBackground` / system night).
 *
 * FAB / tab highlight scoped to the inbox route only (`fix/fab-scope` #40).
 */
@Composable
fun PinotAppScaffold(
    modifier: Modifier = Modifier,
    inboxUnreadCount: Int = 0,
    /** Total held count for the Filtered nav badge ([QuarantineRepository.observeHeldCount]). */
    filteredHeldCount: Int = 0,
    onComposeClick: () -> Unit = {},
    pendingThreadId: Long? = null,
    onPendingThreadConsumed: () -> Unit = {},
    pendingFiltered: Boolean = false,
    onPendingFilteredConsumed: () -> Unit = {},
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val current = PinotDestination.fromRoute(route)
    val filteredSelected = PinotDestination.isFilteredRoute(route)
    val showBottomBar = PinotDestination.showsBottomBar(route)
    val inboxSelectionActive = remember { mutableStateOf(false) }
    // FAB: tab == Chats only. Filtered leaves fromRoute null → no FAB.
    val showFab = current == PinotDestination.Inbox && !inboxSelectionActive.value
    // Version 3: 18dp from the right, 80dp from the bottom (above the nav bar).
    val fabBottomOffset = 80.dp

    CompositionLocalProvider(LocalInboxSelectionActive provides inboxSelectionActive) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.bg),
        ) {
            // Explicit inset-height paint so overlays (which may not use Scaffold
            // padding the same way) still get correct bar colour from app theme.
            Spacer(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(colors.bg),
            )
            Spacer(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(colors.bg),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = colors.bg,
                    contentColor = colors.text,
                    bottomBar = {
                        if (showBottomBar) {
                            PinotBottomBar(
                                // Under Filtered the underlying tab may still be
                                // on the back stack; suppress its highlight.
                                selectedTab = if (filteredSelected) null else current,
                                filteredSelected = filteredSelected,
                                onNavigateTab = { dest ->
                                    navigateToTab(navController, dest)
                                },
                                onNavigateFiltered = {
                                    if (!filteredSelected) {
                                        // Stack on the current tab so Back returns
                                        // underneath (Settings or Chats) — STATE.md
                                        // default overlay pop, not a third special case.
                                        navController.navigate(PinotDestination.ROUTE_FILTERED) {
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                inboxUnreadCount = inboxUnreadCount,
                                filteredHeldCount = filteredHeldCount,
                            )
                        }
                    },
                ) { innerPadding ->
                    PinotNavHost(
                        navController = navController,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        pendingThreadId = pendingThreadId,
                        onPendingThreadConsumed = onPendingThreadConsumed,
                        pendingFiltered = pendingFiltered,
                        onPendingFilteredConsumed = onPendingFilteredConsumed,
                    )
                }

                if (showFab) {
                    // fix/fab-scope: Chats only, hidden while batch selection is active.
                    PinotComposeFab(
                        onClick = {
                            onComposeClick()
                            navController.navigate("compose")
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = spacing.screenPadding, bottom = fabBottomOffset),
                    )
                }
            }
        }
    }
}

@Preview(name = "Scaffold · Dark · Chats", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PinotAppScaffoldPreviewDarkChats() {
    PinotRougeTheme(darkTheme = true) {
        PinotAppScaffold(inboxUnreadCount = 2, filteredHeldCount = 5)
    }
}

@Preview(name = "Scaffold · Light · Chats", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PinotAppScaffoldPreviewLightChats() {
    PinotRougeTheme(darkTheme = false) {
        PinotAppScaffold(inboxUnreadCount = 2, filteredHeldCount = 5)
    }
}

@Preview(name = "Scaffold · Light · Settings frame", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PinotAppScaffoldPreviewLightSettings() {
    PinotRougeTheme(darkTheme = false) {
        PinotAppScaffold()
    }
}
