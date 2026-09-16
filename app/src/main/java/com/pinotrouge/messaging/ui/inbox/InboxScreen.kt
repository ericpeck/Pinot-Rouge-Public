package com.pinotrouge.messaging.ui.inbox

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotCheckbox
import com.pinotrouge.messaging.ui.components.PinotChip
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotMinTouchTarget
import com.pinotrouge.messaging.ui.components.PinotToast
import com.pinotrouge.messaging.ui.nav.LocalInboxSelectionActive
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTextStyles
import com.pinotrouge.messaging.ui.theme.PinotTypography
import kotlinx.coroutines.flow.distinctUntilChanged

private const val INBOX_LOAD_MORE_THRESHOLD = 6

/**
 * Inbox tab entry point. ViewModel via hiltViewModel(); NavHost calls this
 * fully-qualified with no import (Wave 3 PinotNavHost protocol).
 */
@Composable
fun InboxRoute(
    onOpenThread: (threadId: Long) -> Unit,
    onOpenHeld: () -> Unit = {},
    /** Opens the Search screen (pill is navigation, not in-list filter). */
    onOpenSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshRole()
        viewModel.refresh()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.clearContactsCache()
        viewModel.refreshPermissions()
        viewModel.refresh()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshRole()
                viewModel.refreshPermissions()
                viewModel.refresh(quiet = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.selectionActive) {
        viewModel.exitSelection()
    }

    // Hoist selection into the scaffold so the compose FAB hides mid-select
    // (same class of bug as fix/fab-scope). Scaffold provides the holder.
    val inboxSelectionHolder = LocalInboxSelectionActive.current
    LaunchedEffect(state.selectionActive) {
        inboxSelectionHolder.value = state.selectionActive
    }
    DisposableEffect(Unit) {
        onDispose { inboxSelectionHolder.value = false }
    }

    InboxScreen(
        state = state,
        onOpenThread = onOpenThread,
        onOpenHeld = onOpenHeld,
        onOpenSearch = onOpenSearch,
        onSelectChip = viewModel::selectChip,
        onCopyOtp = viewModel::copyOtp,
        onStartSelection = viewModel::startSelection,
        onToggleSelection = viewModel::toggleSelection,
        onExitSelection = viewModel::exitSelection,
        onRequestDelete = viewModel::requestDeleteSelected,
        onConfirmDelete = viewModel::confirmDeleteSelected,
        onDismissDeleteConfirm = viewModel::dismissDeleteConfirm,
        onArchiveSelected = viewModel::archiveSelected,
        onUndoDelete = viewModel::undoPendingDelete,
        onDismissUndoToast = viewModel::dismissUndoToast,
        onRequestRole = {
            val intent = viewModel.roleRequestIntent()
            if (intent != null) {
                roleLauncher.launch(intent)
            } else {
                viewModel.refreshRole()
                viewModel.refresh()
            }
        },
        onRequestReadPermission = {
            val perms = viewModel.previewPermissionsToRequest()
            if (perms.isNotEmpty()) {
                permissionLauncher.launch(perms)
            } else {
                viewModel.refreshPermissions()
                viewModel.refresh()
            }
        },
        onLoadMore = viewModel::loadMoreThreads,
        modifier = modifier,
    )
}

