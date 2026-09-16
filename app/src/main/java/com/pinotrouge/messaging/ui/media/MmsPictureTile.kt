package com.pinotrouge.messaging.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotTypography

val MmsPictureTileWidth = 178.dp
val MmsPictureTileHeight = 132.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MmsPictureTile(
    tile: MmsTile,
    contentDescription: String,
    selected: Boolean,
    /**
     * Null when the tile is not a control. Held-message detail passes null:
     * a held photo's bytes are addressable, but there is nowhere to go — they
     * are not in Telephony until release. Interactivity is read off *having a
     * handler*, never off whether the bytes happen to be addressable.
     */
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(shapes.lg)
    // A control only if it was given a handler AND there is somewhere to go.
    val tappable = onClick != null && (tile.opensViewer || tile.runsDownload)
    val targetW = with(density) { MmsPictureTileWidth.roundToPx() }
    val targetH = with(density) { MmsPictureTileHeight.roundToPx() }
    val bitmap = tile.uri?.let { uri ->
        rememberMmsPartImage(uri, targetW, targetH, DecodeScale.Cover)
    }

    Box(
        modifier = modifier
            .size(MmsPictureTileWidth, MmsPictureTileHeight)
            .clip(shape)
            .background(colors.neutral800)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.divider,
                shape = shape,
            )
            .testTag(mmsTileTag(tile))
            .semantics {
                this.contentDescription = contentDescription
                if (tappable) role = Role.Button
            }
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongPress,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (tile.kind) {
            MmsTileKind.Photo -> {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            MmsTileKind.Downloading -> {
                TileCopy(stringResource(R.string.mms_downloading))
            }
            MmsTileKind.NotDownloaded -> {
                TileCopy(
                    primary = stringResource(R.string.mms_not_downloaded),
                    action = stringResource(R.string.mms_download),
                )
            }
            MmsTileKind.Failed -> {
                TileCopy(
                    primary = stringResource(R.string.mms_download_failed),
                    action = stringResource(R.string.mms_try_again),
                )
            }
            MmsTileKind.Expired -> {
                TileCopy(stringResource(R.string.mms_expired))
            }
            MmsTileKind.Video,
            MmsTileKind.Audio,
            MmsTileKind.Contact,
            MmsTileKind.Unsupported,
            -> {
                val label = tile.partLabelRes?.let { stringResource(it) }.orEmpty()
                TileCopy(
                    primary = label,
                    action = stringResource(R.string.mms_part_unsupported),
                )
            }
        }
    }
}

fun mmsTileTag(tile: MmsTile): String = "mms-tile-${tile.kind.name.lowercase()}-${tile.messageId}-${tile.seq}"

@Composable
private fun TileCopy(
    primary: String,
    action: String? = null,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = Modifier.padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = primary,
            style = PinotTypography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = action,
                style = PinotTypography.labelLarge.copy(fontSize = 13.sp),
                color = colors.accent,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun mmsTileContentDescription(
    tile: MmsTile,
    isOutgoing: Boolean,
    senderLabel: String?,
    senderAddress: String? = null,
): String {
    val from = senderLabel?.takeIf { it.isNotBlank() }
        ?: senderAddress?.takeIf { it.isNotBlank() }
    return when (tile.kind) {
        MmsTileKind.Photo -> if (isOutgoing) {
            stringResource(R.string.mms_a11y_photo_sent)
        } else {
            val token = inboundPhotoToken(from)
            if (token != null) {
                stringResource(R.string.mms_a11y_photo_from, token)
            } else {
                stringResource(R.string.mms_a11y_photo_unknown_sender)
            }
        }
        MmsTileKind.Downloading -> stringResource(R.string.mms_a11y_downloading)
        MmsTileKind.NotDownloaded -> stringResource(R.string.mms_a11y_not_downloaded)
        MmsTileKind.Failed -> stringResource(R.string.mms_a11y_download_failed)
        MmsTileKind.Expired -> stringResource(R.string.mms_a11y_expired)
        MmsTileKind.Video,
        MmsTileKind.Audio,
        MmsTileKind.Contact,
        MmsTileKind.Unsupported,
        -> {
            val label = tile.partLabelRes?.let { stringResource(it) }
                ?: stringResource(R.string.mms_part_unsupported)
            stringResource(R.string.mms_a11y_unsupported, label)
        }
    }
}

/**
 * Item 11's `mms_a11y_photo_from` needs a non-blank `%1$s`. Prefer the
 * contact name, then the number — never format an empty name into
 * "Photo from ".
 *
 * With neither a name nor a number the answer is [unknownSender], copy item
 * 15. It used to be `""`, which is not "no description": the node is still
 * announced, with nothing in it.
 *
 * Both strings are resolved by the caller, so this stays callable from the
 * viewer's ViewModel as well as from a composable.
 */
fun inboundPhotoToken(from: String?): String? = from?.takeIf { it.isNotBlank() }

fun inboundPhotoDescription(
    from: String?,
    unknownSender: () -> String,
    photoFrom: (String) -> String,
): String {
    val token = inboundPhotoToken(from)
    return if (token != null) photoFrom(token) else unknownSender()
}
