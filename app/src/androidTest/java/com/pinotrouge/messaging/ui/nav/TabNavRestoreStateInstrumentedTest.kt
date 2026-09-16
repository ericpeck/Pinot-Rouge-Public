package com.pinotrouge.messaging.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for `fix/tab-nav-restore-state` (defect-8-11-26-pt1), retargeted
 * by `fix/tab-nav-test-coverage`.
 *
 * After Filtered is stacked on a tab, tapping Chats must land on `inbox` — not
 * re-restore the saved Filtered overlay. Asserts routes only (stub NavHost, no
 * Hilt / ViewModels).
 *
 * Coverage boundary (be honest):
 * - These tests call [navigateToTab] (or wire the bar to it the same way
 *   [PinotAppScaffold] does). They guard the *options* inside that helper.
 * - Whether [PinotAppScaffold] itself still calls [navigateToTab] is **not**
 *   covered here — composing the full scaffold needs Hilt and the real screens.
 *   That wire is device-trace only. Do not assume a green suite means the
 *   scaffold was re-checked.
 *
 * [NavBadgeInstrumentedTest] stubs `onNavigateTab = {}` and never checks that a
 * tab tap navigates; the bar-tap case below covers that wire at the bar level.
 */
@RunWith(AndroidJUnit4::class)
class TabNavRestoreStateInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setStubNav() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = PinotDestination.Inbox.route,
            ) {
                composable(PinotDestination.Inbox.route) { }
                composable(PinotDestination.Settings.route) { }
                composable(PinotDestination.ROUTE_FILTERED) { }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Stub NavHost plus a real [PinotBottomBar], wired the same way
     * [PinotAppScaffold] wires it (`navigateToTab` / stack Filtered).
     */
    private fun setStubNavWithBar() {
        composeRule.setContent {
            PinotRougeTheme(darkTheme = true) {
                navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val route = backStackEntry?.destination?.route
                val current = PinotDestination.fromRoute(route)
                val filteredSelected = PinotDestination.isFilteredRoute(route)

                Column(Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = PinotDestination.Inbox.route,
                        modifier = Modifier.weight(1f),
                    ) {
                        composable(PinotDestination.Inbox.route) { }
                        composable(PinotDestination.Settings.route) { }
                        composable(PinotDestination.ROUTE_FILTERED) { }
                    }
                    PinotBottomBar(
                        selectedTab = if (filteredSelected) null else current,
                        filteredSelected = filteredSelected,
                        onNavigateTab = { dest -> navigateToTab(navController, dest) },
                        onNavigateFiltered = {
                            if (!filteredSelected) {
                                navController.navigate(PinotDestination.ROUTE_FILTERED) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        inboxUnreadCount = 0,
                        filteredHeldCount = 0,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun currentRoute(): String? =
        navController.currentBackStackEntry?.destination?.route

    private fun pushFiltered() {
        composeRule.runOnIdle {
            navController.navigate(PinotDestination.ROUTE_FILTERED) {
                launchSingleTop = true
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(PinotDestination.ROUTE_FILTERED, currentRoute())
        }
    }

    private fun goToTab(dest: PinotDestination) {
        composeRule.runOnIdle {
            navigateToTab(navController, dest)
        }
        composeRule.waitForIdle()
    }

    /** The primary regression guard: Chats while Filtered is open must clear it. */
    @Test
    fun inbox_then_filtered_then_chats_lands_on_inbox() {
        setStubNav()
        pushFiltered()

        goToTab(PinotDestination.Inbox)
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Inbox.route, currentRoute())
        }
    }

    /**
     * Device failure that needs a *seeded* saved stack: Chats-while-Filtered
     * first, then Settings, then Chats again still lands on Held without the fix.
     *
     * Do not assert after the seed — with the bug present that lands on
     * `filtered` and would only duplicate [inbox_then_filtered_then_chats_lands_on_inbox].
     * The point of this test is the final transition.
     */
    @Test
    fun poisoned_then_settings_then_chats_lands_on_inbox() {
        setStubNav()

        pushFiltered()
        goToTab(PinotDestination.Inbox) // seeds; do NOT assert here
        goToTab(PinotDestination.Settings)
        goToTab(PinotDestination.Inbox)

        composeRule.runOnIdle {
            assertEquals(PinotDestination.Inbox.route, currentRoute())
        }
    }

    /**
     * Unseeded Settings → Filtered → Chats. Works even with the bug present
     * (no saved stack under inbox yet). Kept as a path document, not as the
     * from-Settings regression guard — that is [poisoned_then_settings_then_chats_lands_on_inbox].
     */
    @Test
    fun settings_then_filtered_then_chats_unseeded_lands_on_inbox() {
        setStubNav()

        goToTab(PinotDestination.Settings)
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Settings.route, currentRoute())
        }

        pushFiltered()
        goToTab(PinotDestination.Inbox)
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Inbox.route, currentRoute())
        }
    }

    /** Settings still escapes Filtered (different save key even with the bug). */
    @Test
    fun inbox_then_filtered_then_settings_lands_on_settings() {
        setStubNav()
        pushFiltered()

        goToTab(PinotDestination.Settings)
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Settings.route, currentRoute())
        }
    }

    /** Baseline: tab alternation with no Filtered involved. */
    @Test
    fun chats_and_settings_alternate_without_filtered() {
        setStubNav()

        goToTab(PinotDestination.Settings)
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Settings.route, currentRoute())
        }

        goToTab(PinotDestination.Inbox)
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Inbox.route, currentRoute())
        }

        goToTab(PinotDestination.Settings)
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Settings.route, currentRoute())
        }
    }

    /**
     * Drives a real [PinotBottomBar] tap rather than calling [navigateToTab]
     * directly, so the bar's Chats slot and the tap → callback wire are covered.
     *
     * Still uses the same callback the scaffold uses; does **not** prove the
     * scaffold still calls [navigateToTab] (see class KDoc).
     */
    @Test
    fun bar_tap_chats_after_filtered_lands_on_inbox() {
        setStubNavWithBar()

        composeRule.onNodeWithText("Filtered").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(PinotDestination.ROUTE_FILTERED, currentRoute())
        }

        composeRule.onNodeWithText("Chats").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(PinotDestination.Inbox.route, currentRoute())
        }
    }
}