@Composable
fun InboxScreen(
    state: InboxUiState,
    onOpenThread: (threadId: Long) -> Unit,
    onSelectChip: (InboxChip) -> Unit,
    onCopyOtp: (threadId: Long, code: String) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenHeld: () -> Unit = {},
    onStartSelection: (threadId: Long) -> Unit = {},
    onToggleSelection: (threadId: Long) -> Unit = {},
    onExitSelection: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDeleteConfirm: () -> Unit = {},
    onArchiveSelected: () -> Unit = {},
    onUndoDelete: () -> Unit = {},
    onDismissUndoToast: () -> Unit = {},
    onRequestRole: () -> Unit = {},
    onRequestReadPermission: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val rows = state.filteredThreads
    val listBody = resolveInboxListBody(
        isLoading = state.isLoading,
        canReadMessages = state.canReadMessages,
        filteredEmpty = rows.isEmpty(),
        hasAnyThreads = state.threads.isNotEmpty(),
        pendingDeleteCount = state.pendingDeleteCount,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.selectionActive) {
                SelectionTopBar(
                    selectedCount = state.selectedCount,
                    showArchive = state.selectedCount > 0,
                    showDelete = state.roleHeld && state.selectedCount > 0,
                    onClose = onExitSelection,
                    onArchive = onArchiveSelected,
                    onDelete = onRequestDelete,
                )
            } else {
                // Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.screenPadding, end = spacing.screenPadding, top = 14.dp, bottom = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.inbox_title),
                            style = PinotTypography.headlineSmall,
                            color = colors.text,
                        )
                        if (state.unreadCount > 0) {
                            Text(
                                text = stringResource(R.string.inbox_unread_count, state.unreadCount),
                                style = PinotTypography.bodySmall.copy(fontSize = 11.5.sp),
                                color = colors.dimmer,
                                modifier = Modifier.padding(bottom = 1.dp),
                            )
                        }
                    }
                    // Persistent preview-mode banner — only when ROLE_SMS is not held.
                    if (!state.roleHeld) {
                        Spacer(Modifier.height(12.dp))
                        PreviewModeBanner(onRequestRole = onRequestRole)
                    }
                    Spacer(Modifier.height(12.dp))
                    // Version 5: pill navigates to Search — not an in-list filter.
                    SearchPill(onClick = onOpenSearch)
                }

                // Category chips (variant a)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = spacing.screenPadding, end = spacing.screenPadding, top = 4.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    InboxChip.entries.forEach { chip ->
                        PinotChip(
                            label = chip.label,
                            selected = state.selectedChip == chip,
                            onClick = { onSelectChip(chip) },
                        )
                    }
                }

                // Quiet held line — title block → chips → this → list. Absent at 0.
                if (state.heldThisWeekCount > 0) {
                    HeldByFiltersLine(
                        count = state.heldThisWeekCount,
                        onClick = onOpenHeld,
                    )
                }
            }

            // List body
            when (listBody) {
                InboxListBody.Loading -> {
                    Spacer(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
                InboxListBody.NeedsPermission -> {
                    PermissionNeededInbox(
                        onRequestPermission = onRequestReadPermission,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
                InboxListBody.Empty, InboxListBody.EmptyFilter -> {
                    EmptyInbox(
                        hasAnyThreads = listBody == InboxListBody.EmptyFilter,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
                InboxListBody.Threads -> {
                    // Capture before the new rows land — after a prepend with
                    // stable keys, firstVisibleItemIndex is already 1.
                    val atTop = remember(listState) {
                        derivedStateOf {
                            listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset <= 8
                        }
                    }
                    val wasAtTop = atTop.value // capture by value, during composition
                    LaunchedEffect(rows) {
                        if (wasAtTop) {
                            listState.scrollToItem(0)
                        }
                    }
                    LaunchedEffect(listState, state.hasMoreThreads) {
                        if (!state.hasMoreThreads) return@LaunchedEffect
                        snapshotFlow {
                            val info = listState.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            val total = info.totalItemsCount
                            total > 0 && last >= total - INBOX_LOAD_MORE_THRESHOLD
                        }.distinctUntilChanged().collect { nearEnd ->
                            if (nearEnd) onLoadMore()
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = spacing.scrollBottomInset),
                    ) {
                        items(
                            items = rows,
                            key = { it.threadId },
                        ) { thread ->
                            ThreadRow(
                                thread = thread,
                                showOtpCopy = state.showOtpCopy && !state.selectionActive,
                                otpCopied = state.copiedThreadId == thread.threadId,
                                selectionActive = state.selectionActive,
                                selected = thread.threadId in state.selectedThreadIds,
                                onOpen = { onOpenThread(thread.threadId) },
                                onLongPress = {
                                    if (state.roleHeld) onStartSelection(thread.threadId)
                                },
                                onToggleSelect = { onToggleSelection(thread.threadId) },
                                onCopyOtp = { code -> onCopyOtp(thread.threadId, code) },
                            )
                        }
                        item(key = "footer") {
                            Text(
                                text = stringResource(R.string.inbox_footer),
                                style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                                color = colors.dimmer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 18.dp,
                                        bottom = 4.dp,
                                        start = spacing.screenPadding,
                                        end = spacing.screenPadding,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        if (state.undoToastCount > 0) {
            val toastMessage = if (state.undoIsArchive) {
                pluralStringResource(
                    R.plurals.batch_archived_conversations,
                    state.undoToastCount,
                    state.undoToastCount,
                )
            } else {
                pluralStringResource(
                    R.plurals.batch_deleted_conversations,
                    state.undoToastCount,
                    state.undoToastCount,
                )
            }
            PinotToast(
                message = toastMessage,
                durationMs = 5_000L,
                actionLabel = stringResource(R.string.batch_undo),
                onAction = onUndoDelete,
                onDismiss = onDismissUndoToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
            )
        }
    }

    if (state.showDeleteConfirm) {
        BatchDeleteConfirmDialog(
            title = pluralStringResource(
                R.plurals.batch_delete_conversations_title,
                state.selectedCount,
                state.selectedCount,
            ),
            body = stringResource(R.string.batch_delete_conversations_body),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDeleteConfirm,
        )
    }
}

/**
 * Quiet filtering affordance on Chats. Version 3 default (not the accent900
 * promoted card). Fixed above the list — does not scroll with conversations.
 */
@Composable
private fun HeldByFiltersLine(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val shapes = LocalPinotShapes.current
    val a11y = stringResource(R.string.inbox_held_line_a11y)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenPadding)
            .heightIn(min = PinotMinTouchTarget)
            .clip(RoundedCornerShape(shapes.sm))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = a11y
                role = Role.Button
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            painter = painterResource(PinotIcons.Filter),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.inbox_held_this_week, count),
            style = PinotTextStyles.meta,
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_caret_right),
            contentDescription = null,
            tint = colors.dimmer,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    showArchive: Boolean,
    showDelete: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
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
            if (showArchive) {
                PinotIconButton(
                    onClick = onArchive,
                    contentDescription = stringResource(R.string.batch_archive_action),
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.Archive),
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (showDelete) {
                PinotIconButton(
                    onClick = onDelete,
                    contentDescription = stringResource(R.string.batch_delete_action),
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.Trash),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (!showArchive) {
                Spacer(Modifier.size(PinotMinTouchTarget))
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
private fun BatchDeleteConfirmDialog(
    title: String,
    body: String,
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
            Text(text = title, style = PinotTypography.titleMedium)
        },
        text = {
            Text(text = body, style = PinotTypography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.batch_delete_confirm),
                    style = PinotTypography.labelLarge,
                    color = colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.batch_delete_cancel),
                    style = PinotTypography.labelLarge,
                    color = colors.dim,
                )
            }
        },
    )
}

/**
 * Non-dismissible notice when ROLE_SMS is not held.
 * Filtering is off — same plain voice as Settings. Not a soft tip.
 */
@Composable
private fun PreviewModeBanner(onRequestRole: () -> Unit) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val shape = RoundedCornerShape(shapes.md)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accent900)
            .border(1.dp, colors.accent800, shape)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.inbox_preview_banner_title),
            style = PinotTypography.bodyLarge.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.inbox_preview_banner_body),
            style = PinotTypography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = colors.dim,
        )
        Spacer(Modifier.height(10.dp))
        PinotButton(
            text = stringResource(R.string.inbox_preview_banner_cta),
            onClick = onRequestRole,
            variant = PinotButtonVariant.Primary,
            fillMaxWidth = true,
        )
    }
}

