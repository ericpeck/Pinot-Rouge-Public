package com.pinotrouge.messaging.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.prefs.AppSettings
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotCard
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotSwitch
import com.pinotrouge.messaging.ui.components.PinotTag
import com.pinotrouge.messaging.ui.components.PinotTagVariant
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotThemeKey
import com.pinotrouge.messaging.ui.theme.PinotTypography
import com.pinotrouge.messaging.ui.theme.pinotColors
import com.pinotrouge.messaging.ui.theme.swatchColor
import com.pinotrouge.messaging.ui.theme.swatchDotRing
import com.pinotrouge.messaging.ui.theme.swatchSplit

/**
 * Settings tab entry point. ViewModel via hiltViewModel(); NavHost calls this
 * fully-qualified with no import (Wave 3 PinotNavHost protocol).
 */
@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    onOpenFilters: () -> Unit = {},
    onOpenHeld: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenSweep: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshRole()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshRole()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScreen(
        state = state,
        modifier = modifier,
        onToggleHeld = viewModel::setWeeklyDigest,
        onToggleContacts = viewModel::setNeverFilterContacts,
        onToggleOtp = viewModel::setShowOtpCopy,
        onToggleAutoDownload = viewModel::setAutoDownloadPictures,
        onToggleDownloadRoaming = viewModel::setDownloadWhileRoaming,
        onToggleDark = viewModel::setDarkTheme,
        onAccentTheme = viewModel::setAccentTheme,
        onRequestRole = {
            val intent = viewModel.roleRequestIntent()
            if (intent != null) {
                roleLauncher.launch(intent)
            } else {
                viewModel.refreshRole()
            }
        },
        onOpenFilters = onOpenFilters,
        onOpenHeld = onOpenHeld,
        onOpenArchived = onOpenArchived,
        onOpenSweep = onOpenSweep,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onToggleHeld: (Boolean) -> Unit,
    onToggleContacts: (Boolean) -> Unit,
    onToggleOtp: (Boolean) -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit = {},
    onToggleDownloadRoaming: (Boolean) -> Unit = {},
    onToggleDark: (Boolean) -> Unit,
    onAccentTheme: (PinotThemeKey) -> Unit = {},
    onRequestRole: () -> Unit,
    onOpenFilters: () -> Unit = {},
    onOpenHeld: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenSweep: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val settings = state.settings

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenPadding)
            .padding(top = 14.dp, bottom = spacing.scrollBottomInset),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = PinotTypography.headlineSmall,
            color = colors.text,
        )
        Spacer(Modifier.height(12.dp))

        if (state.isDefaultSmsApp) {
            DefaultAppCard()
        } else {
            NotDefaultNotice(onRequestRole = onRequestRole)
        }

        // Version 3 groups: Messages · Filtering · Appearance
        SectionHeader(stringResource(R.string.settings_group_messages))
        SettingsLinkRow(
            title = stringResource(R.string.settings_archived),
            description = stringResource(R.string.settings_archived_desc),
            // Prototype prints 0 — never hide a zero.
            value = state.archivedCount.toString(),
            onClick = onOpenArchived,
        )
        SettingToggleRow(
            name = stringResource(R.string.settings_auto_download),
            description = stringResource(R.string.settings_auto_download_desc),
            checked = settings.autoDownloadPictures,
            onCheckedChange = onToggleAutoDownload,
        )
        SettingToggleRow(
            name = stringResource(R.string.settings_download_roaming),
            description = stringResource(R.string.settings_download_roaming_desc),
            checked = settings.downloadWhileRoaming,
            onCheckedChange = onToggleDownloadRoaming,
        )

        SectionHeader(stringResource(R.string.settings_group_filtering))
        SettingsLinkRow(
            title = stringResource(R.string.settings_filters),
            description = stringResource(R.string.settings_filters_desc),
            // Enabled rules only: "{n} on" (not total rules).
            value = stringResource(R.string.settings_filters_on, state.filtersOnCount),
            onClick = onOpenFilters,
        )
        SettingsLinkRow(
            title = stringResource(R.string.settings_held_messages),
            description = stringResource(R.string.settings_held_messages_desc),
            // Total held — same observeHeldCount() as the Filtered nav badge.
            value = state.heldCount.toString(),
            onClick = onOpenHeld,
        )
        SettingsLinkRow(
            title = stringResource(R.string.settings_run_filters),
            description = stringResource(R.string.settings_run_filters_desc),
            onClick = onOpenSweep,
        )

        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingToggleRow(
                name = stringResource(R.string.settings_held_name),
                description = stringResource(R.string.settings_held_desc),
                checked = settings.holdByDefault,
                onCheckedChange = onToggleHeld,
            )
            SettingToggleRow(
                name = stringResource(R.string.settings_contacts_name),
                description = stringResource(R.string.settings_contacts_desc),
                checked = settings.neverFilterContacts,
                onCheckedChange = onToggleContacts,
            )
            SettingToggleRow(
                name = stringResource(R.string.settings_otp_name),
                description = stringResource(R.string.settings_otp_desc),
                checked = settings.preserveOtps,
                onCheckedChange = onToggleOtp,
            )
        }

        SectionHeader(stringResource(R.string.settings_group_appearance))
        SettingToggleRow(
            name = stringResource(R.string.settings_dark_name),
            description = stringResource(
                if (settings.darkTheme) {
                    R.string.settings_dark_desc_on
                } else {
                    R.string.settings_dark_desc_off
                },
            ),
            checked = settings.darkTheme,
            onCheckedChange = onToggleDark,
        )
        Spacer(Modifier.height(8.dp))
        AccentThemeRow(
            selected = settings.accentTheme,
            darkTheme = settings.darkTheme,
            onSelect = onAccentTheme,
        )

        Text(
            text = stringResource(R.string.settings_footnote),
            style = PinotTypography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 18.sp),
            color = colors.dimmer,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    val colors = LocalPinotColors.current
    Text(
        text = title.uppercase(),
        style = PinotTypography.labelSmall.copy(
            fontSize = 10.5.sp,
            letterSpacing = 0.1.em,
        ),
        color = colors.dim,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

/**
 * Version 5 Accent Color row — nine offered swatches from
 * [PinotThemeKey.offeredAccents] (prototype `accentOptions`), each with its wine
 * label. Selection uses a check mark so it is not hue-only. Whole cell is the
 * TalkBack target. Grigio/blanc are split dots with a ring; rose overrides the
 * swatch hue. Selected ring / 14% bg use the (overridden) swatch colour.
 */
@Composable
private fun AccentThemeRow(
    selected: PinotThemeKey,
    darkTheme: Boolean,
    onSelect: (PinotThemeKey) -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.md))
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_accent_name),
            style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
            color = colors.text,
        )
        Text(
            text = stringResource(R.string.settings_accent_desc),
            style = PinotTypography.bodySmall,
            color = colors.dim,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        // 3-column grid; nine options → three rows (wraps, does not shrink 48dp).
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PinotThemeKey.offeredAccents.chunked(3).forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowKeys.forEach { key ->
                        AccentSwatchCell(
                            key = key,
                            selected = key == selected,
                            darkTheme = darkTheme,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowKeys.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentSwatchCell(
    key: PinotThemeKey,
    selected: Boolean,
    darkTheme: Boolean,
    onSelect: (PinotThemeKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    // Swatch hue can diverge from palette.accent (rose / grigio / blanc).
    val swatch = remember(key, darkTheme) { key.swatchColor(darkTheme) }
    val split = remember(key, darkTheme) { key.swatchSplit(darkTheme) }
    val dotRing = remember(key, darkTheme) { key.swatchDotRing(darkTheme) }
    // onAccent for every theme — including grigio/blanc (literals were identical).
    val checkTint = remember(key, darkTheme) {
        pinotColors(key, darkTheme).onAccent
    }
    val label = stringResource(key.labelRes())
    val desc = if (selected) {
        stringResource(R.string.settings_accent_selected_cd, label)
    } else {
        stringResource(R.string.settings_accent_cd, label)
    }
    Column(
        modifier = modifier
            // Whole cell is the target (prototype min-height 68); keep ≥ 48dp.
            .defaultMinSize(minHeight = 68.dp)
            .clip(RoundedCornerShape(shapes.md))
            .background(
                if (selected) {
                    swatch.copy(alpha = 0.14f)
                } else {
                    colors.bg
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) swatch else colors.divider,
                shape = RoundedCornerShape(shapes.md),
            )
            .semantics {
                contentDescription = desc
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = { onSelect(key) },
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        val dotModifier = if (split != null) {
            val (a, b) = split
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to a,
                            0.5f to a,
                            0.5f to b,
                            1f to b,
                        ),
                        // 135° diagonal across the 22dp dot.
                        start = Offset(0f, 0f),
                        end = Offset(66f, 66f),
                    ),
                )
                .then(
                    if (dotRing != null) {
                        Modifier.border(1.dp, dotRing, CircleShape)
                    } else {
                        Modifier
                    },
                )
        } else {
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(swatch)
        }
        Box(
            modifier = dotModifier,
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(PinotIcons.Check),
                    contentDescription = null,
                    tint = checkTint,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            text = label,
            style = PinotTypography.bodySmall.copy(fontSize = 11.5.sp),
            color = if (selected) colors.text else colors.dim,
            maxLines = 1,
        )
    }
}

/**
 * Wine labels from Version 5 `accentOptions`. Traps: [PinotThemeKey.Amber] is
 * Chardonnay; [PinotThemeKey.Tangerine] is Amber. Bordeaux is unoffered but kept
 * exhaustive so a persisted key still has a label for a11y if ever shown.
 */
@Composable
private fun PinotThemeKey.labelRes(): Int = when (this) {
    PinotThemeKey.Pinot -> R.string.settings_accent_cabernet
    PinotThemeKey.Bordeaux -> R.string.settings_accent_cabernet
    PinotThemeKey.Rose -> R.string.settings_accent_rose
    PinotThemeKey.Violet -> R.string.settings_accent_concord
    PinotThemeKey.Atlantic -> R.string.settings_accent_malbec
    PinotThemeKey.Laurel -> R.string.settings_accent_thompson
    PinotThemeKey.Amber -> R.string.settings_accent_chardonnay
    PinotThemeKey.Tangerine -> R.string.settings_accent_amber
    PinotThemeKey.Grigio -> R.string.settings_accent_grigio
    PinotThemeKey.Blanc -> R.string.settings_accent_blanc
}

/**
 * Settings link row. Optional [value] is the trailing ink-coloured count (fourth
 * `ink` consumer) plus a caret — Version 5 prototype. Null keeps the old layout
 * for any future row without a count.
 */
@Composable
private fun SettingsLinkRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    value: String? = null,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val shapes = LocalPinotShapes.current
    // One TalkBack target: title then value. Caret is decorative (null CD).
    val rowDescription = if (value != null) {
        stringResource(R.string.settings_link_row_a11y, title, value)
    } else {
        title
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(shapes.md))
            .background(colors.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = rowDescription
                role = Role.Button
            }
            .padding(horizontal = 14.dp, vertical = spacing.rowPaddingV)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
                color = colors.text,
            )
            Text(
                text = description,
                style = PinotTypography.bodySmall,
                color = colors.dim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (value != null) {
            // flex: none — title/description take remaining width; value+caret stay put.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = value,
                    style = PinotTypography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.ink,
                    maxLines = 1,
                )
                Icon(
                    // ic_caret_right already vendored; not on PinotIcons map yet.
                    painter = painterResource(R.drawable.ic_caret_right),
                    contentDescription = null,
                    tint = colors.dimmer,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun DefaultAppCard() {
    val colors = LocalPinotColors.current
    PinotCard(elevated = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(PinotIcons.ShieldCheck),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = stringResource(R.string.settings_default_app_title),
                style = PinotTypography.bodyLarge.copy(fontSize = 14.sp),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            PinotTag(
                text = stringResource(R.string.settings_holder_pinot),
                variant = PinotTagVariant.Outline,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_default_app_body),
            style = PinotTypography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.dim,
        )
    }
}

/**
 * Unmissable, non-dismissible notice when ROLE_SMS is not held.
 * Filtering is off — do not soften this copy.
 */
@Composable
private fun NotDefaultNotice(onRequestRole: () -> Unit) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val shape = RoundedCornerShape(shapes.md)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accent900)
            .border(1.dp, colors.accent800, shape)
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(PinotIcons.ShieldCheck),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = stringResource(R.string.settings_role_notice_title),
                style = PinotTypography.bodyLarge.copy(fontSize = 14.sp),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            PinotTag(
                text = stringResource(R.string.settings_holder_other),
                variant = PinotTagVariant.Outline,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_role_notice_body),
            style = PinotTypography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.dim,
        )
        Spacer(Modifier.height(12.dp))
        PinotButton(
            text = stringResource(R.string.settings_role_notice_cta),
            onClick = onRequestRole,
            variant = PinotButtonVariant.Primary,
            fillMaxWidth = true,
        )
    }
}

