package com.pinotrouge.messaging.data.telephony

/**
 * Sequential OMA MMS / WSP decoder.
 *
 * Walks encoded headers then, for M-Retrieve.conf / M-Send.req, the WSP
 * multipart body (`nEntries`, then per part `HeadersLen DataLen ContentType
 * Headers Data`). Addresses come only from From / To / Cc / Bcc — never from
 * scanning the PDU as text, so digit runs inside a JPEG cannot become
 * participants.
 *
 * Failures return null (or a partial result with whatever headers were
 * well-formed). The walker does not throw on truncated input.
 */
object MmsPduDecoder {

    data class Notification(
        val originator: String?,
        val contentLocation: String?,
        val subject: String?,
        val expiryMillis: Long?,
        val transactionId: String? = null,
    )

    data class Retrieved(
        val originator: String?,
        val toAddresses: List<String>,
        val subject: String?,
        val textBody: String,
        val hasAttachment: Boolean,
        val hasPhoto: Boolean = false,
        val hasAnyPart: Boolean = false,
        val parts: List<MmsPart> = emptyList(),
        val transactionId: String? = null,
    )

    fun messageType(pdu: ByteArray): Int? {
        if (pdu.isEmpty()) return null
        return try {
            Parser(pdu).peekMessageType()
        } catch (_: Exception) {
            null
        }
    }

    fun parseNotification(pdu: ByteArray): Notification? {
        val parsed = parse(pdu) ?: return null
        if (parsed.messageType != TYPE_NOTIFICATION_IND) return null
        val expiryMillis = parsed.expiryMillis(System.currentTimeMillis())
        return Notification(
            originator = parsed.originator,
            contentLocation = parsed.contentLocation,
            subject = parsed.subject,
            expiryMillis = expiryMillis,
            transactionId = parsed.transactionId,
        )
    }

    fun parseRetrieveConf(pdu: ByteArray): Retrieved? {
        val parsed = parse(pdu) ?: return null
        val type = parsed.messageType
        if (type == TYPE_NOTIFICATION_IND) return null
        if (type != null &&
            type != TYPE_RETRIEVE_CONF &&
            type != TYPE_SEND_REQ
        ) {
            return null
        }
        if (parsed.originator.isNullOrBlank() &&
            parsed.body.isBlank() &&
            !parsed.hasPhoto &&
            !parsed.hasAnyPart
        ) {
            return null
        }
        return Retrieved(
            originator = parsed.originator,
            toAddresses = parsed.participants,
            subject = parsed.subject,
            textBody = parsed.body,
            hasAttachment = parsed.hasPhoto || parsed.hasAnyPart,
            hasPhoto = parsed.hasPhoto,
            hasAnyPart = parsed.hasAnyPart,
            parts = parsed.parts,
            transactionId = parsed.transactionId,
        )
    }

    /**
     * Build an [IncomingMms] from either a notification (body empty / subject only)
     * or a full retrieve-conf.
     */
    fun toIncomingMms(
        pdu: ByteArray,
        receivedAtMillis: Long = System.currentTimeMillis(),
    ): IncomingMms? {
        val parsed = parse(pdu) ?: return null
        val originator = parsed.originator ?: return null
        val type = parsed.messageType ?: TYPE_RETRIEVE_CONF
        if (type == TYPE_NOTIFICATION_IND) {
            return IncomingMms(
                originator = originator,
                participants = emptyList(),
                body = "",
                subject = parsed.subject,
                receivedAtMillis = receivedAtMillis,
                hasPhoto = false,
                hasAnyPart = false,
                hasAttachment = false,
                transactionId = parsed.transactionId,
                contentLocation = parsed.contentLocation,
                expiryMillis = parsed.expiryMillis(receivedAtMillis),
                messageType = TYPE_NOTIFICATION_IND,
                parts = emptyList(),
            )
        }
        if (type != TYPE_RETRIEVE_CONF && type != TYPE_SEND_REQ) return null
        return IncomingMms(
            originator = originator,
            participants = parsed.participants,
            body = parsed.body,
            subject = parsed.subject,
            receivedAtMillis = receivedAtMillis,
            hasPhoto = parsed.hasPhoto,
            hasAnyPart = parsed.hasAnyPart,
            hasAttachment = parsed.hasPhoto || parsed.hasAnyPart,
            transactionId = parsed.transactionId,
            contentLocation = parsed.contentLocation,
            expiryMillis = parsed.expiryMillis(receivedAtMillis),
            messageType = type,
            parts = parsed.parts,
        )
    }