@Composable
private fun SearchPill(
    onClick: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val label = stringResource(R.string.inbox_search_placeholder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.surface)
            .border(1.dp, colors.divider, RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(PinotIcons.Search),
            contentDescription = null,
            tint = colors.dimmer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = PinotTypography.bodyMedium.copy(fontSize = 13.sp),
            color = colors.dimmer,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    thread: InboxThreadUi,
    showOtpCopy: Boolean,
    otpCopied: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onCopyOtp: (code: String) -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val spacing = LocalPinotSpacing.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val showOtp = showOtpCopy && !thread.otpCode.isNullOrBlank()
    // Version 3: solid divider token on every row (including the last).
    val rowDivider = colors.divider

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.rowMinHeight)
                .background(if (pressed) colors.pressedOverlay else colors.bg)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        if (selectionActive) onToggleSelect() else onOpen()
                    },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = spacing.screenPadding, vertical = spacing.rowPaddingV),
            horizontalArrangement = Arrangement.spacedBy(spacing.xl),
            verticalAlignment = Alignment.Top,
        ) {
            if (selectionActive) {
                PinotCheckbox(
                    checked = selected,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            // Avatar — Version 3/4: 44dp circle; groups use users-three, not initials.
            Box(
                modifier = Modifier
                    .size(shapes.avatar)
                    .clip(CircleShape)
                    .background(colors.accent800)
                    .border(1.dp, colors.accent300, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (thread.isGroup) {
                    Icon(
                        painter = painterResource(R.drawable.ic_users_three),
                        contentDescription = null,
                        tint = colors.accent100,
                        modifier = Modifier.size(21.dp),
                    )
                } else {
                    Text(
                        text = thread.initials,
                        style = PinotTypography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.02.em,
                        ),
                        color = colors.accent100,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = thread.displayName,
                        style = PinotTypography.titleMedium.copy(
                            fontSize = 14.5.sp,
                            fontWeight = if (thread.unread) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (thread.isGroup && thread.participantCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.inbox_people_count,
                                thread.participantCount,
                            ),
                            style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                            color = colors.accent300,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(shapes.pill))
                                .border(1.dp, colors.accent800, RoundedCornerShape(shapes.pill))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        text = thread.timeLabel,
                        style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                        color = colors.dimmer,
                    )
                }
                Text(
                    text = thread.preview,
                    style = PinotTypography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = colors.dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (showOtp) {
                    val code = thread.otpCode.orEmpty()
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PinotButton(
                            text = if (otpCopied) {
                                stringResource(R.string.inbox_otp_copied, code)
                            } else {
                                stringResource(R.string.inbox_otp_copy, code)
                            },
                            onClick = { onCopyOtp(code) },
                            variant = PinotButtonVariant.Primary,
                        )
                        Text(
                            text = stringResource(R.string.inbox_otp_expires),
                            style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                            color = colors.dimmer,
                        )
                    }
                }
            }

            if (thread.unread) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            } else {
                // Keep row alignment stable when some rows have a trailing dot.
                Spacer(Modifier.width(7.dp))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(rowDivider),
        )
    }
}

