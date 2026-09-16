package com.pinotrouge.messaging.sms

import android.app.PendingIntent
import android.content.Context
import android.telephony.SmsManager
import java.util.ArrayList

/**
 * Test seam — production talks to [SmsManager]; tests record calls.
 *
 * Same idea as [com.pinotrouge.messaging.data.telephony.MmsRadio]: the real
 * radio is never exercised by a recording fake.
 */
interface SmsRadio {
    fun divideMessage(text: String): ArrayList<String>

    fun sendTextMessage(
        destinationAddress: String,
        scAddress: String?,
        text: String,
        sentIntent: PendingIntent?,
        deliveryIntent: PendingIntent?,
    )

    fun sendMultipartTextMessage(
        destinationAddress: String,
        scAddress: String?,
        parts: ArrayList<String>,
        sentIntents: ArrayList<PendingIntent>?,
        deliveryIntents: ArrayList<PendingIntent>?,
    )
}

internal class PlatformSmsRadio(
    private val context: Context,
) : SmsRadio {

    override fun divideMessage(text: String): ArrayList<String> = manager().divideMessage(text)

    override fun sendTextMessage(
        destinationAddress: String,
        scAddress: String?,
        text: String,
        sentIntent: PendingIntent?,
        deliveryIntent: PendingIntent?,
    ) {
        manager().sendTextMessage(
            destinationAddress,
            scAddress,
            text,
            sentIntent,
            deliveryIntent,
        )
    }

    override fun sendMultipartTextMessage(
        destinationAddress: String,
        scAddress: String?,
        parts: ArrayList<String>,
        sentIntents: ArrayList<PendingIntent>?,
        deliveryIntents: ArrayList<PendingIntent>?,
    ) {
        manager().sendMultipartTextMessage(
            destinationAddress,
            scAddress,
            parts,
            sentIntents,
            deliveryIntents,
        )
    }

    private fun manager(): SmsManager =
        context.getSystemService(SmsManager::class.java)
            ?: @Suppress("DEPRECATION") SmsManager.getDefault()
}
