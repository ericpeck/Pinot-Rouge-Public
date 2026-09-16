package com.pinotrouge.messaging.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinotrouge.messaging.ui.theme.LocalPinotColors

/**
 * V6's 306 / 222 / 182 / 110 / 78 read as ratios of the measured grid
 * width. Eric's call 2026-08-25: 80% of thread width, no fixed dp because
 * phones differ. Gap and radius stay V6's constants.
 */
object MmsPictureGridMetrics {
    const val WIDTH_FRACTION = 0.80f
    val Gap: Dp = 3.dp
    val Radius: Dp = 20.dp

    fun heightFraction(photoCount: Int): Float = when {
        photoCount <= 1 -> 0.725f
        photoCount == 2 -> 0.595f
        photoCount == 3 -> 0.729f
        else -> 0.784f
    }

    /** Overlay on the 4th cell when there are more than four photos. */
    fun overflowCount(photoCount: Int): Int =
        if (photoCount > 4) photoCount - 3 else 0
}

@Composable
fun MmsPictureGrid(
    tiles: List<MmsTile>,
    isOutgoing: Boolean,
    senderLabel: String?,
    senderAddress: String?,
    selected: Boolean,
    onClickTile: (MmsTile) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photos = tiles.filter { it.kind == MmsTileKind.Photo }
    if (photos.isEmpty()) return
    val visible = photos.take(4)
    val overflow = MmsPictureGridMetrics.overflowCount(photos.size)
    val gap = MmsPictureGridMetrics.Gap
    val colors = LocalPinotColors.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mms-picture-grid"),
    ) {
        val gridH = maxWidth * MmsPictureGridMetrics.heightFraction(photos.size)
        Box(
            modifier = Modifier
                .width(maxWidth)
                .height(gridH)
                .clip(RoundedCornerShape(MmsPictureGridMetrics.Radius))
                .background(colors.neutral800),
        ) {
            when (photos.size) {
                1 -> GridCell(
                    tile = visible[0],
                    isOutgoing = isOutgoing,
                    senderLabel = senderLabel,
                    senderAddress = senderAddress,
                    selected = selected,
                    onClick = { onClickTile(visible[0]) },
                    onLongPress = onLongPress,
                    modifier = Modifier.fillMaxSize(),
                )
                2 -> Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    visible.forEach { tile ->
                        GridCell(
                            tile = tile,
                            isOutgoing = isOutgoing,
                            senderLabel = senderLabel,
                            senderAddress = senderAddress,
                            selected = selected,
                            onClick = { onClickTile(tile) },
                            onLongPress = onLongPress,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
                3 -> Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    GridCell(
                        tile = visible[0],
                        isOutgoing = isOutgoing,
                        senderLabel = senderLabel,
                        senderAddress = senderAddress,
                        selected = selected,
                        onClick = { onClickTile(visible[0]) },
                        onLongPress = onLongPress,
                        modifier = Modifier
                            .weight(1.5f)
                            .fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        visible.drop(1).forEach { tile ->
                            GridCell(
                                tile = tile,
                                isOutgoing = isOutgoing,
                                senderLabel = senderLabel,
                                senderAddress = senderAddress,
                                selected = selected,
                                onClick = { onClickTile(tile) },
                                onLongPress = onLongPress,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
                else -> Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    GridCell(
                        tile = visible[0],
                        isOutgoing = isOutgoing,
                        senderLabel = senderLabel,
                        senderAddress = senderAddress,
                        selected = selected,
                        onClick = { onClickTile(visible[0]) },
                        onLongPress = onLongPress,
                        modifier = Modifier
                            .weight(1.5f)
                            .fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        val stack = visible.drop(1)
                        stack.forEachIndexed { index, tile ->
                            GridCell(
                                tile = tile,
                                isOutgoing = isOutgoing,
                                senderLabel = senderLabel,
                                senderAddress = senderAddress,
                                selected = selected,
                                onClick = { onClickTile(tile) },
                                onLongPress = onLongPress,
                                overflow = if (overflow > 0 && index == stack.lastIndex) overflow else 0,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    tile: MmsTile,
    isOutgoing: Boolean,
    senderLabel: String?,
    senderAddress: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    overflow: Int = 0,
) {
    val colors = LocalPinotColors.current
    val description = mmsTileContentDescription(
        tile = tile,
        isOutgoing = isOutgoing,
        senderLabel = senderLabel,
        senderAddress = senderAddress,
    )
    val tappable = tile.opensViewer || tile.runsDownload
    var cellW by remember(tile.messageId, tile.seq) { mutableIntStateOf(0) }
    var cellH by remember(tile.messageId, tile.seq) { mutableIntStateOf(0) }
    val bitmap = tile.uri?.takeIf { cellW > 0 && cellH > 0 }?.let { uri ->
        rememberMmsPartImage(uri, cellW, cellH, DecodeScale.Cover)
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                cellW = it.width
                cellH = it.height
            }
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.neutral800)
            .testTag(mmsTileTag(tile))
            .semantics {
                contentDescription = description
                if (tappable) role = Role.Button
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x940A080E))
                    .testTag("mms-grid-overflow"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    color = Color(0xFFF4F2F6),
                    fontSize = 17.sp,
                )
            }
        }
    }
}
