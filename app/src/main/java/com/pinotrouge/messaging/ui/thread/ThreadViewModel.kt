package com.pinotrouge.messaging.ui.thread

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.data.repo.MessageRepository
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.MmsPartRow
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.MmsSendComposer
import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.SmsMessage
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.di.ApplicationScope
import com.pinotrouge.messaging.notify.NotificationHelper
import com.pinotrouge.messaging.sms.IncomingMessagePipeline
import com.pinotrouge.messaging.sms.MmsDownloadClaims
import com.pinotrouge.messaging.sms.MmsSendEvent
import com.pinotrouge.messaging.sms.MmsSendVerdict
import com.pinotrouge.messaging.sms.MmsSubmitResult
import com.pinotrouge.messaging.sms.SmsRoleManager
import com.pinotrouge.messaging.sms.SmsSender
import com.pinotrouge.messaging.sms.SmsSubmitResult
import com.pinotrouge.messaging.ui.components.BATCH_DELETE_UNDO_MS
import com.pinotrouge.messaging.ui.components.BatchSelection
import com.pinotrouge.messaging.ui.components.ConversationDeleteSession
import com.pinotrouge.messaging.ui.media.MmsDownloadUi
import com.pinotrouge.messaging.ui.media.MmsEncodeResult
import com.pinotrouge.messaging.ui.media.MmsImageEncoder
import com.pinotrouge.messaging.ui.media.MmsImageLadder
import com.pinotrouge.messaging.ui.media.MmsStaging
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.media.classifyMmsPart
import com.pinotrouge.messaging.ui.media.stubTileKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class ThreadBubble(
    val ref: MessageRef,
    val body: String,
    val isOutgoing: Boolean,
    val date: Long,
    /** Telephony address of the originator (incoming only; null for outgoing). */
    val senderAddress: String? = null,
    /** Contact name or number for group sender labels. */
    val senderLabel: String? = null,
    /** MMS `SUBJECT` column; null for SMS. */
    val subject: String? = null,
    /**
     * Picture-message placeholder tile (no bytes this branch). 17.3 replaces it
     * when [tiles] is non-empty.
     */
    val showPhotoPlaceholder: Boolean = false,
    /** One tile per image (or one state tile for an undownloaded PDU). */
    val tiles: List<MmsTile> = emptyList(),
) {
    val kind: MessageRef.Kind get() = ref.kind
}

data class ThreadUiState(
    val threadId: Long = 0L,
    val title: String = "",
    /**
     * Personal: "Texts · this phone only". Group: "{n} people · {names}".
     */
    val subtitle: String = "Texts · this phone only",
    /** Telephony address used for [SmsSender.send]. */
    val address: String = "",
    /**
     * Value for builder `Sender IS`. Always the telephony **address**, never a
     * contact display name — incoming SMS carries a number, so "Mom" never matches.
     */
    val senderMatchValue: String = "",
    /**
     * Known contact — gates the Version 2 audio-call button
     * (prototype `threadCallable` / personal category). For groups: any participant.
     */
    val isKnownContact: Boolean = false,
    /** Multi-recipient thread from RECIPIENT_IDS (or after Add people). */
    val isGroup: Boolean = false,
    /** Telephony addresses we send to (grows with Add people). */
    val sendAddresses: List<String> = emptyList(),
    /** Display labels for participants (group + Details). */
    val participantLabels: List<String> = emptyList(),
    val messages: List<ThreadBubble> = emptyList(),
    val draft: String = "",
    val roleHeld: Boolean = false,
    val sending: Boolean = false,
    val sendError: String? = null,
    /** Set when send produced a different Telephony thread; NavHost pops the old one. */
    val navigateToThreadId: Long? = null,
    /** Owned cache path of the one staged outbound image. Survives rotation. */
    val stagedImagePath: String? = null,
    val tooLarge: Boolean = false,
    val loading: Boolean = true,
    val selectionActive: Boolean = false,
    val selectedMessageIds: Set<MessageRef> = emptySet(),
    val selectedCount: Int = 0,
    val showDeleteConfirm: Boolean = false,
    /** Whole-conversation delete confirm from the ⋮ menu. */
    val showDeleteConversationConfirm: Boolean = false,
    val showDetails: Boolean = false,
    val undoToastCount: Int = 0,
    /**
     * Provider has older messages than the loaded window. Scroll-to-top
     * loads the next page; no extra chrome.
     */
    val hasOlderMessages: Boolean = false,
) {
    val stagedImageUri: Uri?
        get() = stagedImagePath?.let { path ->
            val file = File(path)
            if (file.isFile) Uri.fromFile(file) else null
        }
}

