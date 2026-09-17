package com.pinotrouge.messaging.notify

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OtpClipboardInstrumentedTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.clearPrimaryClip()
        }
    }

    @Test
    fun clipFor_setsSensitiveFlagAndRandomOwnershipToken() {
        val code = "882041"
        val token = OtpClipboardPolicy.newOwnershipToken()
        val clip = OtpClipboard.clipFor(code, token)
        assertEquals(code, clip.getItemAt(0).text.toString())
        val extras = clip.description.extras
        assertNotNull(extras)
        assertEquals(token, extras!!.getString(OtpClipboardPolicy.EXTRA_OWNERSHIP))
        assertTrue(extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertNotEquals(code, token)
        assertTrue(OtpClipboardPolicy.shouldClear(token, token))
    }

    @Test
    fun laterPlainCopyIsNotCleared() {
        val token = OtpClipboardPolicy.newOwnershipToken()
        val ours = OtpClipboard.clipFor("882041", token)
        val later = ClipData.newPlainText("note", "grocery list")
        assertTrue(
            OtpClipboardPolicy.shouldClear(
                ours.description.extras?.getString(OtpClipboardPolicy.EXTRA_OWNERSHIP),
                token,
            ),
        )
        assertFalse(
            OtpClipboardPolicy.shouldClear(
                later.description.extras?.getString(OtpClipboardPolicy.EXTRA_OWNERSHIP),
                token,
            ),
        )
    }

    @Test
    fun secondCopyReplacesOwnershipToken() {
        val first = OtpClipboardPolicy.newOwnershipToken()
        val second = OtpClipboardPolicy.newOwnershipToken()
        assertNotEquals(first, second)
        val clip = OtpClipboard.clipFor("222222", second)
        assertEquals(second, clip.description.extras?.getString(OtpClipboardPolicy.EXTRA_OWNERSHIP))
        assertFalse(OtpClipboardPolicy.shouldClear(second, first))
        assertTrue(OtpClipboardPolicy.shouldClear(second, second))
    }

    @Test
    fun resumedActivityCanRoundTripOwnershipExtras() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                val token = OtpClipboardPolicy.newOwnershipToken()
                clipboard.setPrimaryClip(OtpClipboard.clipFor("882041", token))
                val extras = clipboard.primaryClip?.description?.extras
                assertNotNull(
                    "clipboard extras missing even with a resumed activity; " +
                        "background clipboard reads are best-effort",
                    extras,
                )
                assertEquals(token, extras?.getString(OtpClipboardPolicy.EXTRA_OWNERSHIP))
            }
        }
    }
}
