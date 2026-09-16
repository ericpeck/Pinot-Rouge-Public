package com.pinotrouge.messaging.ui.filtered

import android.net.Uri
import com.pinotrouge.messaging.data.repo.HeldMediaRef
import com.pinotrouge.messaging.data.telephony.SmsRepository
import com.pinotrouge.messaging.ui.media.MmsTile
import com.pinotrouge.messaging.ui.media.MmsTileKind
import com.pinotrouge.messaging.ui.media.classifyMmsPart

/**
 * Stable [MmsTile.messageId] for a held row. Held ids are UUID strings, not
 * provider ids — this is only for test tags and the loader cache key, never
 * a Telephony lookup. Do not navigate to `photo/{mmsId}/{seq}` with it.
 */
internal fun heldTileMessageId(heldId: String): Long =
    heldId.hashCode().toLong() and 0x7fff_ffffL

internal fun HeldMediaRef.toMmsTile(heldId: String, seq: Int): MmsTile? {
    val classified = classifyMmsPart(contentType, file.length()) ?: return null
    val (kind, labelRes) = classified
    return MmsTile(
        messageId = heldTileMessageId(heldId),
        seq = seq,
        kind = kind,
        uri = if (kind == MmsTileKind.Photo) Uri.fromFile(file) else null,
        partLabelRes = labelRes,
    )
}

internal fun heldPreviewText(body: String, tiles: List<MmsTile>): String {
    val trimmed = body.replace('\n', ' ').trim()
    if (trimmed.isNotEmpty()) return trimmed
    if (tiles.any { it.kind == MmsTileKind.Photo }) {
        return SmsRepository.PHOTO_SNIPPET_FALLBACK
    }
    return ""
}
