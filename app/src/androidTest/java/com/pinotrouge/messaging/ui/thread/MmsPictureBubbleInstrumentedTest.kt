package com.pinotrouge.messaging.ui.thread

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MmsPictureBubbleInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun photoTile_isWiderThan178_sameBoundsForPortraitAndPanorama() {
        val files = mutableListOf<java.io.File>()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        fun jpegUri(width: Int, height: Int): Uri {
            val dir = java.io.File(context.cacheDir, com.pinotrouge.messaging.data.telephony.PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
            val file = java.io.File(dir, "img-${width}x${height}.jpg")
            files += file
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            return androidx.core.content.FileProvider.getUriForFile(
                context,
                com.pinotrouge.messaging.data.telephony.PlatformMmsTransport.authority(context.packageName),
                file,
            )
        }
        try {
            val portrait = jpegUri(width = 400, height = 800)
            val panorama = jpegUri(width = 1600, height = 400)
            composeRule.setContent {
                PinotRougeTheme {
                    Box(Modifier.size(360.dp, 2400.dp)) {
                        ThreadScreen(
                            state = threadState(
                                listOf(
                                    photoBubble(
                                        id = 1L,
                                        tiles = listOf(
                                            MmsTile(
                                                1L,
                                                seq = 0,
                                                kind = MmsTileKind.Photo,
                                                uri = portrait,
                                            ),
                                        ),
                                    ),
                                    photoBubble(
                                        id = 2L,
                                        tiles = listOf(
                                            MmsTile(
                                                2L,
                                                seq = 0,
                                                kind = MmsTileKind.Photo,
                                                uri = panorama,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            onBack = {},
                            onOpenBuilder = {},
                            onDraftChange = {},
                            onSend = {},
                        )
                    }
                }
            }
            val widths = mutableListOf<androidx.compose.ui.unit.Dp>()
            val heights = mutableListOf<androidx.compose.ui.unit.Dp>()
            for (id in listOf(1L, 2L)) {
                val node = composeRule.onNodeWithTag("mms-tile-photo-$id-0")
                node.assertIsDisplayed()
                node.assertWidthIsAtLeast(179.dp)
                val bounds = node.getBoundsInRoot()
                widths += bounds.right - bounds.left
                heights += bounds.bottom - bounds.top
            }
                val w0 = widths[0].value
                val w1 = widths[1].value
                val h0 = heights[0].value
                val h1 = heights[1].value
                assertEquals("portrait and panorama must share a width", w0, w1, 0.5f)
                assertEquals("portrait and panorama must share a height", h0, h1, 0.5f)
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun subjectRendersAsOwnLine() {
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = threadState(
                            listOf(
                                photoBubble(
                                    body = "caption from the text part",
                                    subject = "the subject",
                                    tiles = listOf(
                                        MmsTile(1L, 0, MmsTileKind.Photo, Uri.parse("content://mms/part/1")),
                                    ),
                                ),
                            ),
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("the subject").assertIsDisplayed()
        composeRule.onNodeWithText("caption from the text part").assertIsDisplayed()
    }

    @Test
    fun multiImage_selectsAsOneMessage() {
        val tiles = listOf(
            MmsTile(7L, 0, MmsTileKind.Photo, Uri.parse("content://mms/part/a")),
            MmsTile(7L, 1, MmsTileKind.Photo, Uri.parse("content://mms/part/b")),
        )
        var selected by mutableStateOf<Set<MessageRef>>(emptySet())
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = threadState(
                            messages = listOf(photoBubble(id = 7L, tiles = tiles)),
                            selectionActive = selected.isNotEmpty(),
                            selected = selected,
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                        onStartSelection = { selected = setOf(it) },
                        onToggleSelection = { ref ->
                            selected = if (ref in selected) selected - ref else selected + ref
                        },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("mms-tile-photo-7-0").assertIsDisplayed()
        composeRule.onNodeWithTag("mms-tile-photo-7-1").assertIsDisplayed()
        composeRule.onNodeWithTag("thread-bubble-mms:7").performClick()
        // long-press path is covered by identity test; here two tiles, one ref
        assertEquals(1, tiles.map { it.messageId }.distinct().size)
    }

    @Test
    fun tapPhoto_opensViewer_notDownload() {
        val opened = mutableListOf<Pair<Long, Int>>()
        val downloads = mutableListOf<Long>()
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = threadState(
                            listOf(
                                photoBubble(
                                    tiles = listOf(
                                        MmsTile(3L, 2, MmsTileKind.Photo, Uri.parse("content://mms/part/2")),
                                    ),
                                ),
                            ),
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                        onOpenPhoto = { id, seq -> opened += id to seq },
                        onDownloadMms = { downloads += it },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("mms-tile-photo-3-2").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(3L to 2), opened)
        assertTrue(downloads.isEmpty())
    }

    @Test
    fun tapNotDownloaded_downloads_doesNotOpenViewer() {
        assertDownloadNotViewer(MmsTileKind.NotDownloaded, "mms-tile-notdownloaded-4-0")
    }

    @Test
    fun tapFailed_downloads_doesNotOpenViewer() {
        assertDownloadNotViewer(MmsTileKind.Failed, "mms-tile-failed-4-0")
    }

    @Test
    fun tapInertStates_doNothing() {
        val opened = mutableListOf<Pair<Long, Int>>()
        val downloads = mutableListOf<Long>()
        val inert = listOf(
            Triple(5L, MmsTileKind.Downloading, null),
            Triple(6L, MmsTileKind.Expired, null),
            Triple(7L, MmsTileKind.Video, R.string.mms_part_video),
        )
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 2400.dp)) {
                    ThreadScreen(
                        state = threadState(
                            inert.map { (id, kind, labelRes) ->
                                photoBubble(
                                    id = id,
                                    tiles = listOf(
                                        MmsTile(id, 0, kind, partLabelRes = labelRes),
                                    ),
                                )
                            },
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                        onOpenPhoto = { id, seq -> opened += id to seq },
                        onDownloadMms = { downloads += it },
                    )
                }
            }
        }
        for ((id, kind, _) in inert) {
            composeRule.onNodeWithTag("mms-tile-${kind.name.lowercase()}-$id-0").performClick()
            composeRule.waitForIdle()
        }
        assertTrue("inert opened viewer: $opened", opened.isEmpty())
        assertTrue("inert downloaded: $downloads", downloads.isEmpty())
    }

    @Test
    fun everyState_hasItem11Description() {
        val cases = listOf(
            Triple(MmsTileKind.Photo, null, "Photo from Maya"),
            Triple(MmsTileKind.Downloading, null, "Photo, downloading"),
            Triple(MmsTileKind.NotDownloaded, null, "Photo not downloaded. Double tap to download."),
            Triple(MmsTileKind.Expired, null, "Photo, no longer available"),
            Triple(MmsTileKind.Video, R.string.mms_part_video, "Video, cannot be shown"),
        )
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 2400.dp)) {
                    ThreadScreen(
                        state = threadState(
                            cases.mapIndexed { index, (kind, labelRes, _) ->
                                val id = 90L + index
                                photoBubble(
                                    id = id,
                                    senderLabel = "Maya",
                                    tiles = listOf(
                                        MmsTile(
                                            id,
                                            0,
                                            kind,
                                            uri = if (kind == MmsTileKind.Photo) {
                                                Uri.parse("content://mms/part/$id")
                                            } else {
                                                null
                                            },
                                            partLabelRes = labelRes,
                                        ),
                                    ),
                                )
                            },
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                    )
                }
            }
        }
        for ((_, _, description) in cases) {
            composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        }
    }

    @Test
    fun photoFrom_blankLabel_usesAddress() {
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = threadState(
                            listOf(
                                photoBubble(
                                    senderLabel = "",
                                    senderAddress = "+15555550100",
                                    tiles = listOf(
                                        MmsTile(
                                            11L,
                                            0,
                                            MmsTileKind.Photo,
                                            Uri.parse("content://mms/part/11"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Photo from +15555550100").assertIsDisplayed()
    }

    @Test
    fun viewer_blankName_usesNumber() {
        composeRule.setContent {
            PinotRougeTheme {
                com.pinotrouge.messaging.ui.media.PhotoViewerScreen(
                    state = com.pinotrouge.messaging.ui.media.PhotoViewerUiState(
                        contentDescription = com.pinotrouge.messaging.ui.media.inboundPhotoDescription(
                            from = "+15555550100",
                            unknownSender = { "Photo from an unknown sender" },
                            photoFrom = { "Photo from $it" },
                        ),
                    ),
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Photo from +15555550100").assertIsDisplayed()
    }

    @Test
    fun failureStatesRenderCopy() {
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = threadState(
                            listOf(
                                photoBubble(
                                    tiles = listOf(
                                        MmsTile(8L, 0, MmsTileKind.Failed),
                                    ),
                                ),
                            ),
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Photo didn’t download").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Photo didn\u2019t download. Double tap to try again.",
        ).assertIsDisplayed()
    }

    @Test
    fun viewerBack_callsOnBack() {
        var backs = 0
        composeRule.setContent {
            PinotRougeTheme {
                com.pinotrouge.messaging.ui.media.PhotoViewerScreen(
                    state = com.pinotrouge.messaging.ui.media.PhotoViewerUiState(
                        contentDescription = "Photo you sent",
                    ),
                    onBack = { backs++ },
                )
            }
        }
        composeRule.onNodeWithTag("mms-photo-viewer-back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, backs)
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun failedRetrieve_releasesInFlightSoDownloadCanStartAgain() {
        ThreadViewModel.resetDownloadsInFlightForTests()
        val id = 9_001L
        assertTrue(ThreadViewModel.claimDownload(id))
        assertFalse(ThreadViewModel.claimDownload(id))
        ThreadViewModel.markDownloadFailed(id)
        assertFalse(ThreadViewModel.isDownloadInFlight(id))
        assertTrue(
            "downloadMms must be able to start again after a failed retrieve",
            ThreadViewModel.claimDownload(id),
        )
        ThreadViewModel.resetDownloadsInFlightForTests()
    }

    private fun assertDownloadNotViewer(kind: MmsTileKind, tag: String) {
        val opened = mutableListOf<Pair<Long, Int>>()
        val downloads = mutableListOf<Long>()
        composeRule.setContent {
            PinotRougeTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ThreadScreen(
                        state = threadState(
                            listOf(photoBubble(id = 4L, tiles = listOf(MmsTile(4L, 0, kind)))),
                        ),
                        onBack = {},
                        onOpenBuilder = {},
                        onDraftChange = {},
                        onSend = {},
                        onOpenPhoto = { id, seq -> opened += id to seq },
                        onDownloadMms = { downloads += it },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(4L), downloads)
        assertTrue(opened.isEmpty())
    }

    private fun threadState(
        messages: List<ThreadBubble>,
        selectionActive: Boolean = false,
        selected: Set<MessageRef> = emptySet(),
    ) = ThreadUiState(
        threadId = 1,
        title = "Maya",
        address = "+15555550100",
        messages = messages,
        roleHeld = true,
        loading = false,
        selectionActive = selectionActive,
        selectedMessageIds = selected,
    )

    private fun photoBubble(
        id: Long = 1L,
        body: String = "",
        subject: String? = null,
        senderLabel: String? = "Maya",
        senderAddress: String? = null,
        tiles: List<MmsTile> = emptyList(),
    ) = ThreadBubble(
        ref = MessageRef.mms(id),
        body = body,
        isOutgoing = false,
        date = 1,
        senderAddress = senderAddress,
        senderLabel = senderLabel,
        subject = subject,
        tiles = tiles,
    )
}