    /**
     * Compose an M-NotifyResp.ind echoing [transactionId].
     *
     * Wire: Message-Type 0x8C 0x83, MMS-Version 1.2 as short-integer
     * (value 0x12 → 0x8D 0x92), Transaction-Id 0x98, Status 0x95 [status]
     * (Retrieved = 0x81).
     */
    fun composeNotifyResp(
        transactionId: String,
        status: Int = STATUS_RETRIEVED,
    ): ByteArray = composeNotifyResp(transactionId.toByteArray(Charsets.US_ASCII), status)

    fun composeNotifyResp(
        transactionId: ByteArray,
        status: Int = STATUS_RETRIEVED,
    ): ByteArray {
        val id = if (transactionId.isNotEmpty() && transactionId.last() == 0.toByte()) {
            transactionId
        } else {
            transactionId + 0
        }
        val out = ByteArray(5 + id.size + 2)
        out[0] = HEADER_MESSAGE_TYPE.toByte()
        out[1] = TYPE_NOTIFYRESP_IND.toByte()
        out[2] = HEADER_MMS_VERSION.toByte()
        out[3] = VERSION_1_2_SHORT.toByte()
        out[4] = HEADER_TRANSACTION_ID.toByte()
        id.copyInto(out, 5)
        val statusAt = 5 + id.size
        out[statusAt] = HEADER_STATUS.toByte()
        out[statusAt + 1] = status.toByte()
        return out
    }

    internal fun parse(pdu: ByteArray): Parsed? {
        if (pdu.isEmpty()) return null
        return try {
            Parser(pdu).parse()
        } catch (_: Exception) {
            null
        }
    }

    // Header identifiers (OMA-TS-MMS-ENC, encoded 0x80|n).
    private const val HEADER_BCC = 0x81
    private const val HEADER_CC = 0x82
    private const val HEADER_CONTENT_LOCATION = 0x83
    private const val HEADER_CONTENT_TYPE = 0x84
    private const val HEADER_DATE = 0x85
    private const val HEADER_DELIVERY_REPORT = 0x86
    private const val HEADER_DELIVERY_TIME = 0x87
    private const val HEADER_EXPIRY = 0x88
    private const val HEADER_FROM = 0x89
    private const val HEADER_MESSAGE_CLASS = 0x8A
    private const val HEADER_MESSAGE_ID = 0x8B
    private const val HEADER_MESSAGE_TYPE = 0x8C
    private const val HEADER_MMS_VERSION = 0x8D
    private const val HEADER_MESSAGE_SIZE = 0x8E
    private const val HEADER_PRIORITY = 0x8F
    private const val HEADER_READ_REPLY = 0x90
    private const val HEADER_REPORT_ALLOWED = 0x91
    private const val HEADER_RESPONSE_STATUS = 0x92
    private const val HEADER_RESPONSE_TEXT = 0x93
    private const val HEADER_SENDER_VISIBILITY = 0x94
    private const val HEADER_STATUS = 0x95
    private const val HEADER_SUBJECT = 0x96
    private const val HEADER_TO = 0x97
    private const val HEADER_TRANSACTION_ID = 0x98

    const val TYPE_SEND_REQ = 0x80
    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_NOTIFYRESP_IND = 0x83
    const val TYPE_RETRIEVE_CONF = 0x84
    const val TYPE_DELIVERY_IND = 0x86

