package com.pinotrouge.messaging.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * Shared chrome for overlay screens (Filters, Held, Archive, …).
 * Back + title + optional subtitle + trailing actions. Overlays cover the
 * bottom bar entirely — this header is their only way home.
 */
@Composable
fun OverlayHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalPinotColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PinotIconButton(
                onClick = onBack,
                contentDescription = stringResource(R.string.thread_back),
            ) {
                Icon(
                    painter = painterResource(PinotIcons.Back),
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.size(21.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = PinotTypography.titleMedium.copy(fontSize = 16.sp),
                    color = colors.text,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                        color = colors.dimmer,
                    )
                }
            }
            actions()
        }
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
    }
}
