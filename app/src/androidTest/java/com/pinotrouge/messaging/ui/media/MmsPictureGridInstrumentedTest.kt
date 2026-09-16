package com.pinotrouge.messaging.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import com.pinotrouge.messaging.ui.filtered.FilteredGroup
import com.pinotrouge.messaging.ui.filtered.FilteredScreen
import com.pinotrouge.messaging.ui.filtered.FilteredUiState
import com.pinotrouge.messaging.ui.filtered.HeldMessageDetailUi
import com.pinotrouge.messaging.ui.filtered.HeldMessageScreen
import com.pinotrouge.messaging.ui.filtered.HeldMessageUi
import com.pinotrouge.messaging.ui.filtered.HeldMessageUiState
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Thread pictures are an 80%-width grid; Filtered and held stay 178×132.
 */
@RunWith(AndroidJUnit4::class)
class MmsPictureGridInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach { it.delete() }
        files.clear()
        MmsPartImageLoader.resetForTests()
    }

    @Test
    fun singlePhoto_fillsGrid_sameBoundsForPortraitAndLandscape() {
        val portrait = photoTile(messageId = 1L, seq = 0, jpeg = jpegUri(400, 800))
        val landscape = photoTile(messageId = 2L, seq = 0, jpeg = jpegUri(1600, 400))
        composeRule.setContent {
            PinotRougeTheme {
                Column {
                    Box(Modifier.width(GRID_W)) {
                        MmsPictureGrid(
                            tiles = listOf(portrait),
                            isOutgoing = false,
                            senderLabel = SENDER,
                            senderAddress = SENDER,
                            selected = false,
                            onClickTile = {},
                            onLongPress = {},
                        )
                    }
                    Box(Modifier.width(GRID_W)) {
                        MmsPictureGrid(
                            tiles = listOf(landscape),
                            isOutgoing = false,
                            senderLabel = SENDER,
                            senderAddress = SENDER,
                            selected = false,
                            onClickTile = {},
                            onLongPress = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(mmsTileTag(portrait)).assertWidthIsEqualTo(GRID_W)
        composeRule.onNodeWithTag(mmsTileTag(portrait)).assertHeightIsEqualTo(
            GRID_W * MmsPictureGridMetrics.heightFraction(1),
        )
        composeRule.onNodeWithTag(mmsTileTag(landscape)).assertWidthIsEqualTo(GRID_W)
        composeRule.onNodeWithTag(mmsTileTag(landscape)).assertHeightIsEqualTo(
            GRID_W * MmsPictureGridMetrics.heightFraction(1),
        )
    }

    @Test
    fun twoThreeFour_eachCellHasOwnTagAndDescription() {
        composeRule.setContent {
            PinotRougeTheme {
                Column {
                    Box(Modifier.width(GRID_W)) {
                        MmsPictureGrid(
                            tiles = photos(count = 2, messageId = 20L),
                            isOutgoing = false,
                            senderLabel = SENDER,
                            senderAddress = SENDER,
                            selected = false,
                            onClickTile = {},
                            onLongPress = {},
                        )
                    }
                    Box(Modifier.width(GRID_W)) {
                        MmsPictureGrid(
                            tiles = photos(count = 3, messageId = 21L),
                            isOutgoing = false,
                            senderLabel = SENDER,
                            senderAddress = SENDER,
                            selected = false,
                            onClickTile = {},
                            onLongPress = {},
                        )
                    }
                    Box(Modifier.width(GRID_W)) {
                        MmsPictureGrid(
                            tiles = photos(count = 4, messageId = 22L),
                            isOutgoing = false,
                            senderLabel = SENDER,
                            senderAddress = SENDER,
                            selected = false,
                            onClickTile = {},
                            onLongPress = {},
                        )
                    }
                }
            }
        }
        assertCellCount(messageId = 20L, count = 2)
        assertCellCount(messageId = 21L, count = 3)
        assertCellCount(messageId = 22L, count = 4)
    }

    @Test
    fun fivePhotos_fourCells_lastShowsPlusTwo() {
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.width(GRID_W)) {
                    MmsPictureGrid(
                        tiles = photos(count = 5, messageId = 30L),
                        isOutgoing = false,
                        senderLabel = SENDER,
                        senderAddress = SENDER,
                        selected = false,
                        onClickTile = {},
                        onLongPress = {},
                    )
                }
            }
        }
        assertCellCount(messageId = 30L, count = 4)
        composeRule.onNodeWithTag("mms-tile-photo-30-4").assertDoesNotExist()
        composeRule.onNodeWithTag("mms-grid-overflow", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("+2", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tapSeq2_opensThatPhoto() {
        val opened = mutableListOf<Int>()
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.width(GRID_W)) {
                    MmsPictureGrid(
                        tiles = photos(count = 4, messageId = 40L),
                        isOutgoing = false,
                        senderLabel = SENDER,
                        senderAddress = SENDER,
                        selected = false,
                        onClickTile = { opened += it.seq },
                        onLongPress = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("mms-tile-photo-40-2").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(2), opened)
    }

    @Test
    fun filteredListTile_stays178By132() {
        val filtered = photoTile(messageId = 50L, seq = 0)
        composeRule.setContent {
            PinotRougeTheme {
                FilteredScreen(
                    state = FilteredUiState(
                        groups = listOf(
                            FilteredGroup(
                                ruleId = "r-photo",
                                label = RULE_NAME,
                                items = listOf(
                                    HeldMessageUi(
                                        id = "held-filtered",
                                        sender = SENDER,
                                        timeLabel = "10:41",
                                        preview = "(Photo)",
                                        reason = "Filter: $RULE_NAME",
                                        body = "",
                                        ruleId = "r-photo",
                                        ruleName = RULE_NAME,
                                        tiles = listOf(filtered),
                                    ),
                                ),
                            ),
                        ),
                        heldCount = 1,
                    ),
                    onOpenHeldMessage = {},
                    onOpenFilters = {},
                    onRequestDeleteAll = {},
                    onConfirmDeleteAll = {},
                    onDismissDeleteAll = {},
                    onDismissToast = {},
                )
            }
        }
        composeRule.onNodeWithTag(mmsTileTag(filtered)).assertWidthIsEqualTo(MmsPictureTileWidth)
        composeRule.onNodeWithTag(mmsTileTag(filtered)).assertHeightIsEqualTo(MmsPictureTileHeight)
    }

    @Test
    fun heldDetailTile_stays178By132() {
        val held = photoTile(messageId = 51L, seq = 0)
        composeRule.setContent {
            PinotRougeTheme {
                HeldMessageScreen(
                    state = HeldMessageUiState(
                        message = HeldMessageDetailUi(
                            id = "held-detail",
                            sender = SENDER,
                            body = "",
                            timeLabel = "10:41",
                            ruleName = RULE_NAME,
                            displayReason = null,
                            tiles = listOf(held),
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
        composeRule.onNodeWithTag(mmsTileTag(held)).assertWidthIsEqualTo(MmsPictureTileWidth)
        composeRule.onNodeWithTag(mmsTileTag(held)).assertHeightIsEqualTo(MmsPictureTileHeight)
    }

    @Test
    fun gridCellWithHandler_isAButton() {
        val gridTile = photoTile(messageId = 60L, seq = 0)
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.width(GRID_W)) {
                    MmsPictureGrid(
                        tiles = listOf(gridTile),
                        isOutgoing = false,
                        senderLabel = SENDER,
                        senderAddress = SENDER,
                        selected = false,
                        onClickTile = {},
                        onLongPress = {},
                    )
                }
            }
        }
        val gridNode = composeRule.onNodeWithTag(mmsTileTag(gridTile))
        gridNode.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        gridNode.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    @Test
    fun heldDetailTile_isNotAButton() {
        val heldTile = photoTile(messageId = 61L, seq = 0)
        composeRule.setContent {
            PinotRougeTheme {
                HeldMessageScreen(
                    state = HeldMessageUiState(
                        message = HeldMessageDetailUi(
                            id = "held-semantics",
                            sender = SENDER,
                            body = "",
                            timeLabel = "10:41",
                            ruleName = RULE_NAME,
                            displayReason = null,
                            tiles = listOf(heldTile),
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
        val heldNode = composeRule.onNodeWithTag(mmsTileTag(heldTile))
        heldNode.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button).not(),
        )
        heldNode.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).not())
    }

    @Test
    fun decodeTarget_matchesDisplayedCellPixels() {
        val uri = jpegUri(800, 600)
        val tile = photoTile(messageId = 70L, seq = 0, jpeg = uri)
        MmsPartImageLoader.resetForTests()
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.width(GRID_W)) {
                    MmsPictureGrid(
                        tiles = listOf(tile),
                        isOutgoing = false,
                        senderLabel = SENDER,
                        senderAddress = SENDER,
                        selected = false,
                        onClickTile = {},
                        onLongPress = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val size = composeRule.onNodeWithTag(mmsTileTag(tile)).fetchSemanticsNode().size
        assertTrue("cell must have laid out", size.width > 0 && size.height > 0)
        val loader = MmsPartImageLoader.get(context)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            loader.contains(uri, size.width, size.height, DecodeScale.Cover)
        }
        assertTrue(
            "decode must use the displayed ${size.width}×${size.height}, not 178×132",
            loader.contains(uri, size.width, size.height, DecodeScale.Cover),
        )
    }

    private fun assertCellCount(messageId: Long, count: Int) {
        for (seq in 0 until count) {
            val tile = MmsTile(messageId, seq, MmsTileKind.Photo, Uri.parse("content://mms/part/$messageId-$seq"))
            composeRule.onNodeWithTag(mmsTileTag(tile)).assertIsDisplayed()
            composeRule.onNodeWithTag(mmsTileTag(tile)).assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            )
        }
    }

    private fun photos(count: Int, messageId: Long): List<MmsTile> =
        (0 until count).map { seq ->
            photoTile(messageId = messageId, seq = seq)
        }

    private fun photoTile(
        messageId: Long,
        seq: Int,
        jpeg: Uri = Uri.parse("content://mms/part/$messageId-$seq"),
    ) = MmsTile(
        messageId = messageId,
        seq = seq,
        kind = MmsTileKind.Photo,
        uri = jpeg,
    )

    private fun jpegUri(width: Int, height: Int): Uri {
        val dir = File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "grid-${width}x$height-${files.size}.jpg")
        files += file
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return FileProvider.getUriForFile(
            context,
            PlatformMmsTransport.authority(context.packageName),
            file,
        )
    }

    private companion object {
        val GRID_W = 288.dp
        const val SENDER = "+15555550100"
        const val RULE_NAME = "Loan and crypto offers"
    }
}
