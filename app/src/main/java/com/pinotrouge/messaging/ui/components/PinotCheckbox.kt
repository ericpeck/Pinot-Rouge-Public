package com.pinotrouge.messaging.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme

/**
 * Pinot checkbox for batch selection. Not Material3 [androidx.compose.material3.Checkbox]
 * — that widget brings its own colour scheme.
 *
 * 20dp rounded square; checked = accent800 fill + accent300 border + Check in
 * accent100; unchecked = transparent + divider outline.
 */
@Composable
fun PinotCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(shape)
            .background(if (checked) colors.accent800 else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (checked) colors.accent300 else colors.divider,
                shape = shape,
            )
            .semantics {
                this.selected = checked
                this.role = Role.Checkbox
            },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(PinotIcons.Check),
                contentDescription = null,
                tint = colors.accent100,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Preview(name = "Checkbox · Dark · checked", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotCheckboxPreviewDarkChecked() {
    PinotRougeTheme(darkTheme = true) {
        PinotCheckbox(checked = true)
    }
}

@Preview(name = "Checkbox · Dark · unchecked", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotCheckboxPreviewDarkUnchecked() {
    PinotRougeTheme(darkTheme = true) {
        PinotCheckbox(checked = false)
    }
}
