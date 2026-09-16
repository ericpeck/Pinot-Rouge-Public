package com.pinotrouge.messaging.ui.components

import androidx.annotation.DrawableRes
import com.pinotrouge.messaging.R

/**
 * Phosphor Duotone glyphs as vector drawables. Call sites use
 * `Icon(painter = painterResource(PinotIcons.X), …)`.
 *
 * Do not substitute Material filled icons — wrong weight for this system.
 * Full set of 37 is under `res/drawable/ic_*`; 15 mapped here are in current use.
 */
object PinotIcons {
    @DrawableRes val Chat = R.drawable.ic_chat_teardrop_text
    @DrawableRes val Search = R.drawable.ic_magnifying_glass
    @DrawableRes val Settings = R.drawable.ic_gear_six
    @DrawableRes val Filter = R.drawable.ic_funnel_simple
    @DrawableRes val Sliders = R.drawable.ic_sliders_horizontal
    @DrawableRes val Back = R.drawable.ic_caret_left
    @DrawableRes val Check = R.drawable.ic_check
    /** Onboarding checklist ticks — circular duotone plate, not the square `check`. */
    @DrawableRes val CheckCircle = R.drawable.ic_check_circle
    @DrawableRes val Plus = R.drawable.ic_plus
    @DrawableRes val Close = R.drawable.ic_x
    @DrawableRes val Trash = R.drawable.ic_trash
    @DrawableRes val More = R.drawable.ic_dots_three_vertical
    @DrawableRes val Phone = R.drawable.ic_phone
    @DrawableRes val Video = R.drawable.ic_video_camera
    @DrawableRes val Send = R.drawable.ic_paper_plane_tilt
    @DrawableRes val Edit = R.drawable.ic_pencil_simple
    @DrawableRes val ShieldCheck = R.drawable.ic_shield_check
    @DrawableRes val Archive = R.drawable.ic_archive
    @DrawableRes val Unarchive = R.drawable.ic_arrow_counter_clockwise
}
