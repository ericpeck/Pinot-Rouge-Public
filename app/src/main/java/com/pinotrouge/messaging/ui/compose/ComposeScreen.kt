package com.pinotrouge.messaging.ui.compose

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotInput
import com.pinotrouge.messaging.ui.components.PinotToast
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography
import com.pinotrouge.messaging.ui.util.resolvePhoneNumber

/**
 * Compose-sheet entry point. ViewModel via hiltViewModel(); NavHost calls this
 * fully-qualified with no import (Wave 3 PinotNavHost protocol).
 *
 * Reads [com.pinotrouge.messaging.sms.SendToActivity] pending prefs on open.
 */
@Composable
fun ComposeRoute(
    onClose: () -> Unit,
    onSent: (threadId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshRole()
                viewModel.consumePendingCompose()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.sentThreadId) {
        val id = state.sentThreadId ?: return@LaunchedEffect
        viewModel.consumeSentNavigation()
        onSent(id)
    }

    val context = LocalContext.current
    // System contact picker — not a bespoke design (V3/V4 leave that open).
    val pickContact = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        ContactsRepository.clearCachedLookups(context)
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val number = resolvePhoneNumber(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addRecipientFromPicker(number)
    }

    ComposeScreen(
        state = state,
        onClose = onClose,
        onRecipientChange = viewModel::onRecipientChange,
        onBodyChange = viewModel::onBodyChange,
        onSend = viewModel::send,
        onPickContact = {
            pickContact.launch(
                Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ),
            )
        },
        onDismissToast = viewModel::dismissSentToast,
        modifier = modifier,
    )
}

@Composable
fun ComposeScreen(
    state: ComposeUiState,
    onClose: () -> Unit,
    onRecipientChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickContact: () -> Unit = {},
    onDismissToast: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val recipientCount = remember(state.recipient) {
        ComposeViewModel.parseRecipients(state.recipient).size
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim — full-screen; dismiss on tap. Sheet is a sibling so it is not under this clickable.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = shapes.lg, topEnd = shapes.lg))
                .background(colors.surface)
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ComposeViewModel.TITLE,
                    style = PinotTypography.titleLarge.copy(fontSize = 16.sp),
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onClose,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.Close),
                        contentDescription = "Close",
                        tint = colors.text,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                PinotInput(
                    value = state.recipient,
                    onValueChange = onRecipientChange,
                    label = ComposeViewModel.LABEL_TO,
                    placeholder = ComposeViewModel.PLACEHOLDER_TO,
                    enabled = state.roleHeld && !state.sending,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                PinotIconButton(
                    onClick = onPickContact,
                    contentDescription = stringResource(R.string.compose_add_contact),
                    enabled = state.roleHeld && !state.sending,
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.Plus),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (recipientCount >= 2) {
                Text(
                    text = stringResource(R.string.compose_group_hint, recipientCount),
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.dimmer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            PinotInput(
                value = state.body,
                onValueChange = onBodyChange,
                label = ComposeViewModel.LABEL_MESSAGE,
                placeholder = ComposeViewModel.PLACEHOLDER_MESSAGE,
                enabled = state.roleHeld && !state.sending,
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.heightIn(min = 74.dp),
            )

            if (!state.roleHeld) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = ComposeViewModel.DISABLED_REASON,
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.dimmer,
                )
            }

            if (state.sendError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.sendError,
                    style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.accent,
                )
            }

            Spacer(Modifier.height(12.dp))

            val canSend = state.roleHeld &&
                !state.sending &&
                state.recipient.isNotBlank() &&
                state.body.isNotBlank()

            PinotButton(
                text = ComposeViewModel.SEND,
                onClick = onSend,
                variant = PinotButtonVariant.Primary,
                enabled = canSend,
                fillMaxWidth = true,
                modifier = Modifier.heightIn(min = 42.dp),
            )
        }

        if (state.showSentToast) {
            PinotToast(
                message = ComposeViewModel.TOAST_SENT,
                onDismiss = onDismissToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 74.dp),
            )
        }
    }
}

@Preview(name = "Compose · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ComposePreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        ComposeScreen(
            state = ComposeUiState(
                recipient = "18445550192",
                body = "On my way.",
                roleHeld = true,
            ),
            onClose = {},
            onRecipientChange = {},
            onBodyChange = {},
            onSend = {},
        )
    }
}

@Preview(name = "Compose · Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ComposePreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        ComposeScreen(
            state = ComposeUiState(
                recipient = "",
                body = "",
                roleHeld = true,
            ),
            onClose = {},
            onRecipientChange = {},
            onBodyChange = {},
            onSend = {},
        )
    }
}

@Preview(name = "Compose · Dark · no role", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ComposePreviewDarkNoRole() {
    PinotRougeTheme(darkTheme = true) {
        ComposeScreen(
            state = ComposeUiState(
                recipient = "Maya",
                body = "Hello",
                roleHeld = false,
            ),
            onClose = {},
            onRecipientChange = {},
            onBodyChange = {},
            onSend = {},
        )
    }
}