@Composable
private fun EmptyInbox(
    hasAnyThreads: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (hasAnyThreads) R.string.inbox_empty_filter else R.string.inbox_empty,
            ),
            style = PinotTypography.bodyMedium,
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
        if (!hasAnyThreads) {
            Text(
                text = stringResource(R.string.inbox_empty_hint),
                style = PinotTypography.bodySmall,
                color = colors.dimmer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Empty list that is **not** an empty inbox — we simply cannot read the
 * provider. Distinct copy + CTA so this never looks like "No messages yet".
 */
@Composable
private fun PermissionNeededInbox(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.inbox_permission_title),
            style = PinotTypography.bodyLarge.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.inbox_permission_body),
            style = PinotTypography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PinotButton(
            text = stringResource(R.string.inbox_permission_cta),
            onClick = onRequestPermission,
            variant = PinotButtonVariant.Primary,
        )
    }
}

// —— Sample data for previews ——

private fun previewThreads(): List<InboxThreadUi> = listOf(
    InboxThreadUi(
        threadId = 1,
        address = "+15551234567",
        displayName = "Mom",
        initials = "M",
        preview = "Are you still coming Sunday? Bring the folding chairs if you can.",
        timeLabel = "9:14",
        dateMillis = 0L,
        unread = true,
        category = ThreadCategory.People,
        otpCode = null,
    ),
    InboxThreadUi(
        threadId = 2,
        address = "88022",
        displayName = "882-04 verification",
        initials = "#",
        preview = "Your Northgate Bank code is 882041. Never share it.",
        timeLabel = "9:02",
        dateMillis = 0L,
        unread = true,
        category = ThreadCategory.Codes,
        otpCode = "882041",
    ),
    InboxThreadUi(
        threadId = 3,
        address = "+15559876543",
        displayName = "Dev Patel",
        initials = "DP",
        preview = "Sent the photos from Saturday — the light was unreal",
        timeLabel = "8:31",
        dateMillis = 0L,
        unread = false,
        category = ThreadCategory.People,
        otpCode = null,
    ),
    InboxThreadUi(
        threadId = 4,
        address = "BANK",
        displayName = "Northgate Bank",
        initials = "NB",
        preview = "Card ending 4417: $84.20 at Rowan Grocery.",
        timeLabel = "Yesterday",
        dateMillis = 0L,
        unread = false,
        category = ThreadCategory.Money,
        otpCode = null,
    ),
    InboxThreadUi(
        threadId = 6,
        address = "DELTA",
        displayName = "Delta",
        initials = "DL",
        preview = "Flight DL428 now departs gate B12.",
        timeLabel = "Mon",
        dateMillis = 0L,
        unread = false,
        category = ThreadCategory.Travel,
        otpCode = null,
    ),
)