    const val STATUS_RETRIEVED = 0x81

    /** MMS 1.2 (0x12) encoded as a WSP short-integer. */
    private const val VERSION_1_2_SHORT = 0x92

    private const val ADDRESS_PRESENT = 0x80
    private const val INSERT_ADDRESS = 0x81
    private const val TOKEN_ABSOLUTE = 0x80
    private const val TOKEN_RELATIVE = 0x81

    private const val LENGTH_QUOTE = 31
    private const val SHORT_LENGTH_MAX = 30
    private const val TEXT_MIN = 32
    private const val TEXT_MAX = 127
    private const val QUOTE = 127
    private const val QUOTED_STRING_FLAG = 34

    // WSP part headers (HTTP header assigned numbers, encoded 0x80|n).
    private const val WSP_CONTENT_TYPE = 0x91
    private const val WSP_CONTENT_LOCATION = 0x8E
    private const val WSP_CONTENT_ID = 0xC0
    private const val WSP_CONTENT_ID_UNENCODED = 0x40
    private const val WSP_CONTENT_DISPOSITION = 0xAE
    private const val WSP_CONTENT_DISPOSITION_DEP = 0xC5

    // WSP well-known content-type parameters (encoded 0x80|n).
    private const val PARAM_CHARSET = 0x81
    private const val PARAM_TYPE = 0x89
    private const val PARAM_TYPE_DEP = 0x83
    private const val PARAM_START = 0x8A
    private const val PARAM_START_WSP13 = 0x99
    private const val PARAM_NAME_DEP = 0x85
    private const val PARAM_NAME = 0x97
    private const val PARAM_FILENAME_DEP = 0x86
    private const val PARAM_FILENAME = 0x98

    // IANA MIBEnum 106 (UTF-8) encodes as short-integer 0xEA (0x80|106).

    internal data class Parsed(
        val messageType: Int?,
        val originator: String?,
        val participants: List<String>,
        val subject: String?,
        val transactionId: String?,
        val contentLocation: String?,
        val expiryAbsoluteEpochSec: Long?,
        val expiryRelativeSec: Long?,
        val contentType: String?,
        val parts: List<MmsPart>,
        val body: String,
        val hasPhoto: Boolean,
        val hasAnyPart: Boolean,
    ) {
        fun expiryMillis(receivedAtMillis: Long): Long? = when {
            expiryAbsoluteEpochSec != null -> expiryAbsoluteEpochSec * 1000L
            expiryRelativeSec != null -> receivedAtMillis + expiryRelativeSec * 1000L
            else -> null
        }
    }

    private class Parser(private val bytes: ByteArray) {
        var i: Int = 0

        fun remaining(): Int = bytes.size - i
        fun has(n: Int = 1): Boolean = n >= 0 && i + n <= bytes.size
        fun peek(): Int = if (has()) bytes[i].toInt() and 0xFF else -1

        fun u8(): Int {
            if (!has()) return -1
            return bytes[i++].toInt() and 0xFF
        }

        fun skip(n: Int) {
            if (n <= 0) return
            i = (i + n).coerceAtMost(bytes.size)
        }

        fun take(n: Int): ByteArray {
            if (n <= 0 || !has()) return ByteArray(0)
            val count = n.coerceAtMost(remaining())
            val out = bytes.copyOfRange(i, i + count)
            i += count
            return out
        }

        fun peekMessageType(): Int? {
            while (has()) {
                val header = u8()
                if (header < 0) return null
                if (header in TEXT_MIN..TEXT_MAX) {
                    // Application-header name; skip name and a following text value.
                    i--
                    readTextString()
                    val next = peek()
                    if (next in TEXT_MIN..TEXT_MAX || next == QUOTE) readTextString()
                    continue
                }
                if (header == HEADER_MESSAGE_TYPE) {
                    val t = u8()
                    return if (t < 0) null else t
                }
                skipMmsHeaderValue()
            }
            return null
        }

