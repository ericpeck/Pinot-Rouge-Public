package com.pinotrouge.messaging.ui.filtered

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotCheckbox
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotTag
import com.pinotrouge.messaging.ui.components.PinotTagVariant
import com.pinotrouge.messaging.ui.components.PinotToast
import com.pinotrouge.messaging.ui.media.MmsPictureTile
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.media.mmsTileContentDescription
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography
import java.util.Locale

/**
 * Filtered tab entry point. ViewModel via hiltViewModel(); NavHost calls this
 * fully-qualified with no import (Wave 3 PinotNavHost protocol).
 */
@Composable
fun FilteredRoute(
    onOpenHeldMessage: (heldId: String) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FilteredViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.selectionActive) {
        viewModel.exitSelection()
    }
    FilteredScreen(
        state = state,
        modifier = modifier,
        onOpenHeldMessage = onOpenHeldMessage,
        onOpenFilters = onOpenFilters,
        onRequestDeleteAll = viewModel::requestDeleteAll,
        onConfirmDeleteAll = viewModel::confirmDeleteAll,
        onDismissDeleteAll = viewModel::dismissDeleteAllConfirm,
        onDismissToast = viewModel::dismissToast,
        onStartSelection = viewModel::startSelection,
        onToggleSelection = viewModel::toggleSelection,
        onExitSelection = viewModel::exitSelection,
        onRequestBatchDelete = viewModel::requestBatchDelete,
        onConfirmBatchDelete = viewModel::confirmBatchDelete,
        onDismissBatchDelete = viewModel::dismissBatchDeleteConfirm,
        onUndoDelete = viewModel::undoPendingDelete,
    )
}

@Composable
fun FilteredScreen(
    state: FilteredUiState,
    onOpenHeldMessage: (String) -> Unit,
    onOpenFilters: () -> Unit = {},
    onRequestDeleteAll: () -> Unit,
    onConfirmDeleteAll: () -> Unit,
    onDismissDeleteAll: () -> Unit,
    onDismissToast: () -> Unit,
    onStartSelection: (String) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onExitSelection: () -> Unit = {},
    onRequestBatchDelete: () -> Unit = {},
    onConfirmBatchDelete: () -> Unit = {},
    onDismissBatchDelete: () -> Unit = {},
    onUndoDelete: () -> Unit = {},
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.scrollBottomInset),
        ) {
            if (state.selectionActive) {
                FilteredSelectionBar(
                    selectedCount = state.selectedCount,
                    onClose = onExitSelection,
                    onDelete = onRequestBatchDelete,
                )
            } else {
                // Title is OverlayHeader ("Held messages") only — meta under it, no second title.
                Column(
                    modifier = Modifier.padding(
                        start = spacing.screenPadding,
                        end = spacing.screenPadding,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.filtered_held_count, state.heldCount),
                        style = PinotTypography.bodySmall.copy(fontSize = 11.5.sp),
                        color = colors.dimmer,
                    )
                    Text(
                        text = stringResource(R.string.filtered_subtitle),
                        style = PinotTypography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        ),
                        color = colors.dim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (state.heldCount == 0 && !state.undoToastActive) {
                // V3: the empty state proves the promise — confetti, retention copy,
                // Review my filters. No "Delete everything" on an empty list.
                HeldEmptyState(onReviewFilters = onOpenFilters)
            } else {
                state.groups.forEach { group ->
                    FilteredGroupHeader(
                        label = group.label,
                        count = group.count,
                    )
                    group.items.forEach { message ->
                        HeldMessageRow(
                            message = message,
                            selectionActive = state.selectionActive,
                            selected = message.id in state.selectedIds,
                            onToggle = {
                                if (state.selectionActive) {
                                    onToggleSelection(message.id)
                                } else {
                                    onOpenHeldMessage(message.id)
                                }
                            },
                            onLongPress = { onStartSelection(message.id) },
                        )
                    }
                }

                // Populated only — confirm dialog still owns the destructive path.
                if (!state.selectionActive) {
                    Box(Modifier.padding(spacing.screenPadding)) {
                        PinotButton(
                            text = stringResource(R.string.filtered_delete_everything),
                            onClick = onRequestDeleteAll,
                            variant = PinotButtonVariant.Secondary,
                            fillMaxWidth = true,
                        )
                    }
                }
            }
        }

        if (state.undoToastActive) {
            PinotToast(
                message = pluralStringResource(
                    R.plurals.batch_deleted_held,
                    state.undoToastCount,
                    state.undoToastCount,
                ),
                durationMs = 5_000L,
                actionLabel = stringResource(R.string.batch_undo),
                onAction = onUndoDelete,
                onDismiss = onDismissToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
            )
        } else {
            PinotToast(
                message = state.toastMessage,
                onDismiss = onDismissToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
            )
        }
    }

    if (state.showDeleteAllConfirm) {
        DeleteAllConfirmDialog(
            onConfirm = onConfirmDeleteAll,
            onDismiss = onDismissDeleteAll,
        )
    }
    if (state.showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = onDismissBatchDelete,
            containerColor = colors.surface,
            titleContentColor = colors.text,
            textContentColor = colors.dim,
            title = {
                Text(
                    text = pluralStringResource(
                        R.plurals.batch_delete_held_title,
                        state.selectedCount,
                        state.selectedCount,
                    ),
                    style = PinotTypography.titleMedium,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.batch_delete_held_body),
                    style = PinotTypography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmBatchDelete) {
                    Text(
                        text = stringResource(R.string.batch_delete_confirm),
                        style = PinotTypography.labelLarge,
                        color = colors.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBatchDelete) {
                    Text(
                        text = stringResource(R.string.batch_delete_cancel),
                        style = PinotTypography.labelLarge,
                        color = colors.dim,
                    )
                }
            },
        )
    }
}

