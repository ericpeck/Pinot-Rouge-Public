package com.pinotrouge.messaging.sms

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
 */
@AndroidEntryPoint
class SendToActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = parseSendTo(intent)
        if (parsed != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECIPIENT, parsed.recipient)
                .putString(KEY_BODY, parsed.body)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
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
            val data: Uri = intent.data ?: return null
            val bodyFromQuery = data.getQueryParameter("body")
            val bodyFromExtra = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra("sms_body")
            return SendToParser.parse(
                scheme = data.scheme,
                schemeSpecificPart = data.schemeSpecificPart,
                queryBody = bodyFromQuery,
                extraBody = bodyFromExtra,
            )
        }
    }
}