@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val contactsRepository: ContactsRepository,
    private val smsRepository: SmsRepository,
    private val mmsRepository: MmsRepository,
    private val mmsTransport: MmsTransport,
    private val incomingMessagePipeline: IncomingMessagePipeline,
    private val smsSender: SmsSender,
    private val smsRoleManager: SmsRoleManager,
    private val notificationHelper: NotificationHelper,
    private val conversationDeleteSession: ConversationDeleteSession,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private var threadId: Long =
        savedStateHandle.get<String>(ARG_THREAD_ID)?.toLongOrNull()
            ?: savedStateHandle.get<Long>(ARG_THREAD_ID)
            ?: 0L

    private val _state = MutableStateFlow(
        ThreadUiState(
            threadId = threadId,
            roleHeld = smsRoleManager.isRoleHeld(),
        ),
    )
    private val selection = MutableStateFlow(BatchSelection<MessageRef>())
    private val pendingDeleteIds = MutableStateFlow<Set<MessageRef>>(emptySet())
    private val showDeleteConfirm = MutableStateFlow(false)
    private val showDeleteConversationConfirm = MutableStateFlow(false)
    private val undoToastCount = MutableStateFlow(0)

    @Volatile
    private var pendingDeleteJob: Job? = null

    private val downloadUi = mutableMapOf<Long, MmsDownloadUi>()
    private val visualsCache = ConcurrentHashMap<Long, Pair<String, List<MmsTile>>>()
    private val visualsInFlight =
        ConcurrentHashMap<Long, Deferred<Pair<String, List<MmsTile>>>>()
    private val mmsTextOnly = ConcurrentHashMap<Long, Boolean>()
    private val refreshSeq = AtomicInteger(0)
    private val loadOlderInFlight = AtomicBoolean(false)
    @Volatile
    private var reachedOldest = false

    val uiState: StateFlow<ThreadUiState> = combine(
        _state,
        smsRoleManager.roleHeld,
        selection,
        pendingDeleteIds,
        combine(showDeleteConfirm, showDeleteConversationConfirm, undoToastCount) { msgConfirm, convConfirm, toastCount ->
            Triple(msgConfirm, convConfirm, toastCount)
        },
    ) { state, held, sel, pending, confirms ->
        val (msgConfirm, convConfirm, toastCount) = confirms
        val visible = state.messages.filter { it.ref !in pending }
        state.copy(
            roleHeld = held,
            messages = visible,
            selectionActive = sel.active,
            selectedMessageIds = sel.selected,
            selectedCount = sel.count,
            showDeleteConfirm = msgConfirm,
            showDeleteConversationConfirm = convConfirm,
            undoToastCount = toastCount,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        _state.value,
    )

    init {
        smsRoleManager.refresh()
        restoreStagedFromHandle()
        restorePendingMms()
        dispatchMarkThreadReadOnOpen(threadId) { id ->
            viewModelScope.launch {
                logWriteResult("markThreadRead", smsRepository.markThreadRead(id))
            }
        }
        if (threadId != 0L) {
            notificationHelper.clearThread(threadId)
            refresh()
            viewModelScope.launch {
                observeProviderChanges()
            }
            viewModelScope.launch {
                incomingMessagePipeline.downloadFailures.collect { id ->
                    applyDownloadUi(id, MmsDownloadUi.Failed)
                }
            }
            viewModelScope.launch {
                smsSender.sendCompletions.collect { event ->
                    onMmsSendCompleted(event)
                }
            }
        }
    }

    fun refreshRole() {
        smsRoleManager.refresh()
    }

    @OptIn(FlowPreview::class)
    private suspend fun observeProviderChanges() {
        smsRepository.observeSmsChanges()
            .drop(1)
            .debounce(MessageRepository.SMS_CHANGE_DEBOUNCE_MS)
            .collect { refresh(announceLoading = false) }
    }

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value, sendError = null, tooLarge = false) }
    }

    fun stagePickedImage(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                MmsStaging.copyToOwnedCache(context, uri)
            } ?: return@launch
            replaceStaged(file)
        }
    }

    /**
     * FileProvider URI for [android.provider.MediaStore.ACTION_IMAGE_CAPTURE].
     * The path is remembered so a rotation during capture still lands.
     */
    fun prepareCameraCapture(): Uri? {
        val (file, uri) = MmsStaging.createCameraTarget(context)
        savedStateHandle[KEY_CAMERA_PATH] = file.absolutePath
        return uri
    }

    fun onCameraCaptured(success: Boolean) {
        val path = savedStateHandle.get<String>(KEY_CAMERA_PATH)
        savedStateHandle.remove<String>(KEY_CAMERA_PATH)
        if (!success) {
            path?.let { File(it).delete() }
            return
        }
        val file = MmsStaging.fileIfExists(path) ?: return
        replaceStaged(file)
    }

    fun clearStagedImage() {
        _state.value.stagedImagePath?.let { File(it).delete() }
        savedStateHandle.remove<String>(KEY_STAGED_PATH)
        _state.update { it.copy(stagedImagePath = null, tooLarge = false, sendError = null) }
    }

    private fun restoreStagedFromHandle() {
        val path = savedStateHandle.get<String>(KEY_STAGED_PATH)
        val file = MmsStaging.fileIfExists(path) ?: return
        _state.update { it.copy(stagedImagePath = file.absolutePath) }
    }

    private fun replaceStaged(file: File) {
        val previous = _state.value.stagedImagePath
        if (previous != null && previous != file.absolutePath) {
            File(previous).delete()
        }
        savedStateHandle[KEY_STAGED_PATH] = file.absolutePath
        _state.update {
            it.copy(stagedImagePath = file.absolutePath, tooLarge = false, sendError = null)
        }
    }

    fun consumeNavigateToThread() {
        _state.update { it.copy(navigateToThreadId = null) }
    }

    private fun restorePendingMms() {
        val fromStash = takeStashedPendingMmsUri(threadId)
        val raw = savedStateHandle.get<String>(KEY_PENDING_MMS_URI) ?: fromStash ?: return
        savedStateHandle[KEY_PENDING_MMS_URI] = raw
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        when (val existing = verdictFromProvider(uri)) {
            null -> _state.update { it.copy(sending = true) }
            else -> onMmsSendCompleted(MmsSendEvent(uri, existing))
        }
    }

    private fun verdictFromProvider(uri: Uri): MmsSendVerdict? {
        val projection = arrayOf(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.RESPONSE_STATUS)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val box = cursor.getInt(0)
                val hasStatus = !cursor.isNull(1)
                val status = if (hasStatus) cursor.getInt(1) else null
                when {
                    box == Telephony.Mms.MESSAGE_BOX_FAILED -> MmsSendVerdict.Failed
                    status == MmsSendComposer.RESPONSE_STATUS_OK -> MmsSendVerdict.Sent
                    status != null -> MmsSendVerdict.Failed
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun onMmsSendCompleted(event: MmsSendEvent) {
        val waiting = savedStateHandle.get<String>(KEY_PENDING_MMS_URI) ?: return
        if (!urisMatch(event.messageUri, waiting)) return
        savedStateHandle.remove<String>(KEY_PENDING_MMS_URI)
        if (event.ok) {
            _state.value.stagedImagePath?.let { File(it).delete() }
            savedStateHandle.remove<String>(KEY_STAGED_PATH)
            _state.update {
                it.copy(
                    draft = "",
                    sending = false,
                    sendError = null,
                    tooLarge = false,
                    stagedImagePath = null,
                )
            }
            refresh(announceLoading = false)
        } else {
            _state.update {
                it.copy(
                    sending = false,
                    sendError = context.getString(R.string.mms_send_failed),
                )
            }
        }
    }

    fun refresh(announceLoading: Boolean = true) {
        if (threadId == 0L) return
        val seq = refreshSeq.incrementAndGet()
        viewModelScope.launch {
            if (announceLoading) {
                _state.update { it.copy(loading = true) }
            }
            emitHeader()
            if (seq != refreshSeq.get()) return@launch

            val window = maxOf(MESSAGE_LIMIT, _state.value.messages.size)
            val raw = messageRepository.getMessages(threadId, window)
            if (seq != refreshSeq.get()) return@launch
            val addressFromMessages = raw.firstNotNullOfOrNull {
                it.address?.takeIf(String::isNotBlank)
            }
            if (!addressFromMessages.isNullOrBlank() && _state.value.address.isBlank()) {
                emitHeader(addressOverride = addressFromMessages)
            }
            val bubbles = toBubbles(raw, pruneCaches = true)
            if (seq != refreshSeq.get()) return@launch
            _state.update {
                it.copy(
                    messages = bubbles,
                    loading = false,
                    hasOlderMessages = !reachedOldest && raw.size >= window &&
                        window >= MESSAGE_LIMIT,
                )
            }
            launchMmsVisualLoads(seq, raw)
        }
    }

    /**
     * Next older page of this thread. Scroll-driven; does not change send
     * orchestration or auto-scroll policy for new incoming messages.
     */
    fun loadOlderMessages() {
        if (threadId == 0L || reachedOldest) return
        if (!_state.value.hasOlderMessages) return
        if (!loadOlderInFlight.compareAndSet(false, true)) return
        val seq = refreshSeq.get()
        viewModelScope.launch {
            try {
                val current = _state.value.messages
                if (current.isEmpty()) return@launch
                val oldestDate = current.minOf { it.date }
                val page = messageRepository.getMessages(
                    threadId,
                    MESSAGE_LIMIT,
                    beforeDateMillis = oldestDate,
                )
                if (seq != refreshSeq.get()) return@launch
                val existing = current.map { it.ref }.toSet()
                val unique = page.filter { it.ref !in existing }
                if (unique.isEmpty()) {
                    reachedOldest = true
                    _state.update { it.copy(hasOlderMessages = false) }
                    return@launch
                }
                val olderBubbles = toBubbles(unique.sortedBy { it.date }, pruneCaches = false)
                if (seq != refreshSeq.get()) return@launch
                _state.update {
                    val merged = (olderBubbles + it.messages).distinctBy { bubble -> bubble.ref }
                        .sortedBy { bubble -> bubble.date }
                    it.copy(
                        messages = merged,
                        hasOlderMessages = page.size >= MESSAGE_LIMIT,
                    )
                }
                if (page.size < MESSAGE_LIMIT) {
                    reachedOldest = true
                    _state.update { it.copy(hasOlderMessages = false) }
                }
                launchMmsVisualLoads(seq, unique)
            } finally {
                loadOlderInFlight.set(false)
            }
        }
    }

    private suspend fun toBubbles(
        raw: List<SmsMessage>,
        pruneCaches: Boolean,
    ): List<ThreadBubble> {
        val labelCache = HashMap<String, String>()
        suspend fun labelFor(addr: String): String {
            labelCache[addr]?.let { return it }
            val label = contactsRepository.resolveDisplayName(addr)?.takeIf { it.isNotBlank() }
                ?: addr
            labelCache[addr] = label
            return label
        }
        if (pruneCaches) {
            val liveMmsIds = raw.filter { it.kind == MessageRef.Kind.MMS }.map { it.id }.toSet()
            mmsTextOnly.keys.retainAll(liveMmsIds)
            visualsCache.keys.retainAll(liveMmsIds)
        }
        return raw.map { msg ->
            val outgoing = msg.isOutgoing()
            val senderAddr = if (outgoing) null else msg.address?.takeIf { it.isNotBlank() }
            val cached = if (msg.kind == MessageRef.Kind.MMS) {
                mmsTextOnly[msg.id] = msg.textOnly
                visualsCache[msg.id]
            } else {
                null
            }
            val (caption, tiles) = when {
                msg.kind != MessageRef.Kind.MMS -> msg.body.orEmpty() to emptyList()
                cached != null -> cached
                else -> "" to emptyList()
            }
            ThreadBubble(
                ref = msg.ref,
                body = caption,
                isOutgoing = outgoing,
                date = msg.date,
                senderAddress = senderAddr,
                senderLabel = senderAddr?.let { labelFor(it) },
                subject = msg.subject,
                showPhotoPlaceholder = msg.kind == MessageRef.Kind.MMS &&
                    !msg.textOnly &&
                    tiles.isEmpty(),
                tiles = tiles,
            )
        }
    }

    private fun launchMmsVisualLoads(seq: Int, raw: List<SmsMessage>) {
        raw.filter { it.kind == MessageRef.Kind.MMS }
            .forEach { msg ->
                viewModelScope.launch {
                    val (caption, tiles) = loadMmsVisuals(msg.id)
                    if (seq != refreshSeq.get()) return@launch
                    applyMmsVisuals(msg.id, caption, tiles)
                }
            }
    }

    /**
     * Participants + contacts only. Must not wait on [getMessages] or MMS parts —
     * the name is held hostage by photos today.
     */
    private suspend fun emitHeader(addressOverride: String? = null) {
        val participants = smsRepository.getThreadParticipantAddresses(threadId)
        val address = addressOverride?.takeIf { it.isNotBlank() }
            ?: _state.value.address.takeIf { it.isNotBlank() }
            ?: participants.firstOrNull()
            ?: ""
        // Preserve addresses added via "Add people" across refresh.
        val previousExtra = _state.value.sendAddresses
            .filter { it !in participants && it != address }
        val sendAddresses = (
            participants.ifEmpty { listOfNotNull(address.takeIf { it.isNotBlank() }) } +
                previousExtra
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val isGroup = sendAddresses.size > 1
        val participantLabels = sendAddresses.map { addr ->
            contactsRepository.resolveDisplayName(addr)?.takeIf { it.isNotBlank() } ?: addr
        }
        val displayName = if (address.isNotBlank()) {
            contactsRepository.resolveDisplayName(address)
        } else {
            null
        }
        val title = when {
            isGroup && participantLabels.isNotEmpty() ->
                participantLabels.joinToString(", ")
            !displayName.isNullOrBlank() -> displayName
            address.isNotBlank() -> address
            else -> "Conversation"
        }
        val subtitle = if (isGroup && participantLabels.isNotEmpty()) {
            formatGroupSubtitle(participantLabels)
        } else {
            THREAD_SUBTITLE_PERSONAL
        }
        val senderMatch = senderMatchForPrefill(address = address, displayName = displayName)
        val known = if (isGroup) {
            sendAddresses.any { contactsRepository.isKnownContact(it) }
        } else {
            address.isNotBlank() && contactsRepository.isKnownContact(address)
        }
        _state.update {
            it.copy(
                title = title,
                subtitle = subtitle,
                address = address,
                senderMatchValue = senderMatch,
                isKnownContact = known,
                isGroup = isGroup,
                sendAddresses = sendAddresses,
                participantLabels = participantLabels,
            )
        }
    }

    private fun applyMmsVisuals(mmsId: Long, caption: String, tiles: List<MmsTile>) {
        val textOnly = mmsTextOnly[mmsId] ?: true
        _state.update { state ->
            state.copy(
                messages = state.messages.map { bubble ->
                    if (bubble.ref.kind != MessageRef.Kind.MMS || bubble.ref.id != mmsId) {
                        bubble
                    } else {
                        bubble.copy(
                            body = caption,
                            tiles = tiles,
                            showPhotoPlaceholder = !textOnly && tiles.isEmpty(),
                        )
                    }
                },
            )
        }
    }

    /**
     * Tile tap for *not downloaded* / *failed*. Photo taps open the viewer
     * in the NavHost, not here.
     *
     * In-flight is process-scoped so leaving the thread and coming back
     * cannot start a second retrieve. Failed means the retrieve came back
     * and did not work — the receiver reports that through the pipeline,
     * which releases the claim so *Try again* can start another retrieve.
     * There is no timeout: `startDownload` is a PendingIntent handoff.
     */
    fun downloadMms(mmsId: Long) {
        if (mmsId <= 0L) return
        if (!claimDownload(mmsId)) return
        viewModelScope.launch {
            val stub = mmsRepository.notificationFor(mmsId)
            if (stub == null || stub.contentLocation.isNullOrBlank()) {
                releaseDownload(mmsId)
                applyDownloadUi(mmsId, MmsDownloadUi.Failed)
                return@launch
            }
            applyDownloadUi(mmsId, MmsDownloadUi.Downloading)
            incomingMessagePipeline.downloadPending(mmsId)
        }
    }

    private suspend fun loadMmsVisuals(mmsId: Long): Pair<String, List<MmsTile>> {
        while (true) {
            visualsCache[mmsId]?.let { return it }
            visualsInFlight[mmsId]?.let { return it.await() }
            val deferred = CompletableDeferred<Pair<String, List<MmsTile>>>()
            val winner = visualsInFlight.putIfAbsent(mmsId, deferred)
            if (winner != null) {
                return winner.await()
            }
            try {
                val loaded = computeMmsVisuals(mmsId)
                deferred.complete(loaded)
                return loaded
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
                throw t
            } finally {
                visualsInFlight.remove(mmsId, deferred)
            }
        }
    }

    private suspend fun computeMmsVisuals(mmsId: Long): Pair<String, List<MmsTile>> {
        visualsCache[mmsId]?.let { return it }
        val stub = mmsRepository.notificationFor(mmsId)
        if (stub != null) {
            val expired = stub.expiryMillis?.let { it < System.currentTimeMillis() } == true
            if (expired) {
                downloadUi.remove(mmsId)
                releaseDownload(mmsId)
            }
            val download = when {
                isDownloadInFlight(mmsId) -> MmsDownloadUi.Downloading
                isDownloadFailed(mmsId) -> MmsDownloadUi.Failed
                else -> downloadUi[mmsId] ?: MmsDownloadUi.Idle
            }
            val kind = stubTileKind(expired, download)
            return "" to listOf(
                MmsTile(messageId = mmsId, seq = 0, kind = kind),
            )
        }
        downloadUi.remove(mmsId)
        releaseDownload(mmsId)
        partsReadGate?.invoke(mmsId)
        partsReadCount.incrementAndGet()
        val parts = mmsRepository.getParts(mmsId)
        val caption = withContext(Dispatchers.IO) {
            buildString {
                for (part in parts) {
                    val type = part.contentType.substringBefore(';').trim().lowercase()
                    if (type != "text/plain" && !type.startsWith("text/plain;")) continue
                    val text = readPartText(part) ?: continue
                    if (isNotEmpty()) append('\n')
                    append(text)
                }
            }.trim()
        }
        val tiles = parts.mapNotNull { part ->
            val classified = classifyMmsPart(part.contentType, part.byteSize) ?: return@mapNotNull null
            val (kind, labelRes) = classified
            MmsTile(
                messageId = mmsId,
                seq = part.seq,
                kind = kind,
                uri = if (kind == MmsTileKind.Photo) part.uri else null,
                partLabelRes = labelRes,
            )
        }
        val loaded = caption to tiles
        visualsCache[mmsId] = loaded
        return loaded
    }

    private fun readPartText(part: MmsPartRow): String? {
        val fromStream = runCatching {
            context.contentResolver.openInputStream(part.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8).trim()
            }?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (!fromStream.isNullOrEmpty()) return fromStream
        // insertInbox stores the caption in Part.TEXT; the part stream is empty.
        return runCatching {
            context.contentResolver.query(
                part.uri,
                arrayOf(Telephony.Mms.Part.TEXT),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private fun applyDownloadUi(mmsId: Long, ui: MmsDownloadUi) {
        downloadUi[mmsId] = ui
        _state.update { state ->
            state.copy(
                messages = state.messages.map { bubble ->
                    if (bubble.ref.kind != MessageRef.Kind.MMS || bubble.ref.id != mmsId) {
                        bubble
                    } else {
                        bubble.copy(
                            tiles = bubble.tiles.map { tile ->
                                if (tile.kind == MmsTileKind.Photo ||
                                    tile.kind == MmsTileKind.Expired ||
                                    tile.kind == MmsTileKind.Video ||
                                    tile.kind == MmsTileKind.Audio ||
                                    tile.kind == MmsTileKind.Contact ||
                                    tile.kind == MmsTileKind.Unsupported
                                ) {
                                    tile
                                } else {
                                    tile.copy(kind = stubTileKind(expired = false, download = ui))
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    fun showDetails() {
        _state.update { it.copy(showDetails = true) }
    }

    fun dismissDetails() {
        _state.update { it.copy(showDetails = false) }
    }

    /**
     * Add a person from the system contact picker. Next send uses MMS to the
     * expanded set. Does not invent a contact UI.
     */
    fun addParticipant(address: String) {
        val number = address.trim()
        if (number.isEmpty()) return
        viewModelScope.launch {
            val newLabel = contactsRepository.resolveDisplayName(number)?.takeIf { it.isNotBlank() }
                ?: number
            _state.update { state ->
                if (state.sendAddresses.any { it.equals(number, ignoreCase = true) }) {
                    return@update state
                }
                val base = state.sendAddresses.ifEmpty {
                    listOfNotNull(state.address.takeIf { it.isNotBlank() })
                }
                val addresses = (base + number).distinct()
                val labels = addresses.map { addr ->
                    when {
                        addr.equals(number, ignoreCase = true) -> newLabel
                        else -> {
                            val i = state.sendAddresses.indexOfFirst {
                                it.equals(addr, ignoreCase = true)
                            }
                            if (i >= 0 && i < state.participantLabels.size) {
                                state.participantLabels[i]
                            } else {
                                addr
                            }
                        }
                    }
                }
                state.copy(
                    sendAddresses = addresses,
                    participantLabels = labels,
                    isGroup = addresses.size > 1,
                    subtitle = if (addresses.size > 1) {
                        formatGroupSubtitle(labels)
                    } else {
                        THREAD_SUBTITLE_PERSONAL
                    },
                    title = if (addresses.size > 1) {
                        labels.joinToString(", ")
                    } else {
                        state.title
                    },
                )
            }
        }
    }

    fun startSelection(messageId: MessageRef) {
        if (!smsRoleManager.isRoleHeld()) return
        selection.value = BatchSelection<MessageRef>().enter(messageId)
    }

    fun toggleSelection(messageId: MessageRef) {
        selection.update { it.toggle(messageId) }
    }

    fun exitSelection() {
        selection.value = BatchSelection()
        showDeleteConfirm.value = false
    }

    fun requestDeleteSelected() {
        if (!smsRoleManager.isRoleHeld()) return
        if (selection.value.selected.isEmpty()) return
        showDeleteConfirm.value = true
    }

    fun dismissDeleteConfirm() {
        showDeleteConfirm.value = false
    }

    fun confirmDeleteSelected() {
        if (!smsRoleManager.isRoleHeld()) {
            showDeleteConfirm.value = false
            return
        }
        val ids = selection.value.selected
        if (ids.isEmpty()) {
            showDeleteConfirm.value = false
            return
        }
        showDeleteConfirm.value = false
        selection.value = BatchSelection()
        commitPendingDeleteNow()
        pendingDeleteIds.value = ids
        undoToastCount.value = ids.size
        var job: Job? = null
        job = applicationScope.launch {
            delay(BATCH_DELETE_UNDO_MS)
            logWriteResult("deleteMessages", messageRepository.deleteMessages(ids.toList()))
            if (pendingDeleteIds.value == ids) {
                pendingDeleteIds.value = emptySet()
                undoToastCount.value = 0
            }
            if (pendingDeleteJob === job) pendingDeleteJob = null
        }
        pendingDeleteJob = job
    }

    fun undoPendingDelete() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteIds.value = emptySet()
        undoToastCount.value = 0
    }

    fun dismissUndoToast() {
        // UI only — the application-scoped job still owns the real delete.
        undoToastCount.value = 0
    }

    /** ⋮ menu — delete this conversation (deferred, same as batch). */
    fun requestDeleteConversation() {
        if (!smsRoleManager.isRoleHeld()) return
        if (threadId == 0L) return
        showDeleteConversationConfirm.value = true
    }

    fun dismissDeleteConversationConfirm() {
        showDeleteConversationConfirm.value = false
    }

    /**
     * Confirm whole-thread delete. Starts the deferred job on
     * [ConversationDeleteSession] (outlives this ViewModel) and returns so the
     * UI can pop immediately — Version 2 closes the thread on confirm; the
     * undo toast is hosted on the inbox.
     */
    fun confirmDeleteConversation() {
        if (!smsRoleManager.isRoleHeld()) {
            showDeleteConversationConfirm.value = false
            return
        }
        showDeleteConversationConfirm.value = false
        selection.value = BatchSelection()
        // Commit any in-flight message batch before starting the thread delete.
        commitPendingDeleteNow()
        conversationDeleteSession.start(threadId)
    }

    private fun commitPendingDeleteNow() {
        val ids = pendingDeleteIds.value
        if (ids.isEmpty()) return
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteIds.value = emptySet()
        undoToastCount.value = 0
        applicationScope.launch {
            logWriteResult("deleteMessages", messageRepository.deleteMessages(ids.toList()))
        }
    }

    private fun logWriteResult(where: String, result: SmsRepository.WriteResult) {
        when (result) {
            is SmsRepository.WriteResult.Success -> Unit
            is SmsRepository.WriteResult.RoleNotHeld ->
                Log.e(TAG, "$where: ROLE_SMS not held; messages still in provider")
            is SmsRepository.WriteResult.Failed ->
                Log.e(TAG, "$where: provider write failed; messages still in provider", result.cause)
        }
    }

    /**
     * One recipient, no photo → SMS. A photo, or two or more recipients → MMS
     * via [SmsSender.sendMms] (platform MMSC; no HTTP client).
     *
     * Oversize photos refuse here, before any provider write. MMS send is
     * accepted when the platform takes the PDU; the M-Send.conf verdict
     * arrives on [SmsSender.sendCompletions].
     */
    fun send() {
        val snapshot = _state.value
        val body = snapshot.draft.trim()
        val staged = MmsStaging.fileIfExists(snapshot.stagedImagePath)
        if ((body.isEmpty() && staged == null) || snapshot.sending) return
        if (!smsRoleManager.isRoleHeld()) {
            _state.update { it.copy(sendError = DISABLED_REASON) }
            return
        }
        val addresses = snapshot.sendAddresses.ifEmpty {
            listOfNotNull(snapshot.address.takeIf { it.isNotBlank() })
        }
        if (addresses.isEmpty()) {
            _state.update {
                it.copy(sendError = "Cannot send — no recipient on this thread.")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, sendError = null, tooLarge = false) }
            if (staged != null) {
                val budget = MmsImageLadder.imageBudgetBytes(
                    mmsTransport.carrierMaxMessageBytes(subscriptionId = null),
                )
                val encoded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    MmsImageEncoder.encode(staged.readBytes(), budget)
                }
                when (encoded) {
                    MmsEncodeResult.TooLarge -> {
                        _state.update {
                            it.copy(
                                sending = false,
                                tooLarge = true,
                                sendError = context.getString(R.string.mms_too_large),
                            )
                        }
                        return@launch
                    }
                    is MmsEncodeResult.Fit -> {
                        val image = MmsSendComposer.OutboundImage(
                            contentType = encoded.contentType,
                            bytes = encoded.bytes,
                            location = encoded.location,
                        )
                        val submitted = smsSender.sendMms(addresses, body, image)
                        if (submitted == null) {
                            _state.update {
                                it.copy(
                                    sending = false,
                                    sendError = context.getString(R.string.mms_send_failed),
                                )
                            }
                        } else {
                            rememberMmsSubmission(submitted)
                        }
                    }
                }
                return@launch
            }
            if (addresses.size == 1) {
                when (smsSender.send(addresses.single(), body)) {
                    is SmsSubmitResult.Submitted -> {
                        _state.update { it.copy(draft = "", sending = false, sendError = null) }
                        refresh()
                    }
                    is SmsSubmitResult.FailedBeforeSubmit -> {
                        _state.update {
                            it.copy(
                                sending = false,
                                sendError = "Message could not be sent.",
                            )
                        }
                    }
                }
            } else {
                val submitted = smsSender.sendMms(addresses, body)
                if (submitted == null) {
                    _state.update {
                        it.copy(
                            sending = false,
                            sendError = "Message could not be sent.",
                        )
                    }
                } else {
                    rememberMmsSubmission(submitted)
                }
            }
        }
    }

    private fun rememberMmsSubmission(submitted: MmsSubmitResult) {
        savedStateHandle[KEY_PENDING_MMS_URI] = submitted.messageUri.toString()
        if (submitted.threadId != 0L && submitted.threadId != threadId) {
            stashPendingMmsUri(submitted.threadId, submitted.messageUri.toString())
            threadId = submitted.threadId
            savedStateHandle[ARG_THREAD_ID] = submitted.threadId.toString()
            _state.update {
                it.copy(
                    threadId = submitted.threadId,
                    navigateToThreadId = submitted.threadId,
                )
            }
            refresh(announceLoading = false)
        }
    }

    companion object {
        private const val TAG = "ThreadViewModel"
        const val ARG_THREAD_ID = "threadId"
        const val MESSAGE_LIMIT = 200
        const val THREAD_SUBTITLE_PERSONAL = "Texts · this phone only"
        @Deprecated("Use THREAD_SUBTITLE_PERSONAL", ReplaceWith("THREAD_SUBTITLE_PERSONAL"))
        const val THREAD_SUBTITLE = THREAD_SUBTITLE_PERSONAL
        const val DISABLED_REASON = "Set Pinot Rouge as your default texting app to send."
        const val COMPOSER_PLACEHOLDER = "Text message"
        private const val KEY_STAGED_PATH = "staged_image_path"
        private const val KEY_CAMERA_PATH = "camera_capture_path"
        private const val KEY_PENDING_MMS_URI = "pending_mms_uri"

        private val stashedPendingMms = ConcurrentHashMap<Long, String>()

        private fun stashPendingMmsUri(threadId: Long, uri: String) {
            stashedPendingMms[threadId] = uri
        }

        private fun takeStashedPendingMmsUri(threadId: Long): String? =
            stashedPendingMms.remove(threadId)

        private fun urisMatch(eventUri: Uri?, waiting: String): Boolean {
            if (eventUri == null) return false
            if (eventUri.toString() == waiting) return true
            val waitingUri = runCatching { Uri.parse(waiting) }.getOrNull() ?: return false
            return runCatching {
                ContentUris.parseId(eventUri) == ContentUris.parseId(waitingUri)
            }.getOrDefault(false)
        }

        /**
         * Test seam: parked before [MmsRepository.getParts]. Production is null.
         * Must be installed *before* constructing the ViewModel — [init] calls
         * [refresh] immediately.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        @Volatile
        var partsReadGate: (suspend (Long) -> Unit)? = null

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        val partsReadCount = AtomicInteger(0)

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        fun resetPartsReadGate() {
            partsReadGate = null
            partsReadCount.set(0)
        }

        /**
         * Process-scoped. Survives ViewModel recreation; dies with the
         * process. A type-130 row does not tell us a retrieve is in flight.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun claimDownload(mmsId: Long): Boolean = MmsDownloadClaims.claim(mmsId)

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun releaseDownload(mmsId: Long) {
            MmsDownloadClaims.release(mmsId)
            MmsDownloadClaims.clearFailed(mmsId)
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isDownloadInFlight(mmsId: Long): Boolean = MmsDownloadClaims.isInFlight(mmsId)

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isDownloadFailed(mmsId: Long): Boolean = MmsDownloadClaims.isFailed(mmsId)

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun markDownloadFailed(mmsId: Long) {
            MmsDownloadClaims.markFailed(mmsId)
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun resetDownloadsInFlightForTests() {
            MmsDownloadClaims.resetForTests()
        }

        /**
         * Funnel prefill for `Sender IS`. Always [address], even when
         * [displayName] is a contact — the engine matches against the number
         * on the incoming PDU, not the contact book label.
         */
        fun senderMatchForPrefill(address: String, displayName: String?): String = address

        /**
         * Open-thread is the only mark-read trigger. Compose-new (`threadId == 0`)
         * is not reading a conversation.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun dispatchMarkThreadReadOnOpen(threadId: Long, markRead: (Long) -> Unit) {
            if (threadId != 0L) markRead(threadId)
        }

        /** "{n} people · Name, Name" — Version 3/4 thread subtitle for groups. */
        fun formatGroupSubtitle(labels: List<String>): String {
            val n = labels.size
            return "$n people · ${labels.joinToString(", ")}"
        }
    }
}

private fun SmsMessage.isOutgoing(): Boolean = when (kind) {
    MessageRef.Kind.SMS -> type.isOutgoingSmsType()
    MessageRef.Kind.MMS -> when (type) {
        Telephony.Mms.MESSAGE_BOX_SENT,
        Telephony.Mms.MESSAGE_BOX_OUTBOX,
        Telephony.Mms.MESSAGE_BOX_FAILED,
        -> true
        else -> false
    }
}

private fun Int.isOutgoingSmsType(): Boolean = when (this) {
    Telephony.Sms.MESSAGE_TYPE_SENT,
    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
    Telephony.Sms.MESSAGE_TYPE_FAILED,
    Telephony.Sms.MESSAGE_TYPE_QUEUED,
    -> true
    else -> false
}
