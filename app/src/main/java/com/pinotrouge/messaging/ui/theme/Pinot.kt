package com.pinotrouge.messaging.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Full Pinot token set that does not fit Material3 [androidx.compose.material3.ColorScheme].
 * Screens read [LocalPinotColors.current] for ramps, dim roles, bubbles and switch states.
 *
 * **Never apply alpha to text to make it secondary.** Use [dim] / [dimmer] solid colours.
 */
@Immutable
data class PinotColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val text: Color,
    /** Secondary text, previews, descriptions — solid colour, not alpha. */
    val dim: Color,
    /** Timestamps, meta, placeholders — solid colour, not alpha. */
    val dimmer: Color,
    val divider: Color,
    val accent: Color,
    /**
     * Darker accent for small text on a surface (12sp). Falls back to [accent]
     * when a theme does not define one (Version 5 `ink || accent`).
     */
    val ink: Color,
    /** Text on a filled accent surface — badges only. */
    val onAccent: Color,
    val accent900: Color,
    val accent800: Color,
    val accent300: Color,
    val accent200: Color,
    val accent100: Color,
    val neutral900: Color,
    val neutral800: Color,
    val neutral700: Color,
    val neutral100: Color,
    val switchTrackOff: Color,
    val switchTrackBorderOff: Color,
    val switchKnobOff: Color,
    val switchTrackOn: Color,
    val switchTrackBorderOn: Color,
    val switchKnobOn: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val outgoingBubbleText: Color,
    val shadowSmBorder: Color,
    val disabledOpacity: Float = 0.45f,
    val pressedOverlay: Color,
    val selection: Color,
    /** Modal scrim — per-theme (light 50% / dark 72%). */
    val scrim: Color,
)

/**
 * Radii from Version 3: 8dp cards/inputs/menus, 18dp bubbles/FAB/sheet tops, pill for chips.
 */
@Immutable
data class PinotShapes(
    val sm: Dp = 8.dp,
    val md: Dp = 8.dp,
    val lg: Dp = 18.dp,
    /** FAB is 58dp at 18dp radius (was a 16dp rounded square). */
    val fab: Dp = 18.dp,
    val pill: Dp = 999.dp,
    val avatar: Dp = 44.dp,
)

/**
 * Roomier Version 3 spacing — the dense 0.7× Pinot scale is gone.
 */
@Immutable
data class PinotSpacing(
    val xs: Dp = 8.dp,
    val sm: Dp = 9.dp,
    val md: Dp = 10.dp,
    val lg: Dp = 12.dp,
    val xl: Dp = 14.dp,
    val xxl: Dp = 18.dp,
    val screenPadding: Dp = 18.dp,
    val rowPaddingV: Dp = 16.dp,
    val rowMinHeight: Dp = 76.dp,
    val rowMinHeightCompact: Dp = 64.dp,
    val navHeight: Dp = 62.dp,
    val scrollBottomInset: Dp = 96.dp,
    val fabSize: Dp = 58.dp,
    val switchWidth: Dp = 40.dp,
    val switchHeight: Dp = 24.dp,
    val switchKnob: Dp = 18.dp,
)

/**
 * Motion tokens. Nothing has to consume these yet — later waves will.
 * Respect `Settings.Global.ANIMATOR_DURATION_SCALE` at use sites.
 */
object PinotMotion {
    /** Every screen and sheet transition. */
    val standard = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

    /** Switch knob only — the slight overshoot is intentional. */
    val overshoot = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)

    const val OVERLAY_ENTER_MS = 240
    const val LIST_STAGGER_MS = 340
    const val LIST_STAGGER_STEP_MS = 30
    const val LIST_STAGGER_MAX_ITEMS = 8
    const val SHEET_ENTER_MS = 280
    const val SCRIM_FADE_MS = 200
    const val TOAST_ENTER_MS = 260
    const val MENU_ENTER_MS = 160
    const val SWITCH_MS = 220
}



val LocalPinotColors = staticCompositionLocalOf { pinotColorsDark() }
val LocalPinotShapes = staticCompositionLocalOf { PinotShapes() }
val LocalPinotSpacing = staticCompositionLocalOf { PinotSpacing() }
