package com.pinotrouge.messaging.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lane B — what an MMS tile *announces*.
 *
 * Two defects, one node, so one suite run:
 *  - a held-detail tile announced `Role.Button` and did nothing when
 *    double-tapped, because interactivity was read off `tile.opensViewer`
 *    rather than off having a handler;
 *  - a sender with no contact name **and** no number produced an empty
 *    content description, which is announced-with-nothing, not silent.
 *
 * Its own fixture on purpose: `HeldMediaUiInstrumentedTest` belongs to
 * `fix-held-media-tiles-off-main`.
 */
@RunWith(AndroidJUnit4::class)
class MmsTileSemanticsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach { it.delete() }
        files.clear()
    }

    // ---- the button role -------------------------------------------------

    @Test
    fun heldDetailTile_isDescribedButNotAButton() {
        val tile = photoTile()
        composeRule.setContent { PinotRougeTheme { HeldDetail(tile) } }

        val node = composeRule.onNodeWithTag(mmsTileTag(tile))
        node.assertIsDisplayed()
        node.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button).not(),
        )
        node.assert(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).not(),
        )
    }

    /** The a11y label must survive the fix — a held photo is still described. */
    @Test
    fun heldDetailTile_keepsItsContentDescription() {
        val tile = photoTile()
        composeRule.setContent { PinotRougeTheme { HeldDetail(tile) } }

        composeRule.onNodeWithContentDescription("Photo from $SENDER").assertIsDisplayed()
    }

    /** The regression guard: the Filtered *list* tile is unchanged. */
    @Test
    fun filteredListTile_staysAButtonAndStillToggles() {
        val tile = photoTile()
        val opened = mutableListOf<String>()
        composeRule.setContent {
            PinotRougeTheme {
                FilteredScreen(
                    state = FilteredUiState(
                        groups = listOf(
                            FilteredGroup(
                                ruleId = RULE_ID,
                                label = RULE_NAME,
                                items = listOf(
                                    HeldMessageUi(
                                        id = HELD_ID,
                                        sender = SENDER,
                                        timeLabel = "10:41",
                                        preview = "(Photo)",
                                        reason = "Filter: $RULE_NAME",
                                        body = "",
                                        ruleId = RULE_ID,
                                        ruleName = RULE_NAME,
                                        tiles = listOf(tile),
                                    ),
                                ),
                            ),
                        ),
                        heldCount = 1,
                    ),
                    onOpenHeldMessage = { opened += it },
                    onOpenFilters = {},
                    onRequestDeleteAll = {},
                    onConfirmDeleteAll = {},
                    onDismissDeleteAll = {},
                    onDismissToast = {},
                )
            }
        }

        val node = composeRule.onNodeWithTag(mmsTileTag(tile))
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        node.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(HELD_ID), opened)
    }

    /** A tile that was given a handler is still a control — the default path. */
    @Test
    fun tileWithAHandler_isStillAButtonAndClicks() {
        val tile = photoTile()
        var clicks = 0
        composeRule.setContent {
            PinotRougeTheme {
                MmsPictureTile(
                    tile = tile,
                    contentDescription = "Photo from $SENDER",
                    selected = false,
                    onClick = { clicks++ },
                )
            }
        }

        val node = composeRule.onNodeWithTag(mmsTileTag(tile))
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        node.performClick()
        composeRule.waitForIdle()
        assertEquals(1, clicks)
    }

    // ---- the unknown sender ----------------------------------------------

    @Test
    fun photoDescriptions_coverNameNumberAndNeither() {
        val described = describe(
            null to null,        // no contact, no address row at all
            "  " to "",          // an address row that resolves to blanks
            null to "+15555550100",  // #218's number fallback, unmodified
            "Dana" to "+15555550100",
        )

        assertEquals("Photo from an unknown sender", described[0])
        assertEquals("Photo from an unknown sender", described[1])
        assertEquals("Photo from +15555550100", described[2])
        assertEquals("Photo from Dana", described[3])
    }

    /** The viewer resolves the same three cases from the same rule. */
    @Test
    fun viewer_noNameAndNoNumber_announcesUnknownSender() {
        val unknown = context.getString(
            com.pinotrouge.messaging.R.string.mms_a11y_photo_unknown_sender,
        )
        assertEquals("Photo from an unknown sender", unknown)

        val description = inboundPhotoDescription(
            from = null,
            unknownSender = { unknown },
            photoFrom = { token -> "Photo from $token" },
        )
        composeRule.setContent {
            PinotRougeTheme {
                PhotoViewerScreen(
                    state = PhotoViewerUiState(contentDescription = description),
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(unknown).assertIsDisplayed()
    }

    /** The empty description this brief exists to remove. */
    @Test
    fun noPhotoDescriptionIsEverBlank() {
        assertTrue(describe(null to null).all { it.isNotBlank() })
    }

    // ---- fixture ---------------------------------------------------------

    @Composable
    private fun HeldDetail(tile: MmsTile) {
        HeldMessageScreen(
            state = HeldMessageUiState(
                message = HeldMessageDetailUi(
                    id = HELD_ID,
                    sender = SENDER,
                    body = "",
                    timeLabel = "10:41",
                    ruleName = RULE_NAME,
                    displayReason = null,
                    tiles = listOf(tile),
                ),
                loading = false,
            ),
            onMoveToInbox = {},
            onBlockSender = {},
            onDelete = {},
            onDismissToast = {},
        )
    }

    /** Resolves every case in one `setContent` — the rule allows only one. */
    private fun describe(vararg cases: Pair<String?, String?>): List<String> {
        val tile = photoTile()
        val resolved = MutableList(cases.size) { "" }
        composeRule.setContent {
            cases.forEachIndexed { index, (senderLabel, senderAddress) ->
                resolved[index] = mmsTileContentDescription(
                    tile = tile,
                    isOutgoing = false,
                    senderLabel = senderLabel,
                    senderAddress = senderAddress,
                )
            }
        }
        composeRule.waitForIdle()
        return resolved
    }

    private fun photoTile() = MmsTile(
        messageId = 4_242L,
        seq = 0,
        kind = MmsTileKind.Photo,
        uri = jpegUri(),
    )

    private fun jpegUri(): Uri {
        val dir = File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "mms-tile-semantics.jpg")
        files += file
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        file.outputStream().use { it.write(out.toByteArray()) }
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            PlatformMmsTransport.authority(context.packageName),
            file,
        )
    }

    private companion object {
        const val HELD_ID = "held-tile-semantics"
        const val SENDER = "+15555550100"
        const val RULE_ID = "r-photo"
        const val RULE_NAME = "Loan and crypto offers"
    }
}
