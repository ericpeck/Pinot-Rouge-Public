package com.pinotrouge.messaging.ui.media

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PhotoViewerUiState(
    val uri: android.net.Uri? = null,
    val contentDescription: String = "",
)

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mmsRepository: MmsRepository,
    private val contactsRepository: ContactsRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val mmsId: Long =
        savedStateHandle.get<Long>(ARG_MMS_ID)
            ?: savedStateHandle.get<String>(ARG_MMS_ID)?.toLongOrNull()
            ?: 0L
    private val seq: Int =
        savedStateHandle.get<Int>(ARG_SEQ)
            ?: savedStateHandle.get<String>(ARG_SEQ)?.toIntOrNull()
            ?: 0

    private val _state = MutableStateFlow(PhotoViewerUiState())
    val uiState: StateFlow<PhotoViewerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (mmsId == 0L) return
        val part = mmsRepository.getParts(mmsId).firstOrNull { it.seq == seq } ?: return
        val (outgoing, from) = withContext(Dispatchers.IO) {
            isOutgoing(mmsId) to originatorLabel(mmsId)
        }
        val description = if (outgoing) {
            context.getString(R.string.mms_a11y_photo_sent)
        } else {
            inboundPhotoDescription(
                from = from,
                unknownSender = { context.getString(R.string.mms_a11y_photo_unknown_sender) },
                photoFrom = { token -> context.getString(R.string.mms_a11y_photo_from, token) },
            )
        }
        _state.value = PhotoViewerUiState(uri = part.uri, contentDescription = description)
    }

    private fun isOutgoing(id: Long): Boolean {
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
        return context.contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.MESSAGE_BOX),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            when (cursor.getInt(0)) {
                Telephony.Mms.MESSAGE_BOX_SENT,
                Telephony.Mms.MESSAGE_BOX_OUTBOX,
                Telephony.Mms.MESSAGE_BOX_FAILED,
                -> true
                else -> false
            }
        } == true
    }

    private suspend fun originatorLabel(id: Long): String {
        val addrUri = android.net.Uri.parse("content://mms/$id/addr")
        val address = context.contentResolver.query(
            addrUri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
            val typeIdx = cursor.getColumnIndex(Telephony.Mms.Addr.TYPE)
            while (cursor.moveToNext()) {
                if (typeIdx >= 0 && cursor.getInt(typeIdx) != ADDR_TYPE_FROM) continue
                val value = cursor.getString(addrIdx)?.trim().orEmpty()
                if (value.isNotEmpty()) return@use value
            }
            null
        }
        if (address.isNullOrBlank()) return ""
        return contactsRepository.resolveDisplayName(address)?.takeIf { it.isNotBlank() } ?: address
    }

    companion object {
        const val ARG_MMS_ID = "mmsId"
        const val ARG_SEQ = "seq"
        private const val ADDR_TYPE_FROM = 0x89
    }
}

@Composable
fun PhotoViewerRoute(
    mmsId: Long,
    seq: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    @Suppress("UNUSED_PARAMETER")
    val unused = mmsId to seq
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PhotoViewerScreen(
        state = state,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun PhotoViewerScreen(
    state: PhotoViewerUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val bitmap = state.uri?.let { uri ->
        rememberMmsPartImage(
            uri = uri,
            targetWidthPx = metrics.widthPixels,
            targetHeightPx = metrics.heightPixels,
            scale = DecodeScale.Fit,
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.neutral900)
            .testTag("mms-photo-viewer")
            .semantics { this.contentDescription = state.contentDescription },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("mms-photo-viewer-image"),
                contentScale = ContentScale.Fit,
            )
        }
        PinotIconButton(
            onClick = onBack,
            contentDescription = stringResource(R.string.thread_back),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .testTag("mms-photo-viewer-back"),
        ) {
            Icon(
                painter = painterResource(PinotIcons.Back),
                contentDescription = null,
                tint = colors.text,
            )
        }
    }
}

@Preview(name = "Photo viewer · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PhotoViewerPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        PhotoViewerScreen(state = PhotoViewerUiState(contentDescription = "Photo you sent"), onBack = {})
    }
}

@Preview(name = "Photo viewer · Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PhotoViewerPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        PhotoViewerScreen(state = PhotoViewerUiState(contentDescription = "Photo you sent"), onBack = {})
    }
}
