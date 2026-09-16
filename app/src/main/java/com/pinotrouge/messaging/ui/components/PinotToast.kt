package com.pinotrouge.messaging.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotMotion
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography
import kotlinx.coroutines.delay

/**
 * Bottom overlay above the nav bar — V5 toast treatment:
 * full-width bar (18dp side insets), neutral900 surface, 1dp divider border,
 * elevation shadow, 14/16 inner padding, 260ms enter on [PinotMotion.standard].
 *
 * Optional [actionLabel] / [onAction] for undo-style toasts (batch delete).
 * Existing call sites stay on the 2000ms no-action default.
 *
 * Hosts supply vertical placement (typically `.padding(bottom = 72.dp)`).
 */
@Composable
fun PinotToast(
    message: String?,
    modifier: Modifier = Modifier,
    durationMs: Long = 2000L,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val density = LocalDensity.current
    val visible = !message.isNullOrBlank()
    val shape = RoundedCornerShape(shapes.md)
    val enterFloat = tween<Float>(
        durationMillis = PinotMotion.TOAST_ENTER_MS,
        easing = PinotMotion.standard,
    )
    val enterOffset = tween<IntOffset>(
        durationMillis = PinotMotion.TOAST_ENTER_MS,
        easing = PinotMotion.standard,
    )
    // 14.dp rise from the keyframe — not half-height.
    val enterRisePx = with(density) { 14.dp.roundToPx() }

    LaunchedEffect(message, durationMs) {
        if (message.isNullOrBlank()) return@LaunchedEffect
        delay(durationMs)
        onDismiss()
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = enterFloat) +
                slideInVertically(
                    animationSpec = enterOffset,
                    initialOffsetY = { enterRisePx },
                ) +
                scaleIn(
                    initialScale = 0.97f,
                    animationSpec = enterFloat,
                ),
            // Exit not specified in the prototype — keep the existing fade/slide out.
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                modifier = Modifier
                    // Outer: 18dp side insets (prototype left/right), keep host-friendly vertical.
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    // Elevation ports box-shadow: var(--shadow-md) without reading _ds/.
                    .shadow(elevation = 6.dp, shape = shape)
                    .clip(shape)
                    .background(colors.neutral900)
                    .border(1.dp, colors.divider, shape)
                    // Inner: 14 vertical / 16 horizontal per prototype.
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message.orEmpty(),
                    style = PinotTypography.bodyMedium,
                    color = colors.neutral100,
                    modifier = Modifier.weight(1f),
                )
                if (!actionLabel.isNullOrBlank()) {
                    Text(
                        text = actionLabel,
                        style = PinotTypography.labelLarge,
                        color = colors.accent200,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onAction,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Host that shows [message] and clears it after dismiss. Convenience for screens.
 */
@Composable
fun rememberPinotToastState(): PinotToastState {
    return remember { PinotToastState() }
}

class PinotToastState {
    var message by mutableStateOf<String?>(null)
        private set

    fun show(text: String) {
        message = text
    }

    fun dismiss() {
        message = null
    }
}

@Preview(name = "Toast · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotToastPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            PinotToast(
                message = "“Links from people I do not know” is on.",
                durationMs = Long.MAX_VALUE,
            )
        }
    }
}

@Preview(name = "Toast · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotToastPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            PinotToast(
                message = "“Links from people I do not know” is on.",
                durationMs = Long.MAX_VALUE,
            )
        }
    }
}

@Preview(name = "Toast · Undo action", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotToastPreviewUndo() {
    PinotRougeTheme(darkTheme = false) {
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            PinotToast(
                message = "Deleted 3 conversations",
                durationMs = Long.MAX_VALUE,
                actionLabel = "Undo",
            )
        }
    }
}