        fun parse(): Parsed? {
            var messageType: Int? = null
            var from: String? = null
            val to = ArrayList<String>()
            val cc = ArrayList<String>()
            val bcc = ArrayList<String>()
            var subject: String? = null
            var transactionId: String? = null
            var contentLocation: String? = null
            var expiryAbsolute: Long? = null
            var expiryRelative: Long? = null
            var contentType: String? = null

            while (has()) {
                val header = u8()
                if (header < 0) break
                if (header in TEXT_MIN..TEXT_MAX) {
                    i--
                    readTextString()
                    val next = peek()
                    if (next in TEXT_MIN..TEXT_MAX || next == QUOTE) readTextString()
                    continue
                }
                when (header) {
                    HEADER_MESSAGE_TYPE -> messageType = u8().takeIf { it >= 0 }
                    HEADER_MMS_VERSION -> skipInteger()
                    HEADER_TRANSACTION_ID -> transactionId = readTextString()
                    HEADER_FROM -> from = readFrom()
                    HEADER_TO -> readEncodedString()?.let { to += stripAddressTypeSuffix(it) }
                    HEADER_CC -> readEncodedString()?.let { cc += stripAddressTypeSuffix(it) }
                    HEADER_BCC -> readEncodedString()?.let { bcc += stripAddressTypeSuffix(it) }
                    HEADER_SUBJECT -> subject = readEncodedString()
                    HEADER_CONTENT_LOCATION -> contentLocation = readTextString()
                    HEADER_EXPIRY, HEADER_DELIVERY_TIME -> {
                        val (abs, rel) = readExpiry()
                        if (header == HEADER_EXPIRY) {
                            expiryAbsolute = abs
                            expiryRelative = rel
                        }
                    }
                    HEADER_DATE, HEADER_MESSAGE_SIZE -> skipLongInteger()
                    HEADER_MESSAGE_CLASS -> skipMessageClass()
                    HEADER_DELIVERY_REPORT,
                    HEADER_PRIORITY,
                    HEADER_READ_REPLY,
                    HEADER_REPORT_ALLOWED,
                    HEADER_RESPONSE_STATUS,
                    HEADER_SENDER_VISIBILITY,
                    HEADER_STATUS,
                    -> u8()
                    HEADER_MESSAGE_ID, HEADER_RESPONSE_TEXT -> readTextString()
                    HEADER_CONTENT_TYPE -> {
                        contentType = readContentType().media
                        // Content-Type is the last MMS header; body follows.
                        break
                    }
                    else -> skipMmsHeaderValue()
                }
            }

            val originator = from?.let { stripAddressTypeSuffix(it) }?.takeIf { it.isNotBlank() }
            val others = (to + cc + bcc)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != originator }
                .distinct()

            val isNotification = messageType == TYPE_NOTIFICATION_IND
            val rawParts = if (!isNotification && isMultipart(contentType)) {
                readMultipartParts()
            } else {
                emptyList()
            }

            val kept = rawParts.filterNot { it.isSmil }
            val body = if (isNotification) {
                ""
            } else {
                kept.filter { it.isTextPlain }
                    .joinToString("") { decodeText(it.bytes, it.charset) }
            }
            val parts = if (isNotification) emptyList() else kept
            val hasPhoto = !isNotification && parts.any { it.isImage }
            val hasAnyPart = !isNotification && parts.any { !it.isTextPlain }

            return Parsed(
                messageType = messageType,
                originator = originator,
                participants = others,
                subject = subject,
                transactionId = transactionId,
                contentLocation = contentLocation,
                expiryAbsoluteEpochSec = expiryAbsolute,
                expiryRelativeSec = expiryRelative,
                contentType = contentType,
                parts = parts,
                body = body,
                hasPhoto = hasPhoto,
                hasAnyPart = hasAnyPart,
            )
        }