@Composable
private fun FilteredSelectionBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalPinotColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PinotIconButton(
                onClick = onClose,
                contentDescription = stringResource(R.string.batch_close_selection),
            ) {
                Icon(
                    painter = painterResource(PinotIcons.Close),
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = pluralStringResource(
                    R.plurals.batch_selected_count,
                    selectedCount,
                    selectedCount,
                ),
                style = PinotTypography.titleMedium.copy(fontSize = 15.sp),
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            PinotIconButton(
                onClick = onDelete,
                contentDescription = stringResource(R.string.batch_delete_action),
                enabled = selectedCount > 0,
            ) {
                Icon(
                    painter = painterResource(PinotIcons.Trash),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )
    }
}

@Composable
private fun FilteredGroupHeader(
    label: String,
    count: Int,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.screenPadding, end = spacing.screenPadding, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = PinotTypography.labelSmall, // 10.5sp, 0.1em tracking
            color = colors.accent,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.divider),
        )
        Text(
            text = count.toString(),
            style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
            color = colors.dimmer,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeldMessageRow(
    message: HeldMessageUi,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val divider = colors.divider
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg),
    ) {
        // Summary — tap opens full-screen held message; long-press starts batch select.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.rowMinHeightCompact)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onToggle,
                    onLongClick = onLongPress,
                )
                .padding(horizontal = spacing.screenPadding, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selectionActive) {
                    PinotCheckbox(
                        checked = selected,
                        modifier = Modifier.padding(end = 2.dp, bottom = 2.dp),
                    )
                }
                Text(
                    text = message.sender,
                    style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = message.timeLabel,
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.dimmer,
                )
            }
            if (message.tiles.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (tile in message.tiles) {
                        MmsPictureTile(
                            tile = tile,
                            contentDescription = mmsTileContentDescription(
                                tile = tile,
                                isOutgoing = false,
                                senderLabel = message.sender,
                                senderAddress = message.sender,
                            ),
                            selected = selected,
                            onClick = onToggle,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
            val hidePhotoSnippet = message.tiles.any { it.kind == MmsTileKind.Photo } &&
                message.preview == SmsRepository.PHOTO_SNIPPET_FALLBACK
            if (message.preview.isNotEmpty() && !hidePhotoSnippet) {
                Text(
                    text = message.preview,
                    style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PinotTag(
                    text = message.reason,
                    variant = PinotTagVariant.Neutral,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(divider),
        )
    }
}

/**
 * Bulk delete is irreversible. Prototype fires immediately; we confirm first.
 * Deviation noted in Log/.
 */
@Composable
private fun DeleteAllConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPinotColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.dim,
        title = {
            Text(
                text = stringResource(R.string.filtered_delete_all_title),
                style = PinotTypography.titleMedium,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.filtered_delete_all_body),
                style = PinotTypography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.filtered_delete_all_confirm),
                    style = PinotTypography.labelLarge,
                    color = colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.filtered_delete_all_cancel),
                    style = PinotTypography.labelLarge,
                    color = colors.dim,
                )
            }
        },
    )
}

// —— Sample state for previews ——

