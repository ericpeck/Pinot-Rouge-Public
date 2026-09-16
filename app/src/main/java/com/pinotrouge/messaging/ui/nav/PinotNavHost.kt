package com.pinotrouge.messaging.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * Root NavHost. Bottom bar: Chats · Filtered · Settings; start = inbox.
 *
 * Filtered (`filtered`) is a bar destination that keeps the bottom bar visible
 * (see [PinotDestination.showsBottomBar]). Filters (`rules`) and Archive remain
 * full overlays. Search is a stack route from the Chats pill, not a tab.
 * Builder accepts an optional `returnsTo` query used by the two non-default back
 * cases in STATE.md (Wave 9 wires Filter-this-sender).
 *
 * Screens are wired via fully-qualified `<Screen>Route(...)` calls — no screen
 * imports in this file (Wave 3 PinotNavHost protocol).
 */
@Composable
fun PinotNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    pendingThreadId: Long? = null,
    onPendingThreadConsumed: () -> Unit = {},
    pendingFiltered: Boolean = false,
    onPendingFilteredConsumed: () -> Unit = {},
) {
    LaunchedEffect(pendingThreadId) {
        val id = pendingThreadId ?: return@LaunchedEffect
        if (id <= 0L) {
            onPendingThreadConsumed()
            return@LaunchedEffect
        }
        navController.navigate("thread/$id") {
            launchSingleTop = true
        }
        onPendingThreadConsumed()
    }

    LaunchedEffect(pendingFiltered) {
        if (!pendingFiltered) return@LaunchedEffect
        navController.navigate(PinotDestination.ROUTE_FILTERED) {
            launchSingleTop = true
        }
        onPendingFilteredConsumed()
    }

    NavHost(
        navController = navController,
        startDestination = PinotDestination.start.route,
        modifier = modifier,
    ) {
        // —— Tabs (Chats, Settings) ——
        composable(PinotDestination.Inbox.route) {
            com.pinotrouge.messaging.ui.inbox.InboxRoute(
                onOpenThread = { threadId ->
                    navController.navigate("thread/$threadId")
                },
                onOpenHeld = {
                    navController.navigate(PinotDestination.ROUTE_FILTERED)
                },
                onOpenSearch = {
                    // Stack Search so system Back returns to Chats. Not a tab —
                    // bar hides (fromRoute null, showsBottomBar false).
                    navController.navigate(PinotDestination.ROUTE_SEARCH) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(PinotDestination.Settings.route) {
            com.pinotrouge.messaging.ui.settings.SettingsRoute(
                onOpenFilters = {
                    navController.navigate(PinotDestination.ROUTE_RULES)
                },
                onOpenHeld = {
                    navController.navigate(PinotDestination.ROUTE_FILTERED)
                },
                onOpenArchived = {
                    navController.navigate(PinotDestination.ROUTE_ARCHIVE)
                },
                onOpenSweep = {
                    navController.navigate(PinotDestination.ROUTE_SWEEP)
                },
            )
        }

        // —— Search (stack route from Chats pill; not a tab) ——
        composable(PinotDestination.ROUTE_SEARCH) {
            com.pinotrouge.messaging.ui.search.SearchRoute(
                onOpenThread = { threadId ->
                    navController.navigate("thread/$threadId")
                },
                onOpenHeldMessage = { heldId ->
                    navController.navigate("held/$heldId")
                },
            )
        }

        // —— Overlays. Filtered keeps the bottom bar (showsBottomBar); others hide it ——
        composable(PinotDestination.ROUTE_FILTERED) {
            Column(modifier = Modifier.fillMaxSize()) {
                OverlayHeader(
                    title = stringResource(R.string.filtered_title_held),
                    onBack = { navController.popBackStack() },
                )
                com.pinotrouge.messaging.ui.filtered.FilteredRoute(
                    onOpenHeldMessage = { heldId ->
                        navController.navigate("held/$heldId")
                    },
                    // Stack Filters on Held — Back returns here (empty-state CTA).
                    onOpenFilters = {
                        navController.navigate(PinotDestination.ROUTE_RULES)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        composable(
            route = "held/{heldId}",
            arguments = listOf(
                navArgument("heldId") { type = NavType.StringType },
            ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OverlayHeader(
                    title = stringResource(R.string.held_message_title),
                    onBack = { navController.popBackStack() },
                )
                com.pinotrouge.messaging.ui.filtered.HeldMessageRoute(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        composable(PinotDestination.ROUTE_RULES) {
            val openNewFilter: () -> Unit = {
                navController.navigate(builderRoute(returnsTo = BuilderReturnsTo.Filters))
            }
            Column(modifier = Modifier.fillMaxSize()) {
                OverlayHeader(
                    title = stringResource(R.string.rules_title),
                    onBack = { navController.popBackStack() },
                ) {
                    // V3: trailing "New" — fully-qualified (no PinotNavHost imports).
                    com.pinotrouge.messaging.ui.components.PinotButton(
                        text = stringResource(R.string.rules_new),
                        onClick = openNewFilter,
                        variant = com.pinotrouge.messaging.ui.components.PinotButtonVariant.Primary,
                        leadingIcon = com.pinotrouge.messaging.ui.components.PinotIcons.Plus,
                    )
                }
                com.pinotrouge.messaging.ui.rules.RulesListRoute(
                    onNewFilter = openNewFilter,
                    onEditFilter = { id ->
                        navController.navigate(
                            builderRoute(ruleId = id, returnsTo = BuilderReturnsTo.Filters),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        composable(PinotDestination.ROUTE_ARCHIVE) {
            // Archive owns its OverlayHeader (dynamic "{n} kept…" subtitle).
            // Still one header — no second title in the screen body.
            com.pinotrouge.messaging.ui.archive.ArchiveRoute(
                onBack = { navController.popBackStack() },
                onOpenThread = { threadId ->
                    navController.navigate("thread/$threadId")
                },
            )
        }
        composable(PinotDestination.ROUTE_SWEEP) {
            Column(modifier = Modifier.fillMaxSize()) {
                OverlayHeader(
                    title = stringResource(R.string.sweep_title),
                    onBack = { navController.popBackStack() },
                )
                com.pinotrouge.messaging.ui.sweep.SweepRoute(
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // —— Builder (optional returnsTo for STATE.md back cases) ——
        composable(
            route = "builder?returnsTo={returnsTo}",
            arguments = listOf(
                navArgument("returnsTo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val returnsTo = entry.arguments?.getString("returnsTo")
            com.pinotrouge.messaging.ui.builder.BuilderRoute(
                ruleId = null,
                prefillSender = null,
                onClose = { handleBuilderClose(navController, returnsTo) },
                onSaved = { handleBuilderSaved(navController) },
            )
        }
        composable(
            route = "builder/{ruleId}?returnsTo={returnsTo}",
            arguments = listOf(
                navArgument("ruleId") { type = NavType.StringType },
                navArgument("returnsTo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val returnsTo = entry.arguments?.getString("returnsTo")
            com.pinotrouge.messaging.ui.builder.BuilderRoute(
                ruleId = entry.arguments?.getString("ruleId")?.takeIf { it != "new" },
                prefillSender = null,
                onClose = { handleBuilderClose(navController, returnsTo) },
                onSaved = { handleBuilderSaved(navController) },
            )
        }
        composable(
            route = "builder/prefill/{prefillSender}?returnsTo={returnsTo}",
            arguments = listOf(
                navArgument("prefillSender") { type = NavType.StringType },
                navArgument("returnsTo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val returnsTo = entry.arguments?.getString("returnsTo")
            com.pinotrouge.messaging.ui.builder.BuilderRoute(
                ruleId = null,
                prefillSender = entry.arguments?.getString("prefillSender"),
                onClose = { handleBuilderClose(navController, returnsTo) },
                onSaved = { handleBuilderSaved(navController) },
            )
        }

        // —— Thread / compose ——
        composable(
            route = "thread/{threadId}",
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = NotificationHelper.threadDeepLinkPattern()
                },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("threadId")?.toLongOrNull()
                ?: return@composable
            com.pinotrouge.messaging.ui.thread.ThreadRoute(
                threadId = id,
                onBack = { navController.popBackStack() },
                onOpenBuilderForSender = { nameOrNumber ->
                    // Wave 9: Filter this sender. returnsTo=thread/{id} clears Filters
                    // underneath on cancel (handleBuilderClose).
                    navController.navigate(
                        builderRoute(
                            prefillSender = nameOrNumber,
                            returnsTo = BuilderReturnsTo.thread(id),
                        ),
                    )
                },
                onOpenPhoto = { mmsId, seq ->
                    navController.navigate("photo/$mmsId/$seq")
                },
                onSwitchThread = { newId ->
                    if (newId > 0L && newId != id) {
                        navController.navigate("thread/$newId") {
                            popUpTo("thread/{threadId}") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(
            route = "photo/{mmsId}/{seq}",
            arguments = listOf(
                navArgument("mmsId") { type = NavType.LongType },
                navArgument("seq") { type = NavType.IntType },
            ),
        ) { entry ->
            val mmsId = entry.arguments?.getLong("mmsId") ?: return@composable
            val seq = entry.arguments?.getInt("seq") ?: return@composable
            com.pinotrouge.messaging.ui.media.PhotoViewerRoute(
                mmsId = mmsId,
                seq = seq,
                onBack = { navController.popBackStack() },
            )
        }
        composable("compose") {
            com.pinotrouge.messaging.ui.compose.ComposeRoute(
                onClose = { navController.popBackStack() },
                onSent = { threadId ->
                    navController.navigate("thread/$threadId") {
                        popUpTo("compose") { inclusive = true }
                    }
                },
            )
        }
    }
}

/**
 * Where the builder should return on cancel, and how save is handled.
 *
 * - [Filters] — pop back to the Filters overlay (opened from Settings).
 * - `thread/{id}` — return to that thread and pop any Filters under the builder
 *   so backing out of the thread does not reveal Filters the user never opened.
 * - null — plain [NavHostController.popBackStack].
 *
 * Wave 9 wires *Filter this sender* into [BuilderReturnsTo.thread]; the path is
 * live today from the thread funnel.
 */
object BuilderReturnsTo {
    const val Filters = "filters"
    /** Encoded without `/` so it survives query-param encoding. */
    fun thread(threadId: Long): String = "thread_$threadId"

    fun isThread(returnsTo: String): Boolean = returnsTo.startsWith("thread_")

    fun threadRoute(returnsTo: String): String? {
        if (!isThread(returnsTo)) return null
        val id = returnsTo.removePrefix("thread_")
        return "thread/$id"
    }
}

internal fun builderRoute(
    ruleId: String? = null,
    prefillSender: String? = null,
    returnsTo: String? = null,
): String {
    // java.net.URLEncoder (not android.net.Uri) so unit tests run without a mock.
    fun enc(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    val base = when {
        prefillSender != null ->
            "builder/prefill/${enc(prefillSender)}"
        ruleId != null ->
            "builder/${enc(ruleId)}"
        else ->
            "builder"
    }
    return if (returnsTo != null) {
        "$base?returnsTo=${enc(returnsTo)}"
    } else {
        base
    }
}

/**
 * Cancel: default pop, or return to thread while clearing Filters/Held underlays.
 */
internal fun handleBuilderClose(navController: NavHostController, returnsTo: String?) {
    when {
        returnsTo == null -> {
            navController.popBackStack()
        }
        returnsTo == BuilderReturnsTo.Filters -> {
            if (!navController.popBackStack(PinotDestination.ROUTE_RULES, inclusive = false)) {
                navController.popBackStack()
            }
        }
        BuilderReturnsTo.isThread(returnsTo) -> {
            val threadRoute = BuilderReturnsTo.threadRoute(returnsTo) ?: run {
                navController.popBackStack()
                return
            }
            // Pop builder, then any overlay (filters/held) until the thread is on top.
            navController.popBackStack()
            while (true) {
                val route = navController.currentBackStackEntry?.destination?.route
                if (route == null || route == threadRoute || route.startsWith("thread/")) {
                    break
                }
                if (route == PinotDestination.ROUTE_RULES ||
                    route == PinotDestination.ROUTE_FILTERED
                ) {
                    if (!navController.popBackStack()) break
                    continue
                }
                break
            }
            val current = navController.currentBackStackEntry?.destination?.route
            if (current != threadRoute) {
                navController.navigate(threadRoute) {
                    launchSingleTop = true
                }
            }
        }
        else -> navController.popBackStack()
    }
}

/**
 * Save always lands on Filters and clears the thread (STATE.md case 2).
 *
 * No [saveState]/[restoreState]: those flags re-filed the builder under a
 * second `rules` entry (same round-trip as [navigateToTab] / defect-8-11-26-pt1).
 * Non-inclusive [popUpTo] clears everything above inbox, including the builder.
 */
internal fun handleBuilderSaved(navController: NavHostController) {
    navController.navigate(PinotDestination.Settings.route) {
        // Intentionally no saveState / restoreState — same as [navigateToTab].
        popUpTo(PinotDestination.Inbox.route) {
            inclusive = false
        }
        launchSingleTop = true
    }
    navController.navigate(PinotDestination.ROUTE_RULES) {
        launchSingleTop = true
    }
}

/**
 * Bottom-tab navigation (Chats / Settings).
 *
 * Pops overlays stacked on the current tab (Filtered, and anything else above
 * the start destination) without saving/restoring per-tab back stacks.
 *
 * Filtered is not a [PinotDestination] — it is pushed onto the *current* tab
 * with no `popUpTo`, so Back returns underneath. Saving that stack under the
 * start destination and then restoring it made Chats a no-op after Filtered
 * had been opened (`fix/tab-nav-restore-state`).
 */
internal fun navigateToTab(navController: NavHostController, dest: PinotDestination) {
    navController.navigate(dest.route) {
        // Intentionally no saveState / restoreState — see KDoc.
        popUpTo(navController.graph.findStartDestination().id)
        launchSingleTop = true
    }
}

@Composable
private fun PlaceholderScreen(name: String) {
    val colors = LocalPinotColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = PinotTypography.bodyMedium,
            color = colors.dim,
        )
    }
}
