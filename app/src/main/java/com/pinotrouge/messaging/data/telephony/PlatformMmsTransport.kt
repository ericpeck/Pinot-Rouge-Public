package com.pinotrouge.messaging.data.telephony

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MmsTransport] over the platform [SmsManager]. Cache FileProvider URIs for
 * send/download only — never `held_media/`.
 */
@Singleton
class PlatformMmsTransport : MmsTransport {

    private val radio: MmsRadio

    @Inject
    constructor(@ApplicationContext context: Context) {
        this.radio = PlatformMmsRadio(context)
    }

    @androidx.annotation.VisibleForTesting
    constructor(radio: MmsRadio) {
        this.radio = radio
    }

    override fun send(
        pduUri: Uri,
        sentIntent: PendingIntent,
        sourceIntent: Intent?,
    ) {
        radio.send(subscriptionIdFrom(sourceIntent), pduUri, sentIntent)
    }

    override fun download(
        locationUrl: String,
        targetUri: Uri,
        downloadedIntent: PendingIntent,
        sourceIntent: Intent?,
    ) {
        radio.download(
            subscriptionIdFrom(sourceIntent),
            locationUrl,
            targetUri,
            downloadedIntent,
        )
    }

    override fun carrierMaxMessageBytes(subscriptionId: Int?): Int =
        radio.carrierMaxMessageBytes(subscriptionId)

    companion object {
        const val SEND_CACHE_DIR = "mms_send"
        const val DOWNLOAD_CACHE_DIR = "mms_download"
        const val AUTHORITY_SUFFIX = ".fileprovider"

        /** Conservative fallback when the carrier bundle omits a size. */
        const val DEFAULT_MAX_MESSAGE_BYTES = 300 * 1024

        /**
         * Packages that may read or write a send/download PDU.
         * `SmsManager.sendMultimediaMessage` / `downloadMultimediaMessage` are
         * served by a different UID; grant both rather than guessing which
         * one this image uses. A grant to a missing package is ignored.
         */
        val PDU_READ_GRANTEES = listOf(
            "com.android.phone",
            "com.android.mms.service",
        )

        const val PDU_READ_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val PDU_DOWNLOAD_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

        fun contentUriFor(context: Context, file: File): Uri =
            FileProvider.getUriForFile(context, authority(context.packageName), file)

        fun cachePdu(
            context: Context,
            dirName: String,
            fileName: String,
            bytes: ByteArray? = null,
        ): MmsCachePdu {
            val dir = File(context.cacheDir, dirName).apply { mkdirs() }
            val file = File(dir, fileName)
            if (bytes != null) {
                file.writeBytes(bytes)
            } else {
                file.createNewFile()
            }
            return MmsCachePdu(file, contentUriFor(context, file))
        }

        fun grantPduAccess(context: Context, uri: Uri, flags: Int) {
            for (pkg in PDU_READ_GRANTEES) {
                runCatching { context.grantUriPermission(pkg, uri, flags) }
            }
        }

        fun revokePduAccess(context: Context, uri: Uri, flags: Int) {
            runCatching { context.revokeUriPermission(uri, flags) }
            for (pkg in PDU_READ_GRANTEES) {
                runCatching { context.revokeUriPermission(pkg, uri, flags) }
            }
        }
    }
}

/** App-private cache file exposed to the MMS service through FileProvider. */
data class MmsCachePdu(
    val file: File,
    val uri: Uri,
)

internal fun subscriptionIdFrom(intent: Intent?): Int? {
    if (intent == null) return null
    if (!intent.hasExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)) {
        return subscriptionIdFromExtra(hasExtra = false, value = 0)
    }
    return subscriptionIdFromExtra(
        hasExtra = true,
        value = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        ),
    )
}

internal fun subscriptionIdFromExtra(hasExtra: Boolean, value: Int): Int? {
    if (!hasExtra) return null
    return value.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
}

/** Test seam — production talks to [SmsManager]; tests record calls. */
interface MmsRadio {
    fun send(subscriptionId: Int?, pduUri: Uri, sentIntent: PendingIntent)
    fun download(
        subscriptionId: Int?,
        locationUrl: String,
        targetUri: Uri,
        downloadedIntent: PendingIntent,
    )
    fun carrierMaxMessageBytes(subscriptionId: Int?): Int
}

private class PlatformMmsRadio(
    private val context: Context,
) : MmsRadio {

    override fun send(subscriptionId: Int?, pduUri: Uri, sentIntent: PendingIntent) {
        manager(subscriptionId).sendMultimediaMessage(
            context,
            pduUri,
            /* locationUrl = */ null,
            /* configOverrides = */ null,
            sentIntent,
        )
    }

    override fun download(
        subscriptionId: Int?,
        locationUrl: String,
        targetUri: Uri,
        downloadedIntent: PendingIntent,
    ) {
        manager(subscriptionId).downloadMultimediaMessage(
            context,
            locationUrl,
            targetUri,
            /* configOverrides = */ null,
            downloadedIntent,
        )
    }

    override fun carrierMaxMessageBytes(subscriptionId: Int?): Int {
        val bundle = manager(subscriptionId).carrierConfigValues ?: return PlatformMmsTransport.DEFAULT_MAX_MESSAGE_BYTES
        val raw = bundle.get(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE)
        val parsed = when (raw) {
            is Int -> raw
            is String -> raw.toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0 } ?: PlatformMmsTransport.DEFAULT_MAX_MESSAGE_BYTES
    }

    private fun manager(subscriptionId: Int?): SmsManager {
        val default = context.getSystemService(SmsManager::class.java)
            ?: @Suppress("DEPRECATION") SmsManager.getDefault()
        return if (subscriptionId != null) {
            default.createForSubscriptionId(subscriptionId)
        } else {
            default
        }
    }
}
