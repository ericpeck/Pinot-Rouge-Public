package com.pinotrouge.messaging.ui.nav

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * Three-slot bottom bar (Chats · Filtered · Settings).
 *
 * Chats and Settings are [PinotDestination] tabs. Filtered is a bar slot that
 * opens the held overlay — not a tab entry, so FAB scoping stays on Chats only.
 *
 * Row height [LocalPinotSpacing.navHeight] (62dp). Selected: accent icon/label
 * + accent@18% pill. Unselected: dim. Glyphs 22dp per ICONS.md.
 *
 * While Filtered is open, neither Chats nor Settings is highlighted.
 */
@Composable
fun PinotBottomBar(
    selectedTab: PinotDestination?,
    filteredSelected: Boolean,
    onNavigateTab: (PinotDestination) -> Unit,
    onNavigateFiltered: () -> Unit,
    modifier: Modifier = Modifier,
    inboxUnreadCount: Int = 0,
    filteredHeldCount: Int = 0,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    // While Filtered is open, tab highlight is suppressed even if a tab route
    // is still under the overlay on the back stack.
    val chatsSelected = !filteredSelected && selectedTab == PinotDestination.Inbox
    val settingsSelected = !filteredSelected && selectedTab == PinotDestination.Settings

    Column(modifier = modifier.fillMaxWidth().background(colors.bg)) {
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(spacing.navHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomBarItem(
                label = PinotDestination.Inbox.label,
                icon = PinotDestination.Inbox.icon,
                selected = chatsSelected,
                badgeCount = inboxUnreadCount,
                badgeKind = BadgeKind.Unread,
                tabDescription = if (inboxUnreadCount > 0) {
                    pluralStringResource(
                        R.plurals.nav_chats_unread_a11y,
                        inboxUnreadCount,
                        inboxUnreadCount,
                    )
                } else {
                    PinotDestination.Inbox.label
                },
                onClick = { onNavigateTab(PinotDestination.Inbox) },
            )
            BottomBarItem(
                label = stringResource(R.string.nav_filtered),
                icon = PinotIcons.Filter,
                selected = filteredSelected,
                badgeCount = filteredHeldCount,
                badgeKind = BadgeKind.Held,
                tabDescription = if (filteredHeldCount > 0) {
                    pluralStringResource(
                        R.plurals.nav_filtered_held_a11y,
                        filteredHeldCount,
                        filteredHeldCount,
                    )
                } else {
                    stringResource(R.string.nav_filtered)
                },
                onClick = onNavigateFiltered,
            )
            BottomBarItem(
                label = PinotDestination.Settings.label,
                icon = PinotDestination.Settings.icon,
                selected = settingsSelected,
                badgeCount = 0,
                badgeKind = BadgeKind.Unread,
                tabDescription = PinotDestination.Settings.label,
                onClick = { onNavigateTab(PinotDestination.Settings) },
            )
        }
    }
}

private enum class BadgeKind { Unread, Held }

@Composable
private fun RowScope.BottomBarItem(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    badgeCount: Int,
    badgeKind: BadgeKind,
    tabDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val contentColor = if (selected) {
        colors.accent
    } else {
        colors.dim
    }
    val pillColor = if (selected) {
        colors.accent.copy(alpha = 0.18f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = spacing.navHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                this.selected = selected
                contentDescription = tabDescription
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(horizontal = 14.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = contentColor,
                    // ICONS.md: 22dp nav glyphs
                    modifier = Modifier.size(22.dp),
                )
            }
            if (badgeCount > 0) {
                BadgePill(
                    count = badgeCount,
                    kind = badgeKind,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 2.dp),
                )
            }
        }
        Text(
            text = label,
            style = PinotTypography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.sp,
            ),
            color = contentColor,
        )
    }
}

@Composable
private fun BadgePill(
    count: Int,
    kind: BadgeKind,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val label = if (count > 99) "99+" else count.toString()
    val badgeDescription = when (kind) {
        BadgeKind.Unread -> stringResource(R.string.nav_unread_badge_a11y, label)
        BadgeKind.Held -> stringResource(R.string.nav_held_badge_a11y, label)
    }
    Box(
        modifier = modifier
            .sizeIn(minWidth = 15.dp, minHeight = 15.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.accent)
            .padding(horizontal = 4.dp)
            .semantics {
                contentDescription = badgeDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.onAccent,
            fontSize = 9.5.sp,
            lineHeight = 11.sp,
            style = PinotTypography.labelSmall.copy(
                fontSize = 9.5.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Preview(name = "BottomBar · Dark · Chats", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotBottomBarPreviewDarkChats() {
    PinotRougeTheme(darkTheme = true) {
        PinotBottomBar(
            selectedTab = PinotDestination.Inbox,
            filteredSelected = false,
            onNavigateTab = {},
            onNavigateFiltered = {},
            inboxUnreadCount = 2,
            filteredHeldCount = 5,
        )
    }
}

@Preview(name = "BottomBar · Light · Filtered", showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun PinotBottomBarPreviewLightFiltered() {
    PinotRougeTheme(darkTheme = false) {
        PinotBottomBar(
            selectedTab = null,
            filteredSelected = true,
            onNavigateTab = {},
            onNavigateFiltered = {},
            inboxUnreadCount = 2,
            filteredHeldCount = 5,
        )
    }
}

@Preview(name = "BottomBar · Dark · No tab selected", showBackground = true, backgroundColor = 0xFF17141C)
@Composable
private fun PinotBottomBarPreviewDarkNoneSelected() {
    PinotRougeTheme(darkTheme = true) {
        PinotBottomBar(
            selectedTab = null,
            filteredSelected = false,
            onNavigateTab = {},
            onNavigateFiltered = {},
            inboxUnreadCount = 2,
            filteredHeldCount = 0,
        )
    }
}
