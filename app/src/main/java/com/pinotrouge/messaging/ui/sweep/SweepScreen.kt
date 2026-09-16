package com.pinotrouge.messaging.ui.sweep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotToast
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * *Run filters on my inbox* — scan (read-only) then confirm move.
 * Copy from the task brief (no design reference for this screen).
 */
@Composable
fun SweepRoute(
    modifier: Modifier = Modifier,
    viewModel: SweepViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SweepScreen(
        state = state,
        onShowReview = { viewModel.showReview(true) },
        onHideReview = { viewModel.showReview(false) },
        onConfirmMove = viewModel::confirmMove,
        onUndo = viewModel::undoMove,
        onDismissUndo = viewModel::dismissUndoToast,
        onRescan = viewModel::startScan,
        modifier = modifier,
    )
}

@Composable
fun SweepScreen(
    state: SweepUiState,
    onShowReview: () -> Unit,
    onHideReview: () -> Unit,
    onConfirmMove: () -> Unit,
    onUndo: () -> Unit,
    onDismissUndo: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.screenPadding),
        ) {
            when (state.phase) {
                SweepPhase.Scanning, SweepPhase.Moving -> {
                    Spacer(Modifier.weight(1f))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (state.phase == SweepPhase.Scanning) {
                                stringResource(R.string.sweep_scanning)
                            } else if (state.moveTotal > 0) {
                                stringResource(
                                    R.string.sweep_moving_progress,
                                    state.moveProgress,
                                    state.moveTotal,
                                )
                            } else {
                                stringResource(R.string.sweep_moving)
                            },
                            style = PinotTypography.bodyMedium,
                            color = colors.dim,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                SweepPhase.Preview, SweepPhase.Done -> {
                    if (state.showReview) {
                        ReviewList(
                            matches = state.matches,
                            onBack = onHideReview,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        PreviewBody(
                            state = state,
                            onShowReview = onShowReview,
                            onConfirmMove = onConfirmMove,
                            onRescan = onRescan,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (state.undoToastVisible && state.movedCount > 0) {
            PinotToast(
                message = stringResource(R.string.sweep_moved_toast, state.movedCount),
                actionLabel = stringResource(R.string.sweep_undo),
                onAction = onUndo,
                onDismiss = onDismissUndo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun PreviewBody(
    state: SweepUiState,
    onShowReview: () -> Unit,
    onConfirmMove: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sweep_title),
            style = PinotTypography.headlineSmall,
            color = colors.text,
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.errorMessageRes != null -> {
                Text(
                    text = stringResource(state.errorMessageRes),
                    style = PinotTypography.bodyMedium,
                    color = colors.dim,
                )
                Spacer(Modifier.height(16.dp))
                PinotButton(
                    text = stringResource(R.string.sweep_try_again),
                    onClick = onRescan,
                    fillMaxWidth = true,
                )
            }
            state.noEnabledRules -> {
                Text(
                    text = stringResource(R.string.sweep_no_filters),
                    style = PinotTypography.bodyMedium,
                    color = colors.dim,
                )
            }
            state.phase == SweepPhase.Done && state.movedCount > 0 -> {
                Text(
                    text = stringResource(R.string.sweep_done_body, state.movedCount),
                    style = PinotTypography.bodyMedium,
                    color = colors.text,
                )
                if (state.failedCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sweep_failed_body, state.failedCount),
                        style = PinotTypography.bodySmall,
                        color = colors.dim,
                    )
                }
            }
            state.wouldHold == 0 -> {
                Text(
                    text = stringResource(R.string.sweep_scanned, state.scanned),
                    style = PinotTypography.bodyMedium.copy(fontSize = 13.sp),
                    color = colors.dim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.sweep_none_would_hold),
                    style = PinotTypography.bodyMedium,
                    color = colors.text,
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.sweep_scanned, state.scanned),
                    style = PinotTypography.bodyMedium.copy(fontSize = 13.sp),
                    color = colors.dim,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.sweep_would_hold, state.wouldHold),
                    style = PinotTypography.bodyLarge.copy(fontSize = 16.sp),
                    color = colors.text,
                )
                if (state.caughtFromContacts > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.sweep_contacts_warning,
                            state.caughtFromContacts,
                        ),
                        style = PinotTypography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = colors.ink,
                    )
                }
                Spacer(Modifier.height(16.dp))
                state.groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = group.ruleName,
                            style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = group.count.toString(),
                            style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
                            color = colors.ink,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PinotButton(
                        text = stringResource(R.string.sweep_review, state.wouldHold),
                        onClick = onShowReview,
                        variant = PinotButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    PinotButton(
                        text = stringResource(R.string.sweep_move_them),
                        onClick = onConfirmMove,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.scrollBottomInset))
    }
}

@Composable
private fun ReviewList(
    matches: List<InboxSweep.Match>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    Column(modifier = modifier.fillMaxSize()) {
        PinotButton(
            text = stringResource(R.string.sweep_back_to_summary),
            onClick = onBack,
            variant = PinotButtonVariant.Ghost,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(matches, key = { it.providerMessageId }) { match ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(12.dp),
                ) {
                    Text(
                        text = match.ruleName,
                        style = PinotTypography.labelSmall,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = match.sender,
                        style = PinotTypography.bodySmall.copy(fontSize = 12.sp),
                        color = colors.dim,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = match.body,
                        style = PinotTypography.bodyMedium.copy(fontSize = 13.sp),
                        color = colors.text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
