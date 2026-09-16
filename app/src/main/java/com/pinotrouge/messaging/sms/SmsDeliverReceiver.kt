package com.pinotrouge.messaging.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Entry point for every incoming SMS.
 *
 * When we hold ROLE_SMS the system does **not** write the message to Telephony.
 * If this receiver drops a message, it is gone. On any failure we fall back to
 * unfiltered inbox delivery.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val pending = goAsync()
        val appContext = context.applicationContext
        val entry = EntryPointAccessors.fromApplication(appContext, SmsEntryPoint::class.java)
        val pipeline = entry.incomingMessagePipeline()

        // Capture PDUs before the intent is recycled after onReceive returns.
        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse SMS_DELIVER PDUs", t)
            pending.finish()
            return
        }

        if (parts.isNullOrEmpty()) {
            pending.finish()
            return
        }

        // Copy fields we need — SmsMessage is fine after onReceive if we already
        // hold the array from getMessagesFromIntent.
        scope.launch(Dispatchers.IO) {
            try {
                try {
                    pipeline.handleParts(parts)
                } catch (t: Throwable) {
                    Log.e(TAG, "Pipeline failed; delivering unfiltered", t)
                    val coalesced = IncomingMessagePipeline.coalesce(parts)
                    if (coalesced != null) {
                        runCatching { pipeline.deliverUnfiltered(coalesced) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsDeliverReceiver"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
