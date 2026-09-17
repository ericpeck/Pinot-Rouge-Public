package com.pinotrouge.messaging.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.pinotrouge.messaging.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Handles `ACTION_SENDTO` for sms:/smsto:/mms:/mmsto:.
 *
 * Until feat/ui-thread-compose lands, we parse recipient + body into prefs and
 * open MainActivity so the user is not left on a blank screen.
 *
 * Do not call [Uri.getQueryParameter] here: common `sms:number?body=` links
 * are opaque and that API throws `UnsupportedOperationException`.
 */
@AndroidEntryPoint
class SendToActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = parseSendTo(intent)
        if (parsed != null) {
            storePending(this, parsed)
            // Do not log the recipient or body — they are message content.
            Log.i(TAG, "Pending compose stored")
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
        finish()
    }

    companion object {
        private const val TAG = "SendToActivity"
        const val PREFS = "pending_compose"
        const val KEY_RECIPIENT = "recipient"
        const val KEY_BODY = "body"
        const val KEY_AT = "at"

        fun parseSendTo(intent: Intent?): SendToParser.Parsed? {
            if (intent == null) return null
            if (!isAllowedAction(intent.action)) return null
            return try {
                val data: Uri = intent.data ?: return null
                val extraBody = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra("sms_body")
                SendToParser.parse(
                    scheme = data.scheme,
                    schemeSpecificPart = data.schemeSpecificPart,
                    extraBody = extraBody,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "SendTo parse failed: ${t.javaClass.simpleName}")
                null
            }
        }

        fun storePending(context: Context, parsed: SendToParser.Parsed) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RECIPIENT, parsed.recipient)
                .putString(KEY_BODY, parsed.body)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        }

        internal fun isAllowedAction(action: String?): Boolean =
            action == null ||
                action == Intent.ACTION_SENDTO ||
                action == Intent.ACTION_VIEW
    }
}