        private fun skipMmsHeaderValue() {
            // Best-effort skip so an unknown field does not desynchronise the walk.
            val v = peek()
            if (v < 0) return
            when {
                v <= SHORT_LENGTH_MAX -> {
                    val len = u8()
                    skip(len)
                }
                v == LENGTH_QUOTE -> {
                    u8()
                    val len = readUintvar()
                    if (len > 0) skip(len)
                }
                v in TEXT_MIN..TEXT_MAX || v == QUOTE -> readTextString()
                else -> u8() // short-integer / octet
            }
        }

        private fun skipMessageClass() {
            val v = peek()
            if (v < 0) return
            if (v >= 0x80) u8() else readTextString()
        }

        private fun skipInteger() {
            val v = peek()
            if (v < 0) return
            if (v >= 0x80) u8() else skipLongInteger()
        }

        private fun skipLongInteger() {
            val len = u8()
            if (len in 1..SHORT_LENGTH_MAX) skip(len)
        }

        private fun readUintvar(): Int {
            var value = 0
            repeat(5) {
                val b = u8()
                if (b < 0) return value
                value = (value shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) return value
            }
            return value
        }

        private fun readValueLength(): Int {
            val b = peek()
            if (b < 0) return 0
            return when {
                b <= SHORT_LENGTH_MAX -> {
                    u8()
                    b
                }
                b == LENGTH_QUOTE -> {
                    u8()
                    readUintvar()
                }
                else -> 0
            }
        }

        private fun readLongInteger(): Long? {
            val len = u8()
            if (len < 1 || len > SHORT_LENGTH_MAX || !has(len)) return null
            var value = 0L
            repeat(len) {
                val b = u8()
                if (b < 0) return value
                value = (value shl 8) or b.toLong()
            }
            return value
        }

        private fun readInteger(): Long? {
            val v = peek()
            if (v < 0) return null
            return if (v >= 0x80) (u8() and 0x7F).toLong() else readLongInteger()
        }

        private fun readTextString(): String? {
            if (!has()) return null
            if (peek() == QUOTE) u8()
            val start = i
            while (has()) {
                val b = u8()
                if (b == 0) {
                    return decodeUsAscii(bytes, start, i - 1)
                }
            }
            return if (i > start) decodeUsAscii(bytes, start, i) else null
        }

        private fun readQuotedString(): String? {
            if (peek() == QUOTED_STRING_FLAG) u8()
            return readTextString()
        }

        private fun readEncodedString(): String? {
            val v = peek()
            if (v < 0) return null
            if (v <= SHORT_LENGTH_MAX || v == LENGTH_QUOTE) {
                val len = readValueLength()
                val end = (i + len).coerceAtMost(bytes.size)
                // Optional charset as Integer-value, then Text-string.
                val inner = peek()
                if (inner >= 0x80 || (inner in 1..SHORT_LENGTH_MAX)) {
                    readInteger()
                }
                val text = readTextString()
                if (i < end) i = end
                return text
            }
            return readTextString()
        }

        private fun readFrom(): String? {
            val len = readValueLength()
            val end = (i + len).coerceAtMost(bytes.size)
            val token = u8()
            val address = if (token == ADDRESS_PRESENT) {
                readEncodedString()
            } else {
                // Insert-address-token (0x81) — no originator in the PDU.
                null
            }
            if (i < end) i = end
            return address
        }

        private fun readExpiry(): Pair<Long?, Long?> {
            val len = readValueLength()
            val end = (i + len).coerceAtMost(bytes.size)
            val token = u8()
            val number = readLongInteger()
            if (i < end) i = end
            return when (token) {
                TOKEN_ABSOLUTE -> number to null
                TOKEN_RELATIVE -> null to number
                else -> null to null
            }
        }

        private data class Media(
            val media: String?,
            val params: Map<String, String>,
            val charset: String? = null,
        )

