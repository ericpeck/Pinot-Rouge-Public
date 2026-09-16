package com.pinotrouge.messaging.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme

/**
 * Compose FAB — inbox only. Version 3: 58dp square, 18dp corners,
 * accent800 fill, 1dp accent border, pencil in accent200.
 */
@Composable
fun PinotComposeFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val spacing = LocalPinotSpacing.current
    val shape = RoundedCornerShape(shapes.fab)

    Box(
        modifier = modifier
            .size(spacing.fabSize)
            .clip(shape)
            .background(colors.accent800)
            .border(1.dp, colors.accent, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(PinotIcons.Edit),
            contentDescription = "Compose",
            tint = colors.accent200,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Preview(name = "FAB · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotComposeFabPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        PinotComposeFab(onClick = {})
    }
}

@Preview(name = "FAB · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotComposeFabPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        PinotComposeFab(onClick = {})
    }
}
