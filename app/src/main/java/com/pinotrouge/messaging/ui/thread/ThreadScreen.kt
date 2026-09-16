package com.pinotrouge.messaging.ui.thread

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.ui.components.PinotCheckbox
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotMinTouchTarget
import com.pinotrouge.messaging.ui.components.PinotToast
import com.pinotrouge.messaging.ui.media.DecodeScale
import com.pinotrouge.messaging.ui.media.MmsPictureGrid
import com.pinotrouge.messaging.ui.media.MmsPictureGridMetrics
import com.pinotrouge.messaging.ui.media.MmsPictureTile
import com.pinotrouge.messaging.ui.media.MmsPictureTileHeight
import com.pinotrouge.messaging.ui.media.MmsPictureTileWidth
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.media.mmsTileContentDescription
import com.pinotrouge.messaging.ui.media.rememberMmsPartImage
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography
import com.pinotrouge.messaging.ui.util.resolvePhoneNumber
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * Thread entry point. ViewModel via hiltViewModel(); NavHost calls this
 * fully-qualified with no import (Wave 3 PinotNavHost protocol).
 *
 * [threadId] matches the route arg; the ViewModel also reads it from
 * SavedStateHandle.
 */
@Composable
fun ThreadRoute(
    threadId: Long,
    onBack: () -> Unit,
    onOpenBuilderForSender: (nameOrNumber: String) -> Unit,
    onOpenPhoto: (mmsId: Long, seq: Int) -> Unit = { _, _ -> },
    onSwitchThread: (threadId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshRole()
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.navigateToThreadId) {
        val newId = state.navigateToThreadId ?: return@LaunchedEffect
        viewModel.consumeNavigateToThread()
        if (newId > 0L && newId != threadId) {
            onSwitchThread(newId)
        }
    }

    // Documented for NavHost call sites; ViewModel uses the same route arg.
    @Suppress("UNUSED_PARAMETER")
    val _threadId = threadId

    var menuOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var stubToast by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // System contact picker for "Add people" — not a bespoke design (V3/V4 open question).
    val pickContact = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        ContactsRepository.clearCachedLookups(context)
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val number = resolvePhoneNumber(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addParticipant(number)
        stubToast = context.getString(R.string.thread_person_added, number)
    }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.stagePickedImage(uri)
    }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        viewModel.onCameraCaptured(success)
    }

    BackHandler(enabled = menuOpen) {
        menuOpen = false
    }
    BackHandler(enabled = !menuOpen && attachOpen) {
        attachOpen = false
    }
    BackHandler(enabled = !menuOpen && state.selectionActive) {
        viewModel.exitSelection()
    }

    val notificationsStub = stringResource(R.string.thread_stub_notifications)
    val searchStub = stringResource(R.string.thread_stub_search)
    val videoStub = stringResource(R.string.thread_stub_video)

    ThreadScreen(
        state = state,
        menuOpen = menuOpen,
        onMenuOpenChange = { menuOpen = it },
        stubToast = stubToast,
        onDismissStubToast = { stubToast = null },
        onBack = {
            when {
                menuOpen -> menuOpen = false
                attachOpen -> attachOpen = false
                state.selectionActive -> viewModel.exitSelection()
                else -> onBack()
            }
        },
        onOpenBuilder = {
            menuOpen = false
            val value = state.senderMatchValue
            if (value.isNotBlank()) onOpenBuilderForSender(value)
        },
        onVideo = { stubToast = videoStub },
        onDetails = viewModel::showDetails,
        onDismissDetails = viewModel::dismissDetails,
        onAddPeople = {
            menuOpen = false
            pickContact.launch(
                Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ),
            )
        },
        onNotifications = { stubToast = notificationsStub },
        onSearchInConversation = { stubToast = searchStub },
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::send,
        onStartSelection = viewModel::startSelection,
        onToggleSelection = viewModel::toggleSelection,
        onExitSelection = viewModel::exitSelection,
        onRequestDelete = viewModel::requestDeleteSelected,
        onConfirmDelete = viewModel::confirmDeleteSelected,
        onDismissDeleteConfirm = viewModel::dismissDeleteConfirm,
        onRequestDeleteConversation = viewModel::requestDeleteConversation,
        onConfirmDeleteConversation = {
            // Version 2: close the thread on confirm. Undo toast lives on inbox.
            viewModel.confirmDeleteConversation()
            onBack()
        },
        onDismissDeleteConversation = viewModel::dismissDeleteConversationConfirm,
        onUndoDelete = viewModel::undoPendingDelete,
        onDismissUndoToast = viewModel::dismissUndoToast,
        onOpenPhoto = onOpenPhoto,
        onDownloadMms = viewModel::downloadMms,
        attachOpen = attachOpen,
        onAttachOpenChange = { attachOpen = it },
        onPickGallery = {
            attachOpen = false
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onPickCamera = {
            attachOpen = false
            val uri = viewModel.prepareCameraCapture()
            if (uri != null) takePicture.launch(uri)
        },
        onClearStaged = viewModel::clearStagedImage,
        onLoadOlder = viewModel::loadOlderMessages,
        modifier = modifier,
    )
}

