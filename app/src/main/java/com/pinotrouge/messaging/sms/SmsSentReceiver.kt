package com.pinotrouge.messaging.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Completes [android.telephony.SmsManager.sendTextMessage] /
 * [android.telephony.SmsManager.sendMultipartTextMessage] sent-result
 * callbacks. Not a delivery receipt.
 */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val messageUri = intent.getStringExtra(EXTRA_MESSAGE_URI)?.let(Uri::parse)
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)
        val resultCode = resultCode
        scope.launch(Dispatchers.IO) {
            try {
                val entry = runCatching {
                    EntryPointAccessors.fromApplication(appContext, SmsEntryPoint::class.java)
                }.getOrNull()
                if (entry == null) {
                    Log.e(TAG, "SmsSentReceiver: entry point unavailable")
                    return@launch
                }
                entry.smsSender().onSmsPartResult(
                    messageUri = messageUri,
                    partIndex = partIndex,
                    partCount = partCount,
                    resultCode = resultCode,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "SmsSentReceiver failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsSentReceiver"
        const val ACTION_SENT = "com.pinotrouge.messaging.action.SMS_SENT"
        const val EXTRA_MESSAGE_URI = "message_uri"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
