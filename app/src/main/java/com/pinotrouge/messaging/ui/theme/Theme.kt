package com.pinotrouge.messaging.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Pinot Rouge theme. Maps basics onto Material3 ColorScheme and the rest onto
 * [LocalPinotColors].
 *
 * **No dynamicColor.** Material You would replace the brand accent (`#9a3b53` light /
 * `#d4798c` dark) with a wallpaper colour. That is not a preference — do not reintroduce it.
 *
 * [darkTheme] is driven by Settings later; default is the system setting.
 * Do not read DataStore here.
 *
 * System bars: at targetSdk 35+ / edge-to-edge, `statusBarColor` /
 * `navigationBarColor` are no-ops. We paint [pinot.bg] behind the bars via a
 * full-screen Compose background so the *app* theme (DataStore) wins, not
 * `values-night/` / system night. Icon appearance still uses WindowInsetsController.
 */
@Composable
fun PinotRougeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Accent theme key. Defaults to Pinot; picker lands in feat/accent-theme-picker. */
    theme: PinotThemeKey = PinotThemeKey.Pinot,
    content: @Composable () -> Unit,
) {
    val pinot = pinotColors(theme, dark = darkTheme)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = pinot.accent,
            onPrimary = pinot.onAccent,
            primaryContainer = pinot.accent800,
            onPrimaryContainer = pinot.accent100,
            secondary = pinot.accent300,
            onSecondary = pinot.bg,
            background = pinot.bg,
            onBackground = pinot.text,
            surface = pinot.surface,
            onSurface = pinot.text,
            surfaceVariant = pinot.neutral900,
            onSurfaceVariant = pinot.dim,
            outline = pinot.divider,
            outlineVariant = pinot.neutral800,
            scrim = pinot.scrim,
        )
    } else {
        lightColorScheme(
            primary = pinot.accent,
            onPrimary = pinot.onAccent,
            primaryContainer = pinot.accent800,
            onPrimaryContainer = pinot.accent100,
            secondary = pinot.accent300,
            onSecondary = pinot.onAccent,
            background = pinot.bg,
            onBackground = pinot.text,
            surface = pinot.surface,
            onSurface = pinot.text,
            surfaceVariant = pinot.neutral900,
            onSurfaceVariant = pinot.dim,
            outline = pinot.divider,
            outlineVariant = pinot.neutral800,
            scrim = pinot.scrim,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // Icon colour only — bar *background* is painted in Compose (see below).
        // Do not set window.statusBarColor / navigationBarColor: no-ops at API 35+.
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalPinotColors provides pinot,
        LocalPinotShapes provides PinotShapes(),
        LocalPinotSpacing provides PinotSpacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PinotTypography,
        ) {
            // Full-screen app bg under system bars (edge-to-edge). Follows
            // darkTheme / pinot.bg, not system night or launch windowBackground.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(pinot.bg),
            ) {
                content()
            }
        }
    }
}
