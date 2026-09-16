package com.pinotrouge.messaging.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pinotrouge.messaging.R

/**
 * Inter throughout. Headings weight 500 and never bolder — hierarchy is size and space.
 * Scale from Version 3 / [[02 - Design System (Pinot)]].
 */
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
)

/**
 * Named Pinot text styles. Screens should prefer these over Material slots when they need
 * `preview`, `timestamp`, `kicker`, `meta`, etc.
 */
object PinotTextStyles {
    val display = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.025).em,
    )
    val headline = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.015).em,
    )
    val title = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        letterSpacing = (-0.015).em,
    )
    val titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    )
    val name = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    )
    val nameUnread = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    )
    val body = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
    )
    val bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
    )
    val preview = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    val meta = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    )
    val caption = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
    )
    val timestamp = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
    )
    val kicker = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp,
        letterSpacing = 0.1.em,
    )

    // Legacy aliases used by existing screens — map onto the Version 3 scale.
    val screenTitle get() = title
    val threadName get() = name
    val metadata get() = caption
}

/** Material3 mapping so system components inherit brand type. */
val PinotTypography = Typography(
    displaySmall = PinotTextStyles.display,
    headlineMedium = PinotTextStyles.headline,
    headlineSmall = PinotTextStyles.title,
    titleLarge = PinotTextStyles.title,
    titleMedium = PinotTextStyles.titleSmall,
    titleSmall = PinotTextStyles.name,
    bodyLarge = PinotTextStyles.body,
    bodyMedium = PinotTextStyles.preview,
    bodySmall = PinotTextStyles.caption,
    labelLarge = PinotTextStyles.bodySmall,
    labelMedium = PinotTextStyles.meta,
    labelSmall = PinotTextStyles.kicker,
)
