package com.pinotrouge.messaging.ui.filtered

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotToast
import com.pinotrouge.messaging.ui.media.MmsPictureTile
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.media.mmsTileContentDescription
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTextStyles
import com.pinotrouge.messaging.ui.theme.PinotTypography
import kotlinx.coroutines.delay

/**
 * Full-screen held message overlay. Title chrome is OverlayHeader in the NavHost —
 * this screen draws body + why panel + actions only.
 *
 * Body text is plain [Text] with no link annotations — links must stay inert.
 */
@Composable
fun HeldMessageRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HeldMessageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Successful action: show toast, then return to Held / Search.
    LaunchedEffect(state.finished) {
        if (state.finished) {
            delay(1_800L)
            onBack()
        }
    }

    HeldMessageScreen(
        state = state,
        onMoveToInbox = viewModel::moveToInbox,
        onBlockSender = viewModel::blockSender,
        onDelete = viewModel::deleteHeld,
        onDismissToast = viewModel::dismissToast,
        modifier = modifier,
    )
}

@Composable
fun HeldMessageScreen(
    state: HeldMessageUiState,
    onMoveToInbox: () -> Unit,
    onBlockSender: () -> Unit,
    onDelete: () -> Unit,
    onDismissToast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val shapes = LocalPinotShapes.current
    val message = state.message

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        when {
            message == null && !state.loading -> {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.held_message_gone),
                        style = PinotTypography.bodyMedium,
                        color = colors.dim,
                    )
                }
            }
            message != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screenPadding)
                        .padding(top = 12.dp, bottom = spacing.scrollBottomInset),
                ) {
                    Text(
                        text = message.sender,
                        style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
                        color = colors.text,
                    )
                    Text(
                        text = message.timeLabel,
                        style = PinotTypography.bodySmall.copy(fontSize = 11.sp),
                        color = colors.dimmer,
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    Spacer(Modifier.height(14.dp))

                    if (message.tiles.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (tile in message.tiles) {
                                MmsPictureTile(
                                    tile = tile,
                                    contentDescription = mmsTileContentDescription(
                                        tile = tile,
                                        isOutgoing = false,
                                        senderLabel = message.sender,
                                        senderAddress = message.sender,
                                    ),
                                    selected = false,
                                    // No handler: there is no viewer from a held
                                    // message, so the tile is described but is not
                                    // announced as a button.
                                    onClick = null,
                                )
                            }
                        }
                        if (message.body.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    // Full body bubble — plain Text only (no auto-link / LinkAnnotation).
                    if (message.body.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(shapes.lg))
                                .background(colors.incomingBubble)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = message.body,
                                style = PinotTextStyles.body,
                                color = colors.text,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Why this was held — accent900 / accent800 panel.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(shapes.md))
                            .background(colors.accent900)
                            .border(1.dp, colors.accent800, RoundedCornerShape(shapes.md))
                            .padding(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.held_message_why_kicker),
                            style = PinotTypography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                letterSpacing = 0.1.em,
                            ),
                            color = colors.accent300,
                        )
                        Text(
                            text = stringResource(
                                R.string.held_message_why_rule,
                                message.ruleName,
                            ),
                            style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp),
                            color = colors.text,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // Only when reason adds information beyond "Caught by {rule}".
                        message.displayReason?.let { extra ->
                            Text(
                                text = extra,
                                style = PinotTypography.bodySmall.copy(fontSize = 12.sp),
                                color = colors.dim,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.held_message_why_links),
                            style = PinotTypography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            ),
                            color = colors.dim,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            text = stringResource(R.string.held_message_why_silent),
                            style = PinotTypography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            ),
                            color = colors.dim,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PinotButton(
                            text = stringResource(R.string.filtered_move_to_inbox),
                            onClick = onMoveToInbox,
                            variant = PinotButtonVariant.Primary,
                            fillMaxWidth = true,
                        )
                        PinotButton(
                            text = stringResource(R.string.filtered_block_sender),
                            onClick = onBlockSender,
                            variant = PinotButtonVariant.Secondary,
                            fillMaxWidth = true,
                        )
                        PinotButton(
                            text = stringResource(R.string.filtered_delete),
                            onClick = onDelete,
                            variant = PinotButtonVariant.Ghost,
                            fillMaxWidth = true,
                        )
                    }
                }
            }
        }

        PinotToast(
            message = state.toastMessage,
            onDismiss = onDismissToast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

@Preview(name = "Held message · Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun HeldMessagePreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        HeldMessageScreen(
            state = HeldMessageUiState(
                message = HeldMessageDetailUi(
                    id = "1",
                    sender = "+1 (844) 555-0192",
                    body = "PRE-APPROVED for 5000 dollars — no credit check. " +
                        "Visit usps-redelivery-status.co/9182 to claim.",
                    timeLabel = "10:41",
                    ruleName = "Loan and crypto offers",
                    displayReason = null, // filter catch — rule named once above
                ),
                loading = false,
            ),
            onMoveToInbox = {},
            onBlockSender = {},
            onDelete = {},
            onDismissToast = {},
        )
    }
}

@Preview(name = "Held message · Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun HeldMessagePreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        HeldMessageScreen(
            state = HeldMessageUiState(
                message = HeldMessageDetailUi(
                    id = "1",
                    sender = "+1 (844) 555-0192",
                    body = "PRE-APPROVED for 5000 dollars — no credit check. " +
                        "Visit usps-redelivery-status.co/9182 to claim.",
                    timeLabel = "10:41",
                    ruleName = "Loan and crypto offers",
                    displayReason = null,
                ),
                loading = false,
            ),
            onMoveToInbox = {},
            onBlockSender = {},
            onDelete = {},
            onDismissToast = {},
        )
    }
}

@Preview(name = "Held message · Dark · Blocked", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HeldMessagePreviewBlocked() {
    PinotRougeTheme(darkTheme = true) {
        HeldMessageScreen(
            state = HeldMessageUiState(
                message = HeldMessageDetailUi(
                    id = "2",
                    sender = "88022",
                    body = "Your code is 123456",
                    timeLabel = "Yesterday",
                    ruleName = "System",
                    displayReason = "Blocked sender",
                ),
                loading = false,
            ),
            onMoveToInbox = {},
            onBlockSender = {},
            onDelete = {},
            onDismissToast = {},
        )
    }
}

@Preview(name = "Held message · Dark · Photo", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun HeldMessagePreviewDarkPhoto() {
    PinotRougeTheme(darkTheme = true) {
        HeldMessageScreen(
            state = HeldMessageUiState(
                message = HeldMessageDetailUi(
                    id = "3",
                    sender = "18885550177",
                    body = "",
                    timeLabel = "8:05",
                    ruleName = "Loan and crypto offers",
                    displayReason = null,
                    tiles = listOf(
                        MmsTile(messageId = 1L, seq = 0, kind = MmsTileKind.Photo),
                    ),
                ),
                loading = false,
            ),
            onMoveToInbox = {},
            onBlockSender = {},
            onDelete = {},
            onDismissToast = {},
        )
    }
}
