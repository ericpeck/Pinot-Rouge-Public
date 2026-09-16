package com.pinotrouge.messaging.data.telephony

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri

/**
 * Isolates [android.telephony.SmsManager] so 17.2 can complete a download
 * against a fixture PDU without the radio.
 *
 * Does not decode, write parts, or hold bytes. Call sites still own those.
 *
 * [sourceIntent] carries [android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX]
 * when the platform supplied one (WAP_PUSH / send). Missing extra → default
 * [android.telephony.SmsManager]. No SIM picker.
 */
interface MmsTransport {
    fun send(
        pduUri: Uri,
        sentIntent: PendingIntent,
        sourceIntent: Intent? = null,
    )

    fun download(
        locationUrl: String,
        targetUri: Uri,
        downloadedIntent: PendingIntent,
        sourceIntent: Intent? = null,
    )

    /**
     * Carrier MMS size cap for the subscription we are about to send on.
     * Null [subscriptionId] is the platform default. Dual-SIM devices must
     * not size an image against SIM 1 and send it on SIM 2.
     */
    fun carrierMaxMessageBytes(subscriptionId: Int? = null): Int
}