private val previewGroups = listOf(
    FilteredGroup(
        ruleId = "r3",
        label = "Loan and crypto offers",
        items = listOf(
            HeldMessageUi(
                id = "f1",
                sender = "+1 (844) 555-0192",
                timeLabel = "10:41",
                preview = "Your vehicle warranty is expiring, press 1 to…",
                reason = "Unknown sender · money words",
                body = "Your vehicle warranty is expiring. Press 1 to speak with a specialist about renewal pricing before your coverage lapses.",
                ruleId = "r3",
                ruleName = "Loan and crypto offers",
            ),
            HeldMessageUi(
                id = "f2",
                sender = "LOANFAST",
                timeLabel = "9:12",
                preview = "PRE-APPROVED for $5,000 — no credit check",
                reason = "Filter: Loan and crypto offers",
                body = "PRE-APPROVED for $5,000 with no credit check. Reply YES to claim your funds today.",
                ruleId = "r3",
                ruleName = "Loan and crypto offers",
            ),
            HeldMessageUi(
                id = "f-photo",
                sender = "18885550177",
                timeLabel = "8:05",
                preview = "(Photo)",
                reason = "Filter: Loan and crypto offers",
                body = "",
                ruleId = "r3",
                ruleName = "Loan and crypto offers",
                tiles = listOf(
                    MmsTile(
                        messageId = 1L,
                        seq = 0,
                        kind = MmsTileKind.Photo,
                    ),
                ),
            ),
        ),
    ),
    FilteredGroup(
        ruleId = "r2",
        label = "Promotions and sales",
        items = listOf(
            HeldMessageUi(
                id = "f4",
                sender = "21212",
                timeLabel = "8:47",
                preview = "FLASH SALE — 70% off everything, today only",
                reason = "Short code · promotional",
                body = "FLASH SALE. 70% off everything today only. Reply STOP to unsubscribe.",
                ruleId = "r2",
                ruleName = "Promotions and sales",
            ),
            HeldMessageUi(
                id = "f5",
                sender = "CARTLY",
                timeLabel = "Yesterday",
                preview = "You left something behind. Here is 15% off.",
                reason = "Short code · promotional",
                body = "You left something behind in your cart. Here is 15% off if you check out in the next hour.",
                ruleId = "r2",
                ruleName = "Promotions and sales",
            ),
        ),
    ),
)

private val previewListState = FilteredUiState(
    groups = previewGroups,
    heldCount = previewGroups.sumOf { it.count },
)

/**
 * Empty Held — confetti + retention promise + Review my filters.
 * Spec: Version 3 HeldScreen empty state (the screen that proves the promise).
 */
@Composable
private fun HeldEmptyState(
    onReviewFilters: () -> Unit,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_confetti),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.filtered_empty_title),
            style = PinotTypography.titleMedium,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(R.string.filtered_empty_body),
            style = PinotTypography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PinotButton(
            text = stringResource(R.string.filtered_empty_review_filters),
            onClick = onReviewFilters,
            variant = PinotButtonVariant.Primary,
        )
    }
}

private val previewEmptyState = FilteredUiState(
    groups = emptyList(),
    heldCount = 0,
)

// —— Previews · both palettes ——

@Preview(name = "Filtered · Dark · List", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun FilteredPreviewDarkList() {
    PinotRougeTheme(darkTheme = true) {
        FilteredScreen(
            state = previewListState,
            onOpenHeldMessage = {},
            onRequestDeleteAll = {},
            onConfirmDeleteAll = {},
            onDismissDeleteAll = {},
            onDismissToast = {},
        )
    }
}

@Preview(name = "Filtered · Light · List", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun FilteredPreviewLightList() {
    PinotRougeTheme(darkTheme = false) {
        FilteredScreen(
            state = previewListState,
            onOpenHeldMessage = {},
            onRequestDeleteAll = {},
            onConfirmDeleteAll = {},
            onDismissDeleteAll = {},
            onDismissToast = {},
        )
    }
}

@Preview(name = "Filtered · Dark · Empty", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun FilteredPreviewDarkEmpty() {
    PinotRougeTheme(darkTheme = true) {
        FilteredScreen(
            state = previewEmptyState,
            onOpenHeldMessage = {},
            onRequestDeleteAll = {},
            onConfirmDeleteAll = {},
            onDismissDeleteAll = {},
            onDismissToast = {},
        )
    }
}

@Preview(name = "Filtered · Light · Empty", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun FilteredPreviewLightEmpty() {
    PinotRougeTheme(darkTheme = false) {
        FilteredScreen(
            state = previewEmptyState,
            onOpenHeldMessage = {},
            onRequestDeleteAll = {},
            onConfirmDeleteAll = {},
            onDismissDeleteAll = {},
            onDismissToast = {},
        )
    }
}
