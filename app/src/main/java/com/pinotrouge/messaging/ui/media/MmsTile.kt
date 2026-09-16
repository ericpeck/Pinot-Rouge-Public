package com.pinotrouge.messaging.ui.media

import android.net.Uri

enum class MmsTileKind {
    Photo,
    Downloading,
    NotDownloaded,
    Failed,
    Expired,
    Video,
    Audio,
    Contact,
    Unsupported,
}

enum class MmsDownloadUi {
    Idle,
    Downloading,
    Failed,
}

data class MmsTile(
    val messageId: Long,
    val seq: Int,
    val kind: MmsTileKind,
    val uri: Uri? = null,
    /**
     * Item-9 part label for Video / Audio / Contact card / Not supported yet.
     * Resource id, never a sender filename.
     */
    val partLabelRes: Int? = null,
) {
    val opensViewer: Boolean get() = kind == MmsTileKind.Photo && uri != null
    val runsDownload: Boolean
        get() = kind == MmsTileKind.NotDownloaded || kind == MmsTileKind.Failed
}

/**
 * Classify a part from MIME + size only. Never looks at a sender filename
 * or content id.
 *
 * @return null to skip (empty image, SMIL, plain text).
 */
fun classifyMmsPart(contentType: String, byteSize: Long): Pair<MmsTileKind, Int?>? {
    val type = contentType.substringBefore(';').trim().lowercase()
    return when {
        type.startsWith("image/") -> {
            if (byteSize <= 0L) null
            else MmsTileKind.Photo to null
        }
        type == "application/smil" || type.startsWith("application/smil+") -> null
        type == "text/plain" || type.startsWith("text/plain;") -> null
        type.startsWith("video/") ->
            MmsTileKind.Video to com.pinotrouge.messaging.R.string.mms_part_video
        type.startsWith("audio/") ->
            MmsTileKind.Audio to com.pinotrouge.messaging.R.string.mms_part_audio
        type == "text/vcard" ||
            type == "text/x-vcard" ||
            type == "application/vcard" ||
            type.endsWith("vcard") ->
            MmsTileKind.Contact to com.pinotrouge.messaging.R.string.mms_part_contact
        type.startsWith("text/") -> null
        byteSize > 0L ->
            MmsTileKind.Unsupported to com.pinotrouge.messaging.R.string.mms_part_unsupported
        else -> null
    }
}

fun stubTileKind(expired: Boolean, download: MmsDownloadUi): MmsTileKind = when {
    expired -> MmsTileKind.Expired
    download == MmsDownloadUi.Downloading -> MmsTileKind.Downloading
    download == MmsDownloadUi.Failed -> MmsTileKind.Failed
    else -> MmsTileKind.NotDownloaded
}
