package com.pinotrouge.messaging.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Completes [android.telephony.SmsManager.sendMultimediaMessage].
 * [Activity.RESULT_OK] is not a send verdict — [SmsManager.EXTRA_MMS_DATA]
 * carries the M-Send.conf, and [com.pinotrouge.messaging.data.telephony.MmsSendComposer.parseSendConf]
 * reads `X-Mms-Response-Status`. The cached PDU is deleted here, never in a
 * `finally` around the platform call.
 */
class MmsSendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val pduPath = intent.getStringExtra(EXTRA_PDU_PATH)
        val messageUri = intent.getStringExtra(EXTRA_MESSAGE_URI)?.let(Uri::parse)
        val resultCode = resultCode
        val sendConf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        scope.launch(Dispatchers.IO) {
            try {
                val entry = runCatching {
                    EntryPointAccessors.fromApplication(appContext, SmsEntryPoint::class.java)
                }.getOrNull()
                if (entry == null) {
                    Log.e(TAG, "MmsSendReceiver: entry point unavailable")
                    pduPath?.let { runCatching { File(it).delete() } }
                    return@launch
                }
                entry.smsSender().onSendComplete(
                    resultCode = resultCode,
                    sendConfPdu = sendConf,
                    pduPath = pduPath,
                    messageUri = messageUri,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "MmsSendReceiver failed", t)
                pduPath?.let { runCatching { File(it).delete() } }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MmsSendReceiver"
        const val ACTION_SENT = "com.pinotrouge.messaging.action.MMS_SENT"
        const val EXTRA_PDU_PATH = "pdu_path"
        const val EXTRA_MESSAGE_URI = "message_uri"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
