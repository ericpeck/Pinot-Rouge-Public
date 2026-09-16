package com.pinotrouge.messaging.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * Selectable pill: accent border + accent text + accent-12% fill when selected;
 * divider border + dim text when not.
 */
@Composable
fun PinotChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = PinotMinTouchTarget),
        label = {
            Text(
                text = label,
                style = PinotTypography.labelMedium,
            )
        },
        shape = RoundedCornerShape(shapes.pill),
        border = BorderStroke(
            1.dp,
            if (selected) colors.accent else colors.divider,
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.dim,
            selectedContainerColor = colors.accent.copy(alpha = 0.12f),
            selectedLabelColor = colors.accent,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = colors.text.copy(alpha = colors.disabledOpacity),
            disabledSelectedContainerColor = colors.accent.copy(alpha = 0.06f),
        ),
    )
}

@Preview(name = "Chip · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotChipPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        Row(Modifier.padding(12.dp)) {
            PinotChip("All", selected = true, onClick = {})
            Spacer(Modifier.width(8.dp))
            PinotChip("People", selected = false, onClick = {})
        }
    }
}

@Preview(name = "Chip · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotChipPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        Row(Modifier.padding(12.dp)) {
            PinotChip("All", selected = true, onClick = {})
            Spacer(Modifier.width(8.dp))
            PinotChip("People", selected = false, onClick = {})
        }
    }
}
