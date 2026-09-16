package com.pinotrouge.messaging.ui.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for `fix/post-save-back-stack`.
 *
 * Saving a filter used `popUpTo(inbox){saveState} + restoreState`, which
 * re-filed the builder under a second `rules` entry. Back then walked
 * Filters → Edit filter → Filters → Settings → Messages.
 *
 * Asserts the **full** back-stack route sequence after [handleBuilderSaved],
 * not only the top entry — the defect lives underneath.
 */
@RunWith(AndroidJUnit4::class)
class PostSaveBackStackInstrumentedTest {

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
                composable(PinotDestination.ROUTE_RULES) { }
                composable("builder") { }
            }
        }
        composeRule.waitForIdle()
    }

    private fun routes(): List<String> =
        navController.currentBackStack.value.mapNotNull { it.destination.route }

    /**
     * Drive to inbox → settings → rules → builder, the stack before a save
     * from Filters › New / Edit.
     */
    private fun pushToBuilder() {
        composeRule.runOnIdle {
            navController.navigate(PinotDestination.Settings.route)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            navController.navigate(PinotDestination.ROUTE_RULES)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            navController.navigate("builder")
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    PinotDestination.Inbox.route,
                    PinotDestination.Settings.route,
                    PinotDestination.ROUTE_RULES,
                    "builder",
                ),
                routes(),
            )
        }
    }

    @Test
    fun handleBuilderSaved_stack_is_inbox_settings_rules() {
        setStubNav()
        pushToBuilder()

        composeRule.runOnIdle {
            handleBuilderSaved(navController)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val stack = routes()
            assertEquals(
                listOf(
                    PinotDestination.Inbox.route,
                    PinotDestination.Settings.route,
                    PinotDestination.ROUTE_RULES,
                ),
                stack,
            )
            assertFalse("builder must not remain under Filters", "builder" in stack)
            assertEquals(
                "exactly one rules entry",
                1,
                stack.count { it == PinotDestination.ROUTE_RULES },
            )
        }
    }
}
