package com.pinotrouge.messaging.ui.filtered

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.repo.QuarantineRepository
import com.pinotrouge.messaging.data.room.TransportKind
import com.pinotrouge.messaging.data.telephony.MmsPart
import com.pinotrouge.messaging.data.telephony.MmsRepository
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import com.pinotrouge.messaging.data.telephony.MessageRef
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.media.mmsTileTag
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.thread.ThreadBubble
import com.pinotrouge.messaging.ui.thread.ThreadScreen
import com.pinotrouge.messaging.ui.thread.ThreadUiState
import com.pinotrouge.messaging.util.PINOT_PACKAGE
import com.pinotrouge.messaging.util.awaitRoleHeld
import com.pinotrouge.messaging.util.createInMemoryDb
import com.pinotrouge.messaging.util.grantSmsRoleTo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 17.4 — a held picture is visible in Filtered and on the held-message
 * screen, and Move to inbox restores the MMS so the thread tile can show it.
 */
@RunWith(AndroidJUnit4::class)
class HeldMediaUiInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var sms: SmsRepository
    private lateinit var mms: MmsRepository
    private lateinit var db: com.pinotrouge.messaging.data.room.PinotDatabase
    private lateinit var quarantine: QuarantineRepository
    private val inserted = mutableListOf<Uri>()
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sms = SmsRepository(context)
        mms = MmsRepository(context, sms)
        db = createInMemoryDb(context)
        quarantine = QuarantineRepository(
            heldMessageDao = db.heldMessageDao(),
            blockedSenderDao = db.blockedSenderDao(),
            ruleDao = db.ruleDao(),
            smsRepository = sms,
            heldMediaDao = db.heldMediaDao(),
            mmsRepository = mms,
            context = context,
        )
    }

    @After
    fun tearDown() {
        for (uri in inserted.asReversed()) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        files.forEach { it.delete() }
        db.close()
        File(context.filesDir, QuarantineRepository.HELD_MEDIA_DIR).deleteRecursively()
    }

    @Test
    fun filteredRow_rendersHeldPhotoTile() {
        val tile = MmsTile(
            messageId = heldTileMessageId(HELD_ID),
            seq = 0,
            kind = MmsTileKind.Photo,
            uri = jpegUri(),
        )
        composeRule.setContent {
            PinotRougeTheme {
                FilteredScreen(
                    state = FilteredUiState(
                        groups = listOf(
                            FilteredGroup(
                                ruleId = "r-photo",
                                label = "Loan and crypto offers",
                                items = listOf(
                                    HeldMessageUi(
                                        id = HELD_ID,
                                        sender = SENDER,
                                        timeLabel = "10:41",
                                        preview = "(Photo)",
                                        reason = "Filter: Loan and crypto offers",
                                        body = "",
                                        ruleId = "r-photo",
                                        ruleName = "Loan and crypto offers",
                                        tiles = listOf(tile),
                                    ),
                                ),
                            ),
                        ),
                        heldCount = 1,
                    ),
                    onOpenHeldMessage = {},
                    onRequestDeleteAll = {},
                    onConfirmDeleteAll = {},
                    onDismissDeleteAll = {},
                    onDismissToast = {},
                )
            }
        }
        composeRule.onNodeWithTag(mmsTileTag(tile)).assertIsDisplayed()
        composeRule.onNodeWithText(SENDER).assertIsDisplayed()
    }

    @Test
    fun heldDetail_rendersPictureReasonAndRelease() {
        val tile = MmsTile(
            messageId = heldTileMessageId(HELD_ID),
            seq = 0,
            kind = MmsTileKind.Photo,
            uri = jpegUri(),
        )
        composeRule.setContent {
            PinotRougeTheme {
                HeldMessageScreen(
                    state = HeldMessageUiState(
                        message = HeldMessageDetailUi(
                            id = HELD_ID,
                            sender = SENDER,
                            body = "",
                            timeLabel = "10:41",
                            ruleName = "Loan and crypto offers",
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
        }
        composeRule.onNodeWithTag(mmsTileTag(tile)).assertIsDisplayed()
        composeRule.onNodeWithText("Caught by \"Loan and crypto offers\"").assertIsDisplayed()
        composeRule.onNodeWithText("Move to inbox").assertIsDisplayed()
    }

    @Test
    fun hold_releaseFromHeldScreen_threadShowsPicture() {
        grantSmsRoleTo(PINOT_PACKAGE)
        awaitRoleHeld(sms, held = true)
        assumeTrue("ROLE_SMS not held — restore needs the provider", sms.isDefaultSmsApp())

        val jpeg = jpegBytes()
        runBlocking {
            quarantine.holdOnArrival(
                sender = SENDER,
                body = "",
                receivedAtMillis = 1_700_000_000_000L,
                ruleId = "r-photo",
                reason = "Filter: Loan and crypto offers",
                deleteAfterDays = 30,
                id = HELD_ID,
                parts = listOf(MmsPart(contentType = "image/jpeg", bytes = jpeg)),
                transportKind = TransportKind.MMS,
            )
        }
        val refs = runBlocking { quarantine.heldMedia(HELD_ID) }
        val tiles = refs.mapIndexedNotNull { seq, ref -> ref.toMmsTile(HELD_ID, seq) }
        assertTrue("held media must become tiles", tiles.isNotEmpty())

        val result = AtomicReference<SmsRepository.WriteResult?>(null)
        val latch = CountDownLatch(1)
        val showThread = mutableStateOf(false)
        val threadTileState = mutableStateOf<MmsTile?>(null)
        composeRule.setContent {
            PinotRougeTheme {
                if (showThread.value) {
                    val threadTile = threadTileState.value!!
                    Box(Modifier.size(360.dp, 720.dp)) {
                        ThreadScreen(
                            state = ThreadUiState(
                                threadId = 1,
                                title = SENDER,
                                address = SENDER,
                                messages = listOf(
                                    ThreadBubble(
                                        ref = MessageRef.mms(threadTile.messageId),
                                        body = "",
                                        isOutgoing = false,
                                        date = 1L,
                                        tiles = listOf(threadTile),
                                    ),
                                ),
                                roleHeld = true,
                                loading = false,
                            ),
                            onBack = {},
                            onOpenBuilder = {},
                            onDraftChange = {},
                            onSend = {},
                        )
                    }
                } else {
                    HeldMessageScreen(
                        state = HeldMessageUiState(
                            message = HeldMessageDetailUi(
                                id = HELD_ID,
                                sender = SENDER,
                                body = "",
                                timeLabel = "10:41",
                                ruleName = "Loan and crypto offers",
                                displayReason = null,
                                tiles = tiles,
                            ),
                            loading = false,
                        ),
                        onMoveToInbox = {
                            Thread {
                                result.set(runBlocking { quarantine.moveToInbox(HELD_ID) })
                                latch.countDown()
                            }.start()
                        },
                        onBlockSender = {},
                        onDelete = {},
                        onDismissToast = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag(mmsTileTag(tiles.first())).assertIsDisplayed()
        composeRule.onNodeWithText("Move to inbox").performClick()
        assertTrue("Move to inbox did not finish", latch.await(15, TimeUnit.SECONDS))
        val write = result.get()
        assertTrue("restore failed: $write", write is SmsRepository.WriteResult.Success)
        val uri = (write as SmsRepository.WriteResult.Success).uri!!
        inserted += uri
        val mmsId = ContentUris.parseId(uri)
        val parts = runBlocking { mms.getParts(mmsId) }
        val image = parts.single { it.contentType.startsWith("image/") }
        val read = context.contentResolver.openInputStream(image.uri)!!.use { it.readBytes() }
        assertArrayEquals(jpeg, read)

        val threadTile = MmsTile(
            messageId = mmsId,
            seq = image.seq,
            kind = MmsTileKind.Photo,
            uri = image.uri,
        )
        composeRule.runOnIdle {
            threadTileState.value = threadTile
            showThread.value = true
        }
        composeRule.onNodeWithTag(mmsTileTag(threadTile)).assertIsDisplayed()
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun jpegUri(): Uri {
        val dir = File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "held-media-ui.jpg")
        files += file
        file.outputStream().use { it.write(jpegBytes()) }
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            PlatformMmsTransport.authority(context.packageName),
            file,
        )
    }

    private companion object {
        const val HELD_ID = "held-photo-ui-1"
        const val SENDER = "15555550499"
    }
}
