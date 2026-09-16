package com.pinotrouge.messaging.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * Archive overlay. ViewModel via hiltViewModel(); NavHost calls this
 * fully-qualified with no import (Wave 3 PinotNavHost protocol).
 *
 * Owns [OverlayHeader] so the subtitle count stays live. One header only.
 * Archive is local filing only — Telephony is never written here.
 */
@Composable
fun ArchiveRoute(
    onBack: () -> Unit,
    onOpenThread: (threadId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ArchiveScreen(
        state = state,
        onBack = onBack,
        onOpenThread = onOpenThread,
        onUnarchive = viewModel::unarchive,
        modifier = modifier,
    )
}

@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    onBack: () -> Unit,
    onOpenThread: (threadId: Long) -> Unit,
    onUnarchive: (threadId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        com.pinotrouge.messaging.ui.nav.OverlayHeader(
            title = stringResource(R.string.archive_title),
            subtitle = stringResource(R.string.archive_subtitle, state.count),
            onBack = onBack,
        )
        if (state.rows.isEmpty() && !state.loading) {
            ArchiveEmptyState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = spacing.scrollBottomInset,
                ),
            ) {
                items(state.rows, key = { it.threadId }) { row ->
                    ArchiveRow(
                        row = row,
                        onOpen = { onOpenThread(row.threadId) },
                        onUnarchive = { onUnarchive(row.threadId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveEmptyState(
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_archive),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.archive_empty_title),
            style = PinotTypography.titleMedium,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(R.string.archive_empty_body),
            style = PinotTypography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ArchiveRow(
    row: ArchiveRowUi,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val spacing = LocalPinotSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.rowMinHeightCompact)
            .padding(horizontal = spacing.screenPadding, vertical = spacing.rowPaddingV),
        horizontalArrangement = Arrangement.spacedBy(spacing.xl),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(shapes.avatar)
                .clip(CircleShape)
                .background(colors.accent800)
                .border(1.dp, colors.accent300, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = row.initials,
                style = PinotTypography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.02.em,
                ),
                color = colors.accent100,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onOpen,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.displayName,
                    style = PinotTypography.titleMedium.copy(fontSize = 14.5.sp),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.timeLabel,
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.dimmer,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = row.preview,
                style = PinotTypography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                color = colors.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PinotIconButton(
            onClick = onUnarchive,
            contentDescription = stringResource(R.string.archive_unarchive),
        ) {
            Icon(
                painter = painterResource(PinotIcons.Unarchive),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Preview(name = "Archive · Dark · Empty", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ArchivePreviewEmptyDark() {
    PinotRougeTheme(darkTheme = true) {
        ArchiveScreen(
            state = ArchiveUiState(loading = false),
            onBack = {},
            onOpenThread = {},
            onUnarchive = {},
        )
    }
}

@Preview(name = "Archive · Light · List", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ArchivePreviewListLight() {
    PinotRougeTheme(darkTheme = false) {
        ArchiveScreen(
            state = ArchiveUiState(
                rows = listOf(
                    ArchiveRowUi(
                        threadId = 1,
                        displayName = "CARTLY",
                        initials = "CA",
                        preview = "You left something behind. Here is 15% off.",
                        timeLabel = "Yesterday",
                    ),
                    ArchiveRowUi(
                        threadId = 2,
                        displayName = "Maya Chen",
                        initials = "MC",
                        preview = "See you Thursday — I'll bring the pinot.",
                        timeLabel = "Mon",
                    ),
                ),
                count = 2,
                loading = false,
            ),
            onBack = {},
            onOpenThread = {},
            onUnarchive = {},
        )
    }
}
