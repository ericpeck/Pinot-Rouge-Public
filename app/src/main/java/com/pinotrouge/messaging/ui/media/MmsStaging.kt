package com.pinotrouge.messaging.ui.media

import android.content.Context
import android.net.Uri
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import java.io.File

/**
 * Copy a picker/camera image into the send cache immediately. The picker
 * grant does not outlive the activity result; camera capture writes into
 * a FileProvider URI under the same directory.
 *
 * Never [held_media/] — that path is inbound quarantine.
 */
object MmsStaging {

    fun copyToOwnedCache(context: Context, source: Uri): File? {
        val dir = sendDir(context)
        val dest = File(dir, "stage-${System.nanoTime()}")
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            if (dest.length() <= 0L) {
                dest.delete()
                null
            } else {
                dest
            }
        }.getOrNull()
    }

    fun createCameraTarget(context: Context): Pair<File, Uri> {
        val dir = sendDir(context)
        val file = File(dir, "capture-${System.nanoTime()}.jpg")
        file.createNewFile()
        val uri = PlatformMmsTransport.contentUriFor(context, file)
        return file to uri
    }

    fun fileIfExists(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun sendDir(context: Context): File =
        File(context.cacheDir, PlatformMmsTransport.SEND_CACHE_DIR).apply { mkdirs() }
}