@Composable
private fun SettingToggleRow(
    name: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val shapes = LocalPinotShapes.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.md))
            .background(colors.surface)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = spacing.rowPaddingV)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
                color = colors.text,
            )
            Text(
                text = description,
                style = PinotTypography.bodySmall,
                color = colors.dim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        PinotSwitch(checked = checked, onCheckedChange = null)
    }
}

// —— Previews ——

@Preview(name = "Settings · Dark · Role held", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SettingsPreviewDarkHeld() {
    PinotRougeTheme(darkTheme = true) {
        SettingsScreen(
            state = SettingsUiState(
                settings = AppSettings(darkTheme = true),
                isDefaultSmsApp = true,
                archivedCount = 3,
                filtersOnCount = 4,
                heldCount = 12,
            ),
            onToggleHeld = {},
            onToggleContacts = {},
            onToggleOtp = {},
            onToggleDark = {},
            onRequestRole = {},
        )
    }
}

@Preview(name = "Settings · Light · Role held", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SettingsPreviewLightHeld() {
    PinotRougeTheme(darkTheme = false) {
        SettingsScreen(
            state = SettingsUiState(
                archivedCount = 3,
                filtersOnCount = 4,
                heldCount = 12,
                settings = AppSettings(darkTheme = false),
                isDefaultSmsApp = true,
            ),
            onToggleHeld = {},
            onToggleContacts = {},
            onToggleOtp = {},
            onToggleDark = {},
            onRequestRole = {},
        )
    }
}

@Preview(name = "Settings · Dark · Not default", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SettingsPreviewDarkNotDefault() {
    PinotRougeTheme(darkTheme = true) {
        SettingsScreen(
            state = SettingsUiState(
                settings = AppSettings(darkTheme = true),
                isDefaultSmsApp = false,
            ),
            onToggleHeld = {},
            onToggleContacts = {},
            onToggleOtp = {},
            onToggleDark = {},
            onRequestRole = {},
        )
    }
}

@Preview(name = "Settings · Light · Not default", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SettingsPreviewLightNotDefault() {
    PinotRougeTheme(darkTheme = false) {
        SettingsScreen(
            state = SettingsUiState(
                settings = AppSettings(darkTheme = false),
                isDefaultSmsApp = false,
            ),
            onToggleHeld = {},
            onToggleContacts = {},
            onToggleOtp = {},
            onToggleDark = {},
            onRequestRole = {},
        )
    }
}