        private fun readContentType(): Media {
            val v = peek()
            if (v < 0) return Media(null, emptyMap())
            return when {
                v < TEXT_MIN -> {
                    // Content-general-form: Value-length Media-type *(Parameter)
                    val len = readValueLength()
                    val end = (i + len).coerceAtMost(bytes.size)
                    val media = readConstrainedMedia()
                    val params = LinkedHashMap<String, String>()
                    var charset: String? = null
                    while (i < end && has()) {
                        val p = readContentTypeParam(end) ?: break
                        if (p.first == "charset") charset = p.second
                        params[p.first] = p.second
                    }
                    if (i < end) i = end
                    Media(media, params, charset)
                }
                v <= TEXT_MAX -> Media(readTextString(), emptyMap())
                else -> Media(wellKnownContentType(u8() and 0x7F), emptyMap())
            }
        }

        private fun readConstrainedMedia(): String? {
            val v = peek()
            if (v < 0) return null
            return when {
                v in TEXT_MIN..TEXT_MAX || v == QUOTE -> readTextString()
                v >= 0x80 -> wellKnownContentType(u8() and 0x7F)
                else -> null
            }
        }

        private fun readContentTypeParam(limit: Int): Pair<String, String>? {
            if (i >= limit || !has()) return null
            val param = u8()
            if (param < 0) return null
            if (param in TEXT_MIN..TEXT_MAX) {
                i--
                val name = readTextString() ?: return null
                val value = when (val nv = peek()) {
                    in 0..TEXT_MAX -> readTextString().orEmpty()
                    else -> {
                        if (nv >= 0x80) (u8() and 0x7F).toString() else readTextString().orEmpty()
                    }
                }
                return name to value
            }
            return when (param) {
                PARAM_CHARSET, 0x01 -> {
                    val charset = readCharset()
                    "charset" to charset
                }
                PARAM_TYPE, PARAM_TYPE_DEP -> {
                    "type" to (readConstrainedMedia() ?: "")
                }
                PARAM_START, PARAM_START_WSP13 -> {
                    "start" to (readTextString() ?: "")
                }
                PARAM_NAME, PARAM_NAME_DEP, PARAM_FILENAME, PARAM_FILENAME_DEP -> {
                    "name" to (readTextString() ?: "")
                }
                else -> {
                    // Skip a typed value so we stay in sync.
                    skipParamValue(limit)
                    "p${param.toString(16)}" to ""
                }
            }
        }

        private fun skipParamValue(limit: Int) {
            val v = peek()
            if (v < 0 || i >= limit) return
            when {
                v <= SHORT_LENGTH_MAX -> {
                    val len = u8()
                    skip(len.coerceAtMost(limit - i))
                }
                v == LENGTH_QUOTE -> {
                    u8()
                    val len = readUintvar()
                    skip(len.coerceAtMost(limit - i))
                }
                v in TEXT_MIN..TEXT_MAX || v == QUOTE -> readTextString()
                else -> u8()
            }
        }

        private fun readCharset(): String {
            val v = peek()
            if (v < 0) return "utf-8"
            if (v in TEXT_MIN..TEXT_MAX || v == 0) {
                return readTextString()?.lowercase() ?: "utf-8"
            }
            val mib = readInteger() ?: return "utf-8"
            return charsetName(mib.toInt())
        }

        private fun readMultipartParts(): List<MmsPart> {
            if (!has()) return emptyList()
            val nEntries = readUintvar()
            if (nEntries <= 0) return emptyList()
            val parts = ArrayList<MmsPart>(nEntries.coerceAtMost(64))
            repeat(nEntries.coerceAtMost(256)) {
                if (!has()) return parts
                val part = readOnePart() ?: return parts
                parts += part
            }
            return parts
        }

