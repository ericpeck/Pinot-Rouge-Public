package com.pinotrouge.messaging.data.telephony

/**
 * A parsed incoming multimedia message ready for the filter pipeline.
 *
 * [originator] is the sender of *this* message (Filter Rule Spec §1).
 * [participants] are the other addresses on the thread (To/CC), used only for
 * the contacts short-circuit — [isKnownContact] is true if **any** of
 * originator + participants is a saved contact.
 *
 * [hasPhoto] and [hasAnyPart] are independent facts from the multipart walker.
 * A video MMS is `hasAnyPart = true`, `hasPhoto = false`. An M-Notification.ind
 * has both false — the PDU does not say what it will contain.
 *
 * [hasAttachment] stays as a constructor parameter so existing callers
 * (`IncomingMms(..., hasAttachment = true)`) keep compiling. When omitted it
 * derives from [hasPhoto] or [hasAnyPart].
 */
data class IncomingMms(
    val originator: String,
    val participants: List<String> = emptyList(),
    val body: String,
    val subject: String? = null,
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val hasPhoto: Boolean = false,
    val hasAnyPart: Boolean = false,
    val hasAttachment: Boolean = hasPhoto || hasAnyPart,
    val transactionId: String? = null,
    val contentLocation: String? = null,
    val expiryMillis: Long? = null,
    val messageType: Int = MmsPduDecoder.TYPE_RETRIEVE_CONF,
    val parts: List<MmsPart> = emptyList(),
    val subscriptionId: Int? = null,
) {
    /** Originator plus every other party — input to the any-participant contact rule. */
    val allAddresses: List<String>
        get() = (listOf(originator) + participants)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/**
 * One WSP multipart entry after SMIL has been skipped (or before, if the
 * caller built it by hand). [equals]/[hashCode] compare content, not identity,
 * because [bytes] would otherwise use referential equality.
 */
data class MmsPart(
    val contentType: String,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val charset: String? = null,
    val bytes: ByteArray,
) {
    val isSmil: Boolean
        get() {
            val type = contentType.lowercase()
            if (type == "application/smil" || type.startsWith("application/smil+")) return true
            val id = contentId.orEmpty().lowercase()
            val loc = contentLocation.orEmpty().lowercase()
            if (id.contains("smil") || loc.contains("smil")) return true
            return isTextPlain && (loc.contains("smil") || id.contains("smil"))
        }

    val isImage: Boolean
        get() = contentType.startsWith("image/", ignoreCase = true)

    val isTextPlain: Boolean
        get() {
            val type = contentType.lowercase()
            return type == "text/plain" || type.startsWith("text/plain;")
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmsPart) return false
        return contentType == other.contentType &&
            contentId == other.contentId &&
            contentLocation == other.contentLocation &&
            charset == other.charset &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = contentType.hashCode()
        result = 31 * result + (contentId?.hashCode() ?: 0)
        result = 31 * result + (contentLocation?.hashCode() ?: 0)
        result = 31 * result + (charset?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MmsPart(contentType=$contentType, contentId=$contentId, " +
            "contentLocation=$contentLocation, charset=$charset, bytes=${bytes.size})"
}
