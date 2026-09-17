package com.pinotrouge.messaging.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Reply with a message" when the user declines a call.
 * Recipient from `tel:` data; body from [Intent.EXTRA_TEXT].
 */
@AndroidEntryPoint
class RespondViaMessageService : Service() {

    @Inject lateinit var smsSender: SmsSender

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val parsed = SendToParser.parse(
            scheme = intent?.data?.scheme,
            schemeSpecificPart = intent?.data?.schemeSpecificPart,
            extraBody = intent?.getStringExtra(Intent.EXTRA_TEXT),
        )
        val recipient = parsed?.recipient.orEmpty()
        val body = parsed?.body.orEmpty()

        if (recipient.isEmpty() || body.isEmpty()) {
            Log.w(TAG, "Missing recipient or body; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                smsSender.send(recipient, body)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to send quick reply", t)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "RespondViaMessage"
    }
}
