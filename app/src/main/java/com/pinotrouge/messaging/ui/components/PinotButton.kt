package com.pinotrouge.messaging.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography

enum class PinotButtonVariant { Primary, Secondary, Ghost }

/**
 * Primary is an **accent outline on transparent** — never a filled accent block.
 */
@Composable
fun PinotButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PinotButtonVariant = PinotButtonVariant.Primary,
    enabled: Boolean = true,
    fillMaxWidth: Boolean = false,
    @DrawableRes leadingIcon: Int? = null,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val shape = RoundedCornerShape(shapes.md)
    val widthMod = if (fillMaxWidth) modifier.fillMaxWidth() else modifier
    val contentPad = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

    val label: @Composable () -> Unit = {
        if (leadingIcon != null) {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = PinotTypography.labelLarge)
    }

    when (variant) {
        PinotButtonVariant.Primary -> {
            OutlinedButton(
                onClick = onClick,
                modifier = widthMod.heightIn(min = PinotMinTouchTarget),
                enabled = enabled,
                shape = shape,
                border = BorderStroke(1.dp, colors.accent),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = colors.accent,
                    disabledContentColor = colors.accent.copy(alpha = colors.disabledOpacity),
                ),
                contentPadding = contentPad,
            ) { label() }
        }
        PinotButtonVariant.Secondary -> {
            OutlinedButton(
                onClick = onClick,
                modifier = widthMod.heightIn(min = PinotMinTouchTarget),
                enabled = enabled,
                shape = shape,
                border = BorderStroke(1.dp, colors.divider),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = colors.text,
                    disabledContentColor = colors.text.copy(alpha = colors.disabledOpacity),
                ),
                contentPadding = contentPad,
            ) { label() }
        }
        PinotButtonVariant.Ghost -> {
            TextButton(
                onClick = onClick,
                modifier = widthMod.heightIn(min = PinotMinTouchTarget),
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.accent,
                    disabledContentColor = colors.accent.copy(alpha = colors.disabledOpacity),
                ),
                contentPadding = contentPad,
            ) { label() }
        }
    }
}

@Preview(name = "Buttons · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotButtonPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        PinotButtonRow()
    }
}

@Preview(name = "Buttons · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotButtonPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        PinotButtonRow()
    }
}

@Composable
private fun PinotButtonRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        PinotButton("Primary", onClick = {}, variant = PinotButtonVariant.Primary, fillMaxWidth = true)
        Spacer(Modifier.height(8.dp))
        PinotButton("Secondary", onClick = {}, variant = PinotButtonVariant.Secondary, fillMaxWidth = true)
        Spacer(Modifier.height(8.dp))
        PinotButton(
            "Ghost",
            onClick = {},
            variant = PinotButtonVariant.Ghost,
            leadingIcon = PinotIcons.Plus,
        )
    }
}