// —— Previews ——

@Preview(name = "Inbox · Dark · Populated", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewDarkPopulated() {
    val threads = previewThreads()
    PinotRougeTheme(darkTheme = true) {
        InboxScreen(
            state = InboxUiState(
                threads = threads,
                filteredThreads = threads,
                unreadCount = 2,
                isLoading = false,
                roleHeld = true,
                heldThisWeekCount = 3,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Light · Populated", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewLightPopulated() {
    val threads = previewThreads()
    PinotRougeTheme(darkTheme = false) {
        InboxScreen(
            state = InboxUiState(
                threads = threads,
                filteredThreads = threads,
                unreadCount = 2,
                isLoading = false,
                roleHeld = true,
                heldThisWeekCount = 3,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Dark · Not default", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewDarkNotDefault() {
    val threads = previewThreads()
    PinotRougeTheme(darkTheme = true) {
        InboxScreen(
            state = InboxUiState(
                threads = threads,
                filteredThreads = threads,
                unreadCount = 2,
                isLoading = false,
                roleHeld = false,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Light · Not default", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewLightNotDefault() {
    val threads = previewThreads()
    PinotRougeTheme(darkTheme = false) {
        InboxScreen(
            state = InboxUiState(
                threads = threads,
                filteredThreads = threads,
                unreadCount = 2,
                isLoading = false,
                roleHeld = false,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Dark · Empty", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewDarkEmpty() {
    PinotRougeTheme(darkTheme = true) {
        InboxScreen(
            state = InboxUiState(isLoading = false, roleHeld = true, canReadMessages = true),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Light · Empty", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewLightEmpty() {
    PinotRougeTheme(darkTheme = false) {
        InboxScreen(
            state = InboxUiState(isLoading = false, roleHeld = true, canReadMessages = true),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Dark · Needs permission", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewDarkNeedsPermission() {
    PinotRougeTheme(darkTheme = true) {
        InboxScreen(
            state = InboxUiState(
                isLoading = false,
                roleHeld = false,
                canReadMessages = false,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Light · Needs permission", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun InboxPreviewLightNeedsPermission() {
    PinotRougeTheme(darkTheme = false) {
        InboxScreen(
            state = InboxUiState(
                isLoading = false,
                roleHeld = false,
                canReadMessages = false,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Dark · OTP row", showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun InboxPreviewDarkOtp() {
    val otpOnly = listOf(previewThreads()[1])
    PinotRougeTheme(darkTheme = true) {
        InboxScreen(
            state = InboxUiState(
                threads = otpOnly,
                filteredThreads = otpOnly,
                unreadCount = 1,
                showOtpCopy = true,
                isLoading = false,
                roleHeld = true,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}

@Preview(name = "Inbox · Light · OTP row", showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun InboxPreviewLightOtp() {
    val otpOnly = listOf(previewThreads()[1])
    PinotRougeTheme(darkTheme = false) {
        InboxScreen(
            state = InboxUiState(
                threads = otpOnly,
                filteredThreads = otpOnly,
                unreadCount = 1,
                showOtpCopy = true,
                isLoading = false,
                roleHeld = true,
            ),
            onOpenThread = {},
            onSelectChip = {},
            onOpenSearch = {},
            onCopyOtp = { _, _ -> },
        )
    }
}
