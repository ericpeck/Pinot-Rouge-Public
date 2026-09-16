package com.pinotrouge.messaging.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography

enum class PinotTagVariant { Accent, Neutral, Outline }

@Composable
fun PinotTag(
    text: String,
    modifier: Modifier = Modifier,
    variant: PinotTagVariant = PinotTagVariant.Accent,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val shape = RoundedCornerShape(shapes.sm)

    val bg: Color
    val fg: Color
    val border: Color?

    when (variant) {
        PinotTagVariant.Accent -> {
            bg = colors.accent800
            fg = colors.accent100
            border = null
        }
        PinotTagVariant.Neutral -> {
            bg = colors.neutral800
            fg = colors.neutral100
            border = null
        }
        PinotTagVariant.Outline -> {
            bg = Color.Transparent
            fg = colors.accent
            border = colors.accent
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(
                if (border != null) Modifier.border(1.dp, border, shape) else Modifier,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = PinotTypography.labelSmall, color = fg)
    }
}

@Preview(name = "Tag · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotTagPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        androidx.compose.foundation.layout.Row {
            PinotTag("Accent", variant = PinotTagVariant.Accent)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            PinotTag("Neutral", variant = PinotTagVariant.Neutral)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            PinotTag("Outline", variant = PinotTagVariant.Outline)
        }
    }
}

@Preview(name = "Tag · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotTagPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Row {
            PinotTag("Accent", variant = PinotTagVariant.Accent)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            PinotTag("Neutral", variant = PinotTagVariant.Neutral)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            PinotTag("Outline", variant = PinotTagVariant.Outline)
        }
    }
}
