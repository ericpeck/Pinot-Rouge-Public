package com.pinotrouge.messaging.ui.nav

import androidx.annotation.DrawableRes
import com.pinotrouge.messaging.ui.components.PinotIcons

/**
 * Bottom-tab destinations — Version 5 IA: **Chats · Filtered · Settings**.
 *
 * Route key for Chats remains `inbox` to limit churn; the tab label is **Chats**
 * and the screen title is **Messages**.
 *
 * [PinotDestination] is the set of *tabs* (Chats, Settings). **Filtered is not a
 * tab** — it is a bar destination that opens the existing [ROUTE_FILTERED]
 * overlay while keeping the bottom bar visible and lit. That split is deliberate:
 * [fromRoute] still returns null for overlays (so the FAB stays off), while
 * [showsBottomBar] is true for Filtered only among overlays.
 *
 * Search is no longer a tab. It stays a screen at [ROUTE_SEARCH], reached from
 * the Chats search pill. Other overlays (`rules`, `thread/…`, `builder…`,
 * `compose`, `archive`) hide the bar entirely.
 */
enum class PinotDestination(
    val route: String,
    val label: String,
    @DrawableRes val icon: Int,
) {
    /** Chats tab — route stays `inbox` for deep links and existing callers. */
    Inbox(
        route = "inbox",
        label = "Chats",
        icon = PinotIcons.Chat,
    ),
    Settings(
        route = "settings",
        label = "Settings",
        icon = PinotIcons.Settings,
    );

    companion object {
        val start = Inbox

        /**
         * Held / Filtered overlay. Keeps the bottom bar ([showsBottomBar]) with
         * Filtered lit; not a [PinotDestination] entry so the FAB stays off.
         */
        const val ROUTE_FILTERED = "filtered"

        /** Search screen — stack route from the Chats pill, not a tab. */
        const val ROUTE_SEARCH = "search"

        const val ROUTE_RULES = "rules"
        const val ROUTE_ARCHIVE = "archive"
        /** Inbox sweep overlay — Settings › Filtering › Run filters on my inbox. */
        const val ROUTE_SWEEP = "sweep"

        /**
         * Resolves a NavHost route to a bottom-*tab* destination.
         *
         * Returns null for overlays and nested routes (`thread/{id}`, `compose`,
         * `builder…`, `filtered`, `search`, `rules`, …) and for null — do not
         * treat those as [Inbox]. That null is what scopes the FAB to Chats.
         */
        fun fromRoute(route: String?): PinotDestination? {
            if (route == null) return null
            // Exact tab match only — never prefix-match thread/builder.
            return entries.firstOrNull { it.route == route }
        }

        /**
         * Whether the bottom bar stays visible on this route.
         *
         * True for the two tabs and for [ROUTE_FILTERED] (prototype keeps the
         * bar with Filtered lit). False for every other overlay.
         */
        fun showsBottomBar(route: String?): Boolean {
            if (route == null) return false
            if (fromRoute(route) != null) return true
            return route == ROUTE_FILTERED
        }

        fun isFilteredRoute(route: String?): Boolean = route == ROUTE_FILTERED
    }
}