        private fun readOnePart(): MmsPart? {
            val headersLen = readUintvar()
            val dataLen = readUintvar()
            if (headersLen < 0 || dataLen < 0) return null
            if (!has(headersLen)) {
                // Truncated headers — consume what's left and stop.
                skip(remaining())
                return null
            }
            val headersEnd = i + headersLen
            val media = readContentType()
            var contentId: String? = null
            var contentLocation: String? = media.params["name"]
            var charset = media.charset
            while (i < headersEnd && has()) {
                val h = u8()
                if (h < 0) break
                when (h) {
                    WSP_CONTENT_TYPE -> {
                        val extra = readContentType()
                        if (extra.charset != null) charset = extra.charset
                    }
                    WSP_CONTENT_ID, WSP_CONTENT_ID_UNENCODED -> {
                        contentId = readQuotedString() ?: readTextString()
                    }
                    WSP_CONTENT_LOCATION -> contentLocation = readTextString()
                    PARAM_CHARSET, 0x01 -> charset = readCharset()
                    WSP_CONTENT_DISPOSITION, WSP_CONTENT_DISPOSITION_DEP -> {
                        val len = readValueLength()
                        skip(len.coerceAtMost(headersEnd - i))
                    }
                    else -> {
                        if (h in TEXT_MIN..TEXT_MAX) {
                            i--
                            readTextString()
                            if (peek() in TEXT_MIN..TEXT_MAX || peek() == QUOTE) readTextString()
                        } else {
                            skipPartHeaderValue(headersEnd)
                        }
                    }
                }
            }
            if (i < headersEnd) i = headersEnd
            val data = if (dataLen > remaining()) {
                // Truncated payload — do not throw; drop this part.
                skip(remaining())
                return null
            } else {
                take(dataLen)
            }
            val type = media.media ?: "*/*"
            return MmsPart(
                contentType = type,
                contentId = contentId,
                contentLocation = contentLocation,
                charset = charset,
                bytes = data,
            )
        }