@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onOpenBuilder: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    stubToast: String? = null,
    onDismissStubToast: () -> Unit = {},
    onVideo: () -> Unit = {},
    onDetails: () -> Unit = {},
    onDismissDetails: () -> Unit = {},
    onAddPeople: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onSearchInConversation: () -> Unit = {},
    onStartSelection: (messageId: MessageRef) -> Unit = {},
    onToggleSelection: (messageId: MessageRef) -> Unit = {},
    onExitSelection: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDeleteConfirm: () -> Unit = {},
    onRequestDeleteConversation: () -> Unit = {},
    onConfirmDeleteConversation: () -> Unit = {},
    onDismissDeleteConversation: () -> Unit = {},
    onUndoDelete: () -> Unit = {},
    onDismissUndoToast: () -> Unit = {},
    onOpenPhoto: (mmsId: Long, seq: Int) -> Unit = { _, _ -> },
    onDownloadMms: (mmsId: Long) -> Unit = {},
    attachOpen: Boolean = false,
    onAttachOpenChange: (Boolean) -> Unit = {},
    onPickGallery: () -> Unit = {},
    onPickCamera: () -> Unit = {},
    onClearStaged: () -> Unit = {},
    onLoadOlder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val listScope = rememberCoroutineScope()
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val stickToBottomState = remember { mutableStateOf(true) }
    val showNewTextsState = remember { mutableStateOf(false) }
    var stickToBottom by stickToBottomState
    var showNewTexts by showNewTextsState
    var previousLastKey by remember { mutableStateOf<String?>(null) }
    var previousCount by remember { mutableIntStateOf(0) }
    val lastKey = state.messages.lastOrNull()?.ref?.toLazyKey()
    val userScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val atBottom = listState.isAtBottom()
                    stickToBottomState.value = atBottom
                    if (atBottom) showNewTextsState.value = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isAtBottom() }.collect { atBottom ->
            if (atBottom) {
                stickToBottom = true
                showNewTexts = false
            }
        }
    }

    // Auto-scroll only on first load, the user's own send, or when already
    // at the bottom. Incoming while reading keeps position and shows the
    // "New texts" chip. Load-older prepends (#262) keep the same last-message
    // key — do not yank, and do not delete that listener.
    LaunchedEffect(state.messages.size, lastKey) {
        val last = state.messages.lastOrNull()
        if (last == null) {
            previousCount = 0
            previousLastKey = null
            showNewTexts = false
            return@LaunchedEffect
        }
        val lastChanged = last.ref.toLazyKey() != previousLastKey
        val countGrew = state.messages.size > previousCount
        val isInitial = previousLastKey == null
        val ownSend = lastChanged && countGrew && last.isOutgoing
        val incomingWhileReading = lastChanged && countGrew && !last.isOutgoing
        previousCount = state.messages.size
        previousLastKey = last.ref.toLazyKey()
        if (!lastChanged && !isInitial) {
            return@LaunchedEffect
        }
        if (isInitial || stickToBottom || ownSend) {
            listState.scrollToItem(state.messages.lastIndex)
            stickToBottom = true
            showNewTexts = false
        } else if (incomingWhileReading) {
            showNewTexts = true
        }
    }
    LaunchedEffect(listState, state.hasOlderMessages, state.loading) {
        if (!state.hasOlderMessages || state.loading) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex <= THREAD_LOAD_OLDER_INDEX }
            .distinctUntilChanged()
            .collect { nearTop ->
                if (nearTop) onLoadOlder()
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Cap draft by *laid-out* thread height (IME-aware via parent constraints),
        // not a fixed maxLines — Version 4 fitDraft / draftCap.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val minDraft = PinotMinTouchTarget
            val reserve = ComposerDraftReserve
            val maxDraftHeight = with(density) {
                val capPx = (constraints.maxHeight - headerHeightPx - reserve.roundToPx())
                    .coerceAtLeast(minDraft.roundToPx())
                capPx.toDp()
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { headerHeightPx = it.height },
                ) {
                    if (state.selectionActive) {
                        ThreadSelectionBar(
                            selectedCount = state.selectedCount,
                            showDelete = state.roleHeld && state.selectedCount > 0,
                            onClose = onExitSelection,
                            onDelete = onRequestDelete,
                        )
                    } else {
                        // Phone · video · overflow only for personal (1:1 known-contact) threads.
                        val personal = !state.isGroup &&
                            state.isKnownContact &&
                            state.address.isNotBlank()
                        ThreadTopBar(
                            title = state.title,
                            subtitle = state.subtitle,
                            showCallActions = personal,
                            menuOpen = menuOpen,
                            showDeleteConversation = state.roleHeld,
                            onBack = onBack,
                            onCall = {
                                dialAddress(context, state.address)
                            },
                            onVideo = onVideo,
                            onMenuOpenChange = onMenuOpenChange,
                            onDetails = onDetails,
                            onAddPeople = onAddPeople,
                            onNotifications = onNotifications,
                            onSearchInConversation = onSearchInConversation,
                            onFilterSender = onOpenBuilder,
                            onDeleteConversation = {
                                onMenuOpenChange(false)
                                onRequestDeleteConversation()
                            },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(userScrollConnection),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("thread_message_list"),
                        contentPadding = PaddingValues(spacing.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                    itemsIndexed(
                        items = state.messages,
                        key = { _, bubble -> bubble.ref.toLazyKey() },
                    ) { index, bubble ->
                        val prev = state.messages.getOrNull(index - 1)
                        // Group only: label on first bubble of a run; never on outgoing.
                        val showSenderLabel = state.isGroup &&
                            !bubble.isOutgoing &&
                            !bubble.senderLabel.isNullOrBlank() &&
                            (index == 0 || prev?.senderAddress != bubble.senderAddress)
                        MessageBubble(
                            bubble = bubble,
                            showSenderLabel = showSenderLabel,
                            selectionActive = state.selectionActive,
                            selected = bubble.ref in state.selectedMessageIds,
                            onClick = {
                                if (state.selectionActive) {
                                    onToggleSelection(bubble.ref)
                                }
                            },
                            onLongPress = {
                                if (state.roleHeld) onStartSelection(bubble.ref)
                            },
                            onOpenPhoto = { tile ->
                                if (state.selectionActive) {
                                    onToggleSelection(bubble.ref)
                                } else {
                                    onOpenPhoto(tile.messageId, tile.seq)
                                }
                            },
                            onDownloadMms = { tile ->
                                if (state.selectionActive) {
                                    onToggleSelection(bubble.ref)
                                } else {
                                    onDownloadMms(tile.messageId)
                                }
                            },
                        )
                    }
                    }
                    if (showNewTexts && !state.selectionActive) {
                        NewTextsChip(
                            onClick = {
                                listScope.launch {
                                    if (state.messages.isEmpty()) return@launch
                                    listState.scrollToItem(state.messages.lastIndex)
                                    stickToBottom = true
                                    showNewTexts = false
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = spacing.sm),
                        )
                    }
                }

                if (!state.selectionActive) {
                    ThreadComposer(
                        draft = state.draft,
                        enabled = state.roleHeld && !state.sending,
                        disabledReason = if (!state.roleHeld) {
                            ThreadViewModel.DISABLED_REASON
                        } else {
                            null
                        },
                        sendError = state.sendError,
                        tooLarge = state.tooLarge,
                        sending = state.sending && state.stagedImageUri != null,
                        stagedUri = state.stagedImageUri,
                        maxDraftHeight = maxDraftHeight,
                        onDraftChange = onDraftChange,
                        onSend = onSend,
                        onAttach = { onAttachOpenChange(true) },
                        onClearStaged = onClearStaged,
                    )
                }
            }
        }

        // Message-batch undo only — whole-conversation undo is hosted on the inbox.
        if (state.undoToastCount > 0) {
            PinotToast(
                message = pluralStringResource(
                    R.plurals.batch_deleted_messages,
                    state.undoToastCount,
                    state.undoToastCount,
                ),
                durationMs = 5_000L,
                actionLabel = stringResource(R.string.batch_undo),
                onAction = onUndoDelete,
                onDismiss = onDismissUndoToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
            )
        } else if (stubToast != null) {
            PinotToast(
                message = stubToast,
                onDismiss = onDismissStubToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
            )
        }

        if (attachOpen) {
            AttachSheet(
                onDismiss = { onAttachOpenChange(false) },
                onGallery = onPickGallery,
                onCamera = onPickCamera,
            )
        }
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDeleteConfirm,
            containerColor = colors.surface,
            titleContentColor = colors.text,
            textContentColor = colors.dim,
            title = {
                Text(
                    text = pluralStringResource(
                        R.plurals.batch_delete_messages_title,
                        state.selectedCount,
                        state.selectedCount,
                    ),
                    style = PinotTypography.titleMedium,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.batch_delete_messages_body),
                    style = PinotTypography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(
                        text = stringResource(R.string.batch_delete_confirm),
                        style = PinotTypography.labelLarge,
                        color = colors.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteConfirm) {
                    Text(
                        text = stringResource(R.string.batch_delete_cancel),
                        style = PinotTypography.labelLarge,
                        color = colors.dim,
                    )
                }
            },
        )
    }

    if (state.showDeleteConversationConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDeleteConversation,
            containerColor = colors.surface,
            titleContentColor = colors.text,
            textContentColor = colors.dim,
            title = {
                Text(
                    text = pluralStringResource(
                        R.plurals.batch_delete_conversations_title,
                        1,
                        1,
                    ),
                    style = PinotTypography.titleMedium,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.batch_delete_conversations_body),
                    style = PinotTypography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDeleteConversation) {
                    Text(
                        text = stringResource(R.string.batch_delete_confirm),
                        style = PinotTypography.labelLarge,
                        color = colors.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteConversation) {
                    Text(
                        text = stringResource(R.string.batch_delete_cancel),
                        style = PinotTypography.labelLarge,
                        color = colors.dim,
                    )
                }
            },
        )
    }

    if (state.showDetails) {
        ThreadDetailsDialog(
            participants = state.participantLabels.ifEmpty {
                listOfNotNull(
                    state.title.takeIf { it.isNotBlank() && it != "Conversation" },
                )
            },
            onDismiss = onDismissDetails,
        )
    }
}

