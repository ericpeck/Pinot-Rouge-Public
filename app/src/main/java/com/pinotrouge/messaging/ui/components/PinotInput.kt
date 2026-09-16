package com.pinotrouge.messaging.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * Surface fill, divider border, accent caret, accent border on focus.
 */
@Composable
fun PinotInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines.coerceAtLeast(2),
        maxLines = maxLines,
        textStyle = PinotTypography.bodyMedium.copy(color = colors.text),
        label = label?.let { { Text(it, style = PinotTypography.bodySmall) } },
        placeholder = placeholder?.let {
            {
                Text(
                    it,
                    style = PinotTypography.bodyMedium,
                    color = colors.dimmer,
                )
            }
        },
        shape = RoundedCornerShape(shapes.md),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surface.copy(alpha = colors.disabledOpacity),
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.divider,
            disabledBorderColor = colors.divider.copy(alpha = colors.disabledOpacity),
            cursorColor = colors.accent,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.dim,
            focusedPlaceholderColor = colors.dimmer,
            unfocusedPlaceholderColor = colors.dimmer,
        ),
    )
}

@Preview(name = "Input · Dark", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotInputPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        PinotInput(
            value = "sale, % off, coupon",
            onValueChange = {},
            label = "Contains any of",
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(name = "Input · Light", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotInputPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        PinotInput(
            value = "",
            onValueChange = {},
            placeholder = "Filter name",
            modifier = Modifier.padding(12.dp),
        )
    }
}