        private fun skipPartHeaderValue(limit: Int) {
            val v = peek()
            if (v < 0 || i >= limit) return
            when {
                v <= SHORT_LENGTH_MAX -> {
                    val len = u8()
                    skip(len.coerceAtMost(limit - i))
                }
                v == LENGTH_QUOTE -> {
                    u8()
                    val len = readUintvar()
                    skip(len.coerceAtMost(limit - i))
                }
                v in TEXT_MIN..TEXT_MAX || v == QUOTE || v == QUOTED_STRING_FLAG -> {
                    readTextString()
                }
                else -> u8()
            }
        }
    }

    private fun isMultipart(contentType: String?): Boolean {
        val t = contentType?.lowercase() ?: return false
        return t.contains("multipart")
    }

    private fun stripAddressTypeSuffix(address: String): String {
        val slash = address.indexOf("/TYPE=")
        return if (slash > 0) address.substring(0, slash) else address
    }

    private fun decodeUsAscii(bytes: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        val chars = CharArray(end - start)
        for (idx in start until end) {
            chars[idx - start] = (bytes[idx].toInt() and 0x7F).toChar()
        }
        return String(chars)
    }

    private fun decodeText(bytes: ByteArray, charset: String?): String {
        val cs = when (charset?.lowercase()) {
            null, "", "*", "utf-8", "utf8" -> Charsets.UTF_8
            "us-ascii", "ascii", "iso-646-us" -> Charsets.US_ASCII
            "iso-8859-1", "latin1", "iso_8859-1" -> Charsets.ISO_8859_1
            "utf-16", "utf16", "utf-16be" -> Charsets.UTF_16BE
            "utf-16le" -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        return bytes.toString(cs)
    }

    private fun charsetName(mib: Int): String = when (mib) {
        0, 106 -> "utf-8"
        3 -> "us-ascii"
        4 -> "iso-8859-1"
        17 -> "shift_jis"
        1000, 1013, 1015 -> "utf-16be"
        1014 -> "utf-16le"
        2025 -> "gb2312"
        2026 -> "big5"
        113 -> "gbk"
        else -> "utf-8"
    }

    /**
     * WSP well-known media types (Assigned Numbers), matching AOSP
     * `WspTypeDecoder` / `PduContentTypes`: 0x03 text/plain, 0x1D image/gif,
     * 0x1E image/jpeg, 0x20 image/png, 0x23 multipart.mixed, 0x33
     * multipart.related.
     */
    private fun wellKnownContentType(index: Int): String {
        if (index in WELL_KNOWN_CONTENT_TYPES.indices) {
            return WELL_KNOWN_CONTENT_TYPES[index]
        }
        return "application/octet-stream"
    }

    private val WELL_KNOWN_CONTENT_TYPES = arrayOf(
        "*/*", // 0x00
        "text/*",
        "text/html",
        "text/plain", // 0x03 → 0x83
        "text/x-hdml",
        "text/x-ttml",
        "text/x-vCalendar",
        "text/x-vCard",
        "text/vnd.wap.wml",
        "text/vnd.wap.wmlscript",
        "text/vnd.wap.wta-event", // 0x0A
        "multipart/*",
        "multipart/mixed",
        "multipart/form-data",
        "multipart/byteranges",
        "multipart/alternative",
        "application/*", // 0x10
        "application/java-vm",
        "application/x-www-form-urlencoded",
        "application/x-hdmlc",
        "application/vnd.wap.wmlc",
        "application/vnd.wap.wmlscriptc",
        "application/vnd.wap.wta-eventc",
        "application/vnd.wap.uaprof",
        "application/vnd.wap.wtls-ca-certificate",
        "application/vnd.wap.wtls-user-certificate",
        "application/x-x509-ca-cert", // 0x1A
        "application/x-x509-user-cert",
        "image/*", // 0x1C
        "image/gif", // 0x1D → 0x9D
        "image/jpeg", // 0x1E → 0x9E
        "image/tiff",
        "image/png", // 0x20 → 0xA0
        "image/vnd.wap.wbmp",
        "application/vnd.wap.multipart.*",
        "application/vnd.wap.multipart.mixed", // 0x23 → 0xA3
        "application/vnd.wap.multipart.form-data",
        "application/vnd.wap.multipart.byteranges",
        "application/vnd.wap.multipart.alternative",
        "application/xml",
        "text/xml",
        "application/vnd.wap.wbxml",
        "application/x-x968-cross-cert",
        "application/x-x968-ca-cert",
        "application/x-x968-user-cert",
        "text/vnd.wap.si",
        "application/vnd.wap.sic",
        "text/vnd.wap.sl",
        "application/vnd.wap.slc",
        "text/vnd.wap.co",
        "application/vnd.wap.coc",
        "application/vnd.wap.multipart.related", // 0x33 → 0xB3
        "application/vnd.wap.sia",
        "text/vnd.wap.connectivity-xml",
        "application/vnd.wap.connectivity-wbxml",
        "application/pkcs7-mime",
        "application/vnd.wap.hashed-certificate",
        "application/vnd.wap.signed-certificate",
        "application/vnd.wap.cert-response",
        "application/xhtml+xml",
        "application/wml+xml",
        "text/css",
        "application/vnd.wap.mms-message",
        "application/vnd.wap.rollover-certificate",
        "application/vnd.wap.locc+wbxml",
        "application/vnd.wap.loc+xml",
        "application/vnd.syncml.dm+wbxml",
        "application/vnd.syncml.dm+xml",
        "application/vnd.syncml.notification",
        "application/vnd.wap.xhtml+xml",
        "application/vnd.wv.csp.cir",
        "application/vnd.oma.dd+xml",
        "application/vnd.oma.drm.message",
        "application/vnd.oma.drm.content",
        "application/vnd.oma.drm.rights+xml",
        "application/vnd.oma.drm.rights+wbxml",
        "application/vnd.wv.csp+xml",
        "application/vnd.wv.csp+wbxml",
        "application/vnd.syncml.ds.notification",
        "audio/*",
        "video/*",
        "application/vnd.oma.dd2+xml",
        "application/mikey",
        "application/vnd.oma.dcd",
        "application/vnd.oma.dcdc",
    )
}
