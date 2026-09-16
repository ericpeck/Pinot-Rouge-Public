package com.pinotrouge.messaging.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography
import java.util.Locale

/**
 * Surface fill, md radius, optional elev-sm (1dp neutral800 hairline).
 * Optional uppercase accent kicker.
 */
@Composable
fun PinotCard(
    modifier: Modifier = Modifier,
    kicker: String? = null,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val shape = RoundedCornerShape(shapes.md)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .then(
                if (elevated) {
                    Modifier.border(1.dp, colors.shadowSmBorder, shape)
                } else {
                    Modifier
                },
            )
            .padding(12.dp),
    ) {
        if (kicker != null) {
            Text(
                text = kicker.uppercase(Locale.getDefault()),
                style = PinotTypography.labelSmall,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

@Preview(name = "Card · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotCardPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        val colors = LocalPinotColors.current
        PinotCard(kicker = "Filter", elevated = true, modifier = Modifier.padding(12.dp)) {
            Text("Links from people I do not know", style = PinotTypography.titleMedium, color = colors.text)
            Text("34 caught this month", style = PinotTypography.bodySmall, color = colors.dimmer)
        }
    }
}

@Preview(name = "Card · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotCardPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        val colors = LocalPinotColors.current
        PinotCard(kicker = "Filter", elevated = true, modifier = Modifier.padding(12.dp)) {
            Text("Links from people I do not know", style = PinotTypography.titleMedium, color = colors.text)
            Text("34 caught this month", style = PinotTypography.bodySmall, color = colors.dimmer)
        }
    }
}