@Composable
private fun ThreadDetailsDialog(
    participants: List<String>,
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
                text = stringResource(R.string.thread_menu_details),
                style = PinotTypography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (participants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.thread_details_empty),
                        style = PinotTypography.bodyMedium,
                        color = colors.dim,
                    )
                } else {
                    participants.forEach { name ->
                        Text(
                            text = name,
                            style = PinotTypography.bodyMedium,
                            color = colors.text,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.thread_details_close),
                    style = PinotTypography.labelLarge,
                    color = colors.accent,
                )
            }
        },
    )
}

private fun dialAddress(context: android.content.Context, address: String) {
    if (address.isBlank()) return
    val tel = address.filter { it.isDigit() || it == '+' }
    if (tel.isBlank()) return
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:$tel")
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ThreadSelectionBar(
    selectedCount: Int,
    showDelete: Boolean,
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
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
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
            } else {
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
private fun ThreadTopBar(
    title: String,
    subtitle: String,
    showCallActions: Boolean,
    menuOpen: Boolean,
    showDeleteConversation: Boolean,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onVideo: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onAddPeople: () -> Unit,
    onNotifications: () -> Unit,
    onSearchInConversation: () -> Unit,
    onFilterSender: () -> Unit,
    onDeleteConversation: () -> Unit,
) {
    val colors = LocalPinotColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PinotIconButton(
                    onClick = onBack,
                    contentDescription = stringResource(R.string.thread_back),
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.Back),
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = title,
                        style = PinotTypography.titleMedium.copy(fontSize = 15.sp),
                        color = colors.text,
                        maxLines = 1,
                    )
                    Text(
                        text = subtitle,
                        style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                        color = colors.dimmer,
                        maxLines = 1,
                    )
                }
                // Version 3 order: phone · video · overflow — personal only.
                if (showCallActions) {
                    PinotIconButton(
                        onClick = onCall,
                        contentDescription = stringResource(R.string.thread_call),
                    ) {
                        Icon(
                            painter = painterResource(PinotIcons.Phone),
                            contentDescription = null,
                            tint = colors.text,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    PinotIconButton(
                        onClick = onVideo,
                        contentDescription = stringResource(R.string.thread_video),
                    ) {
                        Icon(
                            painter = painterResource(PinotIcons.Video),
                            contentDescription = null,
                            tint = colors.text,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                PinotIconButton(
                    onClick = { onMenuOpenChange(!menuOpen) },
                    contentDescription = stringResource(R.string.thread_menu),
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.More),
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            if (menuOpen) {
                ThreadOverflowMenu(
                    showDeleteConversation = showDeleteConversation,
                    onDismiss = { onMenuOpenChange(false) },
                    onDetails = {
                        onMenuOpenChange(false)
                        onDetails()
                    },
                    onAddPeople = {
                        onMenuOpenChange(false)
                        onAddPeople()
                    },
                    onNotifications = {
                        onMenuOpenChange(false)
                        onNotifications()
                    },
                    onSearchInConversation = {
                        onMenuOpenChange(false)
                        onSearchInConversation()
                    },
                    onFilterSender = {
                        onMenuOpenChange(false)
                        onFilterSender()
                    },
                    onDeleteConversation = onDeleteConversation,
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

/**
 * Version 3 overflow: min 168dp, surface, radius-md, shadow-md.
 * Six plain-text rows ≥48dp; delete last and in accent. Corner card — do not
 * fillMaxWidth on the column (that made a full-screen band in #48).
 */
@Composable
private fun ThreadOverflowMenu(
    showDeleteConversation: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onAddPeople: () -> Unit,
    onNotifications: () -> Unit,
    onSearchInConversation: () -> Unit,
    onFilterSender: () -> Unit,
    onDeleteConversation: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(shapes.md)
    // Prototype: top 52px, right 10px — popup aligned top-end with offset.
    val menuOffset = with(density) {
        IntOffset(x = (-10).dp.roundToPx(), y = 52.dp.roundToPx())
    }
    Popup(
        alignment = Alignment.TopEnd,
        offset = menuOffset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // min-width 168dp, wrap to content — do NOT fillMaxWidth (that made a full-screen band).
        Column(
            modifier = Modifier
                .widthIn(min = 168.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.shadowSmBorder, shape)
                .padding(vertical = 6.dp),
        ) {
            ThreadMenuRow(
                label = stringResource(R.string.thread_menu_details),
                color = colors.text,
                onClick = onDetails,
            )
            ThreadMenuRow(
                label = stringResource(R.string.thread_menu_add_people),
                color = colors.text,
                onClick = onAddPeople,
            )
            ThreadMenuRow(
                label = stringResource(R.string.thread_menu_notifications),
                color = colors.text,
                onClick = onNotifications,
            )
            ThreadMenuRow(
                label = stringResource(R.string.thread_menu_search_in_conversation),
                color = colors.text,
                onClick = onSearchInConversation,
            )
            ThreadMenuRow(
                label = stringResource(R.string.thread_menu_filter_sender),
                color = colors.text,
                onClick = onFilterSender,
            )
            if (showDeleteConversation) {
                ThreadMenuRow(
                    label = stringResource(R.string.thread_menu_delete_conversation),
                    color = colors.accent300,
                    onClick = onDeleteConversation,
                )
            }
        }
    }
}

@Composable
private fun ThreadMenuRow(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    val spacing = LocalPinotSpacing.current
    Box(
        modifier = Modifier
            .heightIn(min = PinotMinTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = spacing.screenPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = PinotTypography.bodyMedium.copy(fontSize = 14.sp),
            color = color,
        )
    }
}

@Composable
private fun NewTextsChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val spacing = LocalPinotSpacing.current
    val label = stringResource(R.string.thread_new_texts)
    val shape = RoundedCornerShape(shapes.pill)
    Box(
        modifier = modifier
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .shadow(elevation = 4.dp, shape = shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.divider, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .testTag("thread_new_texts"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PinotTypography.labelMedium,
            color = colors.accent,
        )
    }
}

private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    return lastVisible.index >= info.totalItemsCount - 1
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    bubble: ThreadBubble,
    showSenderLabel: Boolean = false,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onOpenPhoto: (MmsTile) -> Unit = {},
    onDownloadMms: (MmsTile) -> Unit = {},
) {
    val colors = LocalPinotColors.current
    val isOut = bubble.isOutgoing
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val contentWidth = maxWidth
        val maxW = contentWidth * 0.82f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
        ) {
            if (showSenderLabel) {
                Text(
                    text = bubble.senderLabel.orEmpty(),
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.accent300,
                    modifier = Modifier.padding(start = 5.dp, bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("thread-bubble-${bubble.ref.toLazyKey()}")
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongPress,
                    ),
                horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionActive && !isOut) {
                    PinotCheckbox(
                        checked = selected,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Column(
                    modifier = Modifier.widthIn(max = maxW),
                    horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
                ) {
                    val photoTiles = bubble.tiles.filter { it.kind == MmsTileKind.Photo }
                    val otherTiles = bubble.tiles.filter { it.kind != MmsTileKind.Photo }
                    val gridW = contentWidth * MmsPictureGridMetrics.WIDTH_FRACTION
                    if (photoTiles.isNotEmpty()) {
                        MmsPictureGrid(
                            tiles = photoTiles,
                            isOutgoing = isOut,
                            senderLabel = bubble.senderLabel,
                            senderAddress = bubble.senderAddress,
                            selected = selected,
                            onClickTile = { tile ->
                                when {
                                    tile.opensViewer -> onOpenPhoto(tile)
                                    tile.runsDownload -> onDownloadMms(tile)
                                    else -> onClick()
                                }
                            },
                            onLongPress = onLongPress,
                            modifier = Modifier.width(gridW),
                        )
                    }
                    if (otherTiles.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
                        ) {
                            for (tile in otherTiles) {
                                MmsPictureTile(
                                    tile = tile,
                                    contentDescription = mmsTileContentDescription(
                                        tile = tile,
                                        isOutgoing = isOut,
                                        senderLabel = bubble.senderLabel,
                                        senderAddress = bubble.senderAddress,
                                    ),
                                    selected = selected,
                                    onClick = {
                                        when {
                                            tile.opensViewer -> onOpenPhoto(tile)
                                            tile.runsDownload -> onDownloadMms(tile)
                                            else -> onClick()
                                        }
                                    },
                                    onLongPress = onLongPress,
                                )
                            }
                        }
                    } else if (photoTiles.isEmpty() && bubble.showPhotoPlaceholder) {
                        Box(
                            modifier = Modifier
                                .width(gridW)
                                .height(gridW * MmsPictureGridMetrics.heightFraction(1))
                                .clip(RoundedCornerShape(MmsPictureGridMetrics.Radius))
                                .background(colors.neutral800)
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) colors.accent else colors.divider,
                                    shape = RoundedCornerShape(MmsPictureGridMetrics.Radius),
                                )
                                .testTag("mms-placeholder-${bubble.ref.toLazyKey()}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_image),
                                contentDescription = null,
                                tint = colors.dim,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    val subject = bubble.subject?.takeIf { it.isNotBlank() }
                    val caption = bubble.body.takeIf { it.isNotEmpty() && it != subject }
                    if (subject != null || caption != null) {
                        if (bubble.tiles.isNotEmpty() || bubble.showPhotoPlaceholder) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    if (subject != null) {
                        TextBubble(
                            text = subject,
                            isOutgoing = isOut,
                            selected = selected,
                        )
                    }
                    if (caption != null) {
                        if (subject != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        TextBubble(
                            text = caption,
                            isOutgoing = isOut,
                            selected = selected,
                        )
                    }
                }
                if (selectionActive && isOut) {
                    PinotCheckbox(
                        checked = selected,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextBubble(
    text: String,
    isOutgoing: Boolean,
    selected: Boolean,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(shapes.lg))
            .background(if (isOutgoing) colors.outgoingBubble else colors.incomingBubble)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) colors.accent else colors.bg,
                shape = RoundedCornerShape(shapes.lg),
            )
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = PinotTypography.bodyLarge.copy(
                fontSize = 14.sp,
                lineHeight = 14.sp * 1.45f,
            ),
            color = if (isOutgoing) colors.outgoingBubbleText else colors.text,
        )
    }
}

/** Prototype draftCap reserve: thread − header − 36dp. */
private val ComposerDraftReserve = 36.dp
private const val THREAD_LOAD_OLDER_INDEX = 2

@Composable
private fun ThreadComposer(
    draft: String,
    enabled: Boolean,
    disabledReason: String?,
    sendError: String?,
    tooLarge: Boolean,
    sending: Boolean,
    stagedUri: Uri?,
    maxDraftHeight: Dp,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onClearStaged: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val density = LocalDensity.current
    val draftScroll = rememberScrollState()
    val fieldMin = PinotMinTouchTarget
    val fieldMax = maxDraftHeight.coerceAtLeast(fieldMin)

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
        ) {
            if (disabledReason != null) {
                Text(
                    text = disabledReason,
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.dimmer,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (sendError != null) {
                Text(
                    text = sendError,
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.accent,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .then(
                            if (tooLarge) Modifier.testTag("mms-too-large") else Modifier,
                        )
                        .then(
                            if (!tooLarge && !sending) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClick = onSend,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
            }
            if (sending) {
                Text(
                    text = stringResource(R.string.mms_sending),
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.dimmer,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .testTag("mms-sending"),
                )
            }
            if (stagedUri != null) {
                val targetW = with(density) { MmsPictureTileWidth.roundToPx() }
                val targetH = with(density) { MmsPictureTileHeight.roundToPx() }
                val bitmap = rememberMmsPartImage(
                    uri = stagedUri,
                    targetWidthPx = targetW,
                    targetHeightPx = targetH,
                    scale = DecodeScale.Cover,
                )
                Box(
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .testTag("staged-preview"),
                ) {
                    Box(
                        modifier = Modifier
                            .size(MmsPictureTileWidth, MmsPictureTileHeight)
                            .alpha(if (sending) colors.disabledOpacity else 1f)
                            .clip(RoundedCornerShape(shapes.lg))
                            .background(colors.neutral800)
                            .border(1.dp, colors.divider, RoundedCornerShape(shapes.lg)),
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp)
                            .size(PinotMinTouchTarget)
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onClearStaged,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(PinotIcons.Close),
                            contentDescription = stringResource(R.string.mms_staged_remove_cd),
                            tint = colors.dim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(PinotMinTouchTarget)
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onAttach,
                        )
                        .testTag("thread-attach"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus_circle),
                        contentDescription = stringResource(R.string.mms_attach_cd),
                        tint = colors.dim,
                        modifier = Modifier.size(23.dp),
                    )
                }
                val fieldShape = RoundedCornerShape(999.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = fieldMin, max = fieldMax)
                        .clip(fieldShape)
                        .background(
                            if (enabled) {
                                colors.surface
                            } else {
                                colors.surface.copy(alpha = colors.disabledOpacity)
                            },
                        )
                        .border(1.dp, colors.divider, fieldShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = ThreadViewModel.COMPOSER_PLACEHOLDER,
                            style = PinotTypography.bodyMedium.copy(fontSize = 13.sp),
                            color = colors.dimmer,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        enabled = enabled,
                        textStyle = TextStyle(
                            color = colors.text,
                            fontSize = 13.sp,
                            fontFamily = PinotTypography.bodyMedium.fontFamily,
                            lineHeight = 18.sp,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = fieldMin - 20.dp, max = fieldMax - 20.dp)
                            .verticalScroll(draftScroll),
                    )
                }
                val canSend = enabled && (draft.isNotBlank() || stagedUri != null)
                Box(
                    modifier = Modifier
                        .size(PinotMinTouchTarget)
                        .clickable(
                            enabled = canSend,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onSend,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(
                                1.dp,
                                if (canSend) {
                                    colors.accent
                                } else {
                                    colors.accent.copy(alpha = colors.disabledOpacity)
                                },
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(PinotIcons.Send),
                            contentDescription = "Send",
                            tint = if (canSend) {
                                colors.accent
                            } else {
                                colors.accent.copy(alpha = colors.disabledOpacity)
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .testTag("attach-sheet"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = shapes.lg, topEnd = shapes.lg))
                .background(colors.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 22.dp)
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.neutral700),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.mms_attach_sheet_title),
                style = PinotTypography.titleSmall.copy(fontSize = 16.sp),
                color = colors.text,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachCell(
                    icon = R.drawable.ic_images,
                    label = stringResource(R.string.mms_attach_gallery),
                    onClick = onGallery,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("attach-gallery"),
                )
                AttachCell(
                    icon = R.drawable.ic_camera,
                    label = stringResource(R.string.mms_attach_camera),
                    onClick = onCamera,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("attach-camera"),
                )
                // V5 is a 3-column grid. File/Location/Contact/Voice are not
                // shipped — empty third cell, not a two-column reflow.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp)
                        .testTag("attach-empty"),
                )
            }
        }
    }
}

@Composable
private fun AttachCell(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(shapes.sm))
            .background(colors.accent900)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 16.dp)
            .heightIn(min = 84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(25.dp),
        )
        Text(
            text = label,
            style = PinotTypography.bodySmall.copy(fontSize = 11.5.sp),
            color = colors.accent200,
        )
    }
}

// —— Previews ——

private val previewMessages = listOf(
    ThreadBubble(MessageRef.sms(1), "Hey — are we still on for Thursday?", isOutgoing = false, date = 1),
    ThreadBubble(MessageRef.sms(2), "Yes! 7 works. I'll bring the pinot.", isOutgoing = true, date = 2),
    ThreadBubble(MessageRef.sms(3), "Perfect. See you then.", isOutgoing = false, date = 3),
    ThreadBubble(MessageRef.sms(4), "PRE-APPROVED for 5000 dollars — no credit check", isOutgoing = false, date = 4),
)

@Preview(name = "Thread · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ThreadPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        ThreadScreen(
            state = ThreadUiState(
                threadId = 1,
                title = "Maya Chen",
                address = "+15551234567",
                senderMatchValue = "15555550100",
                messages = previewMessages,
                draft = "",
                roleHeld = true,
                loading = false,
            ),
            onBack = {},
            onOpenBuilder = {},
            onDraftChange = {},
            onSend = {},
        )
    }
}

@Preview(name = "Thread · Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ThreadPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        ThreadScreen(
            state = ThreadUiState(
                threadId = 1,
                title = "Maya Chen",
                address = "+15551234567",
                senderMatchValue = "15555550100",
                messages = previewMessages,
                draft = "On my way",
                roleHeld = true,
                loading = false,
            ),
            onBack = {},
            onOpenBuilder = {},
            onDraftChange = {},
            onSend = {},
        )
    }
}

@Preview(name = "Thread · Dark · role not held", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ThreadPreviewDarkNoRole() {
    PinotRougeTheme(darkTheme = true) {
        ThreadScreen(
            state = ThreadUiState(
                threadId = 1,
                title = "18445550192",
                address = "18445550192",
                senderMatchValue = "18445550192",
                messages = previewMessages.take(2),
                draft = "Cannot send this",
                roleHeld = false,
                loading = false,
            ),
            onBack = {},
            onOpenBuilder = {},
            onDraftChange = {},
            onSend = {},
        )
    }
}
