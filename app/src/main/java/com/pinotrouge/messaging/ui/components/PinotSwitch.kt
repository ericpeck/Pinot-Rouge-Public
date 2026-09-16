package com.pinotrouge.messaging.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme

/**
 * Custom 40×24 pill switch — **not** material3.Switch.
 * Knob is 18dp; travel uses [PinotMotion]-scale duration (~200ms here until motion wiring).
 *
 * The visible track stays 40×24dp and sits inside a [PinotMinTouchTarget] zone.
 * Do not scale the track up to meet the Material minimum.
 *
 * Pass a non-null [onCheckedChange] when this switch is the interactive element
 * (Filters list: the switch sits outside the drag hit target). Pass null to draw
 * it decorative — no click, no [Role.Switch] — so a parent `.toggleable` row
 * can be the only semantic node.
 */
@Composable
fun PinotSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val duration = 200
    val trackW = spacing.switchWidth
    val trackH = spacing.switchHeight
    val knob = spacing.switchKnob
    val inset = 2.dp
    val knobOnX = trackW - knob - inset

    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.switchTrackOn else colors.switchTrackOff,
        animationSpec = tween(duration),
        label = "switchTrack",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) colors.switchTrackBorderOn else colors.switchTrackBorderOff,
        animationSpec = tween(duration),
        label = "switchBorder",
    )
    val knobColor by animateColorAsState(
        targetValue = if (checked) colors.switchKnobOn else colors.switchKnobOff,
        animationSpec = tween(duration),
        label = "switchKnob",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) knobOnX else inset,
        animationSpec = tween(duration),
        label = "switchKnobOffset",
    )

    val alpha = if (enabled) 1f else colors.disabledOpacity
    val onChange = onCheckedChange
    val boxModifier = if (onChange != null) {
        val interaction = remember { MutableInteractionSource() }
        modifier
            .size(PinotMinTouchTarget)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onClick = { onChange(!checked) },
            )
    } else {
        modifier.size(PinotMinTouchTarget)
    }

    Box(
        modifier = boxModifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = trackW, height = trackH)
                .clip(RoundedCornerShape(percent = 50))
                .background(trackColor.copy(alpha = trackColor.alpha * alpha))
                .border(
                    1.dp,
                    borderColor.copy(alpha = borderColor.alpha * alpha),
                    RoundedCornerShape(percent = 50),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knobOffset)
                    .size(knob)
                    .clip(CircleShape)
                    .background(knobColor.copy(alpha = knobColor.alpha * alpha)),
            )
        }
    }
}

@Preview(name = "Switch · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotSwitchPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        Row(Modifier.padding(12.dp)) {
            PinotSwitch(checked = false, onCheckedChange = {})
            Spacer(Modifier.width(12.dp))
            PinotSwitch(checked = true, onCheckedChange = {})
        }
    }
}

@Preview(name = "Switch · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotSwitchPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        Row(Modifier.padding(12.dp)) {
            PinotSwitch(checked = false, onCheckedChange = {})
            Spacer(Modifier.width(12.dp))
            PinotSwitch(checked = true, onCheckedChange = {})
        }
    }
}
