package com.pinotrouge.messaging.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android URI / Intent boundary for SENDTO. Opens drafts only — never sends.
 *
 * `sms:number?body=` is opaque. [Uri.getQueryParameter] throws
 * UnsupportedOperationException on that shape (the crash on API 33 and 36).
 */
@RunWith(AndroidJUnit4::class)
class SendToUriInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun opaqueSmsUri_getQueryParameterThrows_parseSendToDoesNot() {
        val uri = Uri.parse("sms:+15555550100?body=Hello%20there")
        assertFalse(uri.isHierarchical)
        val queryCrash = runCatching { uri.getQueryParameter("body") }
        assertTrue(queryCrash.exceptionOrNull() is UnsupportedOperationException)

        val intent = Intent(Intent.ACTION_SENDTO, uri)
        val parsed = SendToActivity.parseSendTo(intent)
        assertNotNull(parsed)
        assertEquals("+15555550100", parsed!!.recipient)
        assertEquals("Hello there", parsed.body)
    }

    @Test
    fun hierarchicalSmsUri_parsesRecipientAndBody() {
        val uri = Uri.parse("sms://5550100?body=Hi")
        val intent = Intent(Intent.ACTION_SENDTO, uri)
        val parsed = SendToActivity.parseSendTo(intent)
        assertEquals("5550100", parsed?.recipient)
        assertEquals("Hi", parsed?.body)
    }

    @Test
    fun extraText_fillsBodyWhenUriHasNone() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:5550100")).apply {
            putExtra(Intent.EXTRA_TEXT, "from extra")
        }
        val parsed = SendToActivity.parseSendTo(intent)
        assertEquals("5550100", parsed?.recipient)
        assertEquals("from extra", parsed?.body)
    }

    @Test
    fun viewAction_isAccepted_sendAction_isRejected() {
        val sms = Uri.parse("sms:5550100?body=draft")
        assertNotNull(
            SendToActivity.parseSendTo(Intent(Intent.ACTION_VIEW, sms)),
        )
        assertNull(
            SendToActivity.parseSendTo(Intent(Intent.ACTION_SEND).setData(sms)),
        )
    }

    @Test
    fun httpsScheme_isRejected() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("https://example.com"))
        assertNull(SendToActivity.parseSendTo(intent))
    }

    @Test
    fun malformedUri_doesNotThrow() {
        val intent = Intent(Intent.ACTION_SENDTO)
        intent.data = Uri.parse("sms:")
        assertNull(SendToActivity.parseSendTo(intent))
    }

    @Test
    fun storePending_writesDraftPrefs_andDoesNotSend() {
        val parsed = SendToParser.Parsed(recipient = "5550100", body = "draft only")
        SendToActivity.storePending(context, parsed)
        val prefs = context.getSharedPreferences(SendToActivity.PREFS, Context.MODE_PRIVATE)
        assertEquals("5550100", prefs.getString(SendToActivity.KEY_RECIPIENT, null))
        assertEquals("draft only", prefs.getString(SendToActivity.KEY_BODY, null))
        prefs.edit().clear().commit()
    }
}
