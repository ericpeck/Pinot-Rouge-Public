package com.pinotrouge.messaging.data.telephony

import java.io.ByteArrayOutputStream

/**
 * M-Send.req composer. The platform [android.telephony.SmsManager] delivers
 * the PDU to the carrier MMSC — this class does not open a socket.
 *
 * WSP multipart entries are `HeadersLen DataLen ContentType Headers Data`.
 * There is no field identifier in front of ContentType; a previous version
 * wrote [HEADER_CONTENT_TYPE] `and 0x7F` (`0x04`) there, which a strict
 * parser reads as the first byte of the type.
 *
 * Photo send is SMIL → optional text → image. Content-IDs and Content-Locations
 * are fixed so the SMIL `src` matches the part Location (the classic iPhone
 * blank-image bug is a mismatch here).
 */
object MmsSendComposer {

    data class OutboundImage(
        val contentType: String,
        val bytes: ByteArray,
        val location: String,
    )

    data class SendConf(
        val responseStatus: Int?,
        val messageId: String?,
    ) {
        val ok: Boolean get() = responseStatus == RESPONSE_STATUS_OK
    }

    /**
     * @param recipients other parties (not including self)
     * @param body UTF-8 text body / caption. Blank is omitted when an image is present.
     * @param image encoded image bytes, or null for a text-only Send-Req
     */
    fun composeSendReq(
        recipients: List<String>,
        body: String,
        image: OutboundImage? = null,
    ): ByteArray {
        require(recipients.isNotEmpty()) { "at least one recipient" }
        val textBytes = body.toByteArray(Charsets.UTF_8)
        val includeText = body.isNotEmpty()

        val headers = ByteArrayOutputStream()
        headers.write(HEADER_MESSAGE_TYPE)
        headers.write(TYPE_SEND_REQ)
        headers.write(HEADER_TRANSACTION_ID)
        writeTextString(headers, "pinot-${System.currentTimeMillis()}")
        headers.write(HEADER_MMS_VERSION)
        headers.write(VERSION_1_2_SHORT)
        headers.write(HEADER_FROM)
        headers.write(0x01) // value-length 1
        headers.write(INSERT_ADDRESS_TOKEN)
        for (raw in recipients) {
            headers.write(HEADER_TO)
            writeEncodedString(headers, normalizeAddress(raw))
        }
        headers.write(HEADER_CONTENT_TYPE)
        if (image != null) {
            writeMultipartRelatedContentType(
                headers,
                type = "application/smil",
                start = CONTENT_ID_SMIL,
            )
        } else {
            writeMultipartRelatedContentType(headers)
        }

        val bodyOut = ByteArrayOutputStream()
        when {
            image == null -> {
                writeUintvar(bodyOut, 1)
                writeTextPart(bodyOut, textBytes)
            }
            else -> {
                val n = 1 + (if (includeText) 1 else 0) + 1
                writeUintvar(bodyOut, n)
                writeSmilPart(bodyOut, image.location, includeText)
                if (includeText) writeTextPart(bodyOut, textBytes, withLocation = true)
                writeImagePart(bodyOut, image)
            }
        }

        val out = ByteArrayOutputStream()
        out.write(headers.toByteArray())
        out.write(bodyOut.toByteArray())
        return out.toByteArray()
    }

    /**
     * M-Send.conf in [android.telephony.SmsManager.EXTRA_MMS_DATA].
     * `RESULT_OK` is not a verdict — this is. An empty or absent PDU has
     * no [SendConf.responseStatus]; [com.pinotrouge.messaging.sms.SmsSender]
     * treats that as unknown, not failed.
     */
    fun parseSendConf(pdu: ByteArray): SendConf {
        var i = 0
        var status: Int? = null
        var messageId: String? = null
        while (i < pdu.size) {
            val header = pdu[i].toInt() and 0xFF
            i++
            when (header) {
                HEADER_MESSAGE_TYPE, HEADER_MMS_VERSION,
                HEADER_RESPONSE_STATUS, HEADER_STATUS,
                -> {
                    if (i >= pdu.size) break
                    val value = pdu[i].toInt() and 0xFF
                    i++
                    if (header == HEADER_RESPONSE_STATUS) status = value
                }
                HEADER_MESSAGE_ID, HEADER_TRANSACTION_ID -> {
                    val (text, next) = readCString(pdu, i) ?: break
                    i = next
                    if (header == HEADER_MESSAGE_ID) messageId = text
                }
                HEADER_CONTENT_TYPE -> break
                else -> {
                    if (header in 32..127) {
                        i--
                        val skipped = readCString(pdu, i) ?: break
                        i = skipped.second
                    } else {
                        val skipped = skipValueLengthField(pdu, i) ?: break
                        i = skipped
                    }
                }
            }
        }
        return SendConf(responseStatus = status, messageId = messageId)
    }

    private fun writeTextPart(
        out: ByteArrayOutputStream,
        textBytes: ByteArray,
        withLocation: Boolean = false,
    ) {
        val contentType = ByteArrayOutputStream()
        writeContentType(
            contentType,
            wellKnown = WELL_KNOWN_TEXT_PLAIN,
            params = listOf(PARAM_CHARSET to byteArrayOf(CHARSET_UTF8_SHORT.toByte())),
        )
        writePart(
            out,
            contentType.toByteArray(),
            contentId = CONTENT_ID_TEXT,
            contentLocation = if (withLocation) LOCATION_TEXT else null,
            data = textBytes,
        )
    }

    private fun writeSmilPart(
        out: ByteArrayOutputStream,
        imageLocation: String,
        includeText: Boolean,
    ) {
        val smil = smilXml(imageLocation, includeText)
        val contentType = ByteArrayOutputStream()
        writeContentType(contentType, extensionMedia = "application/smil")
        writePart(
            out,
            contentType.toByteArray(),
            contentId = CONTENT_ID_SMIL,
            contentLocation = LOCATION_SMIL,
            data = smil,
        )
    }

    private fun writeImagePart(out: ByteArrayOutputStream, image: OutboundImage) {
        val media = image.contentType.substringBefore(';').trim().lowercase()
        val contentType = ByteArrayOutputStream()
        val wellKnown = wellKnownImage(media)
        if (wellKnown != null) {
            writeContentType(contentType, wellKnown = wellKnown)
        } else {
            writeContentType(contentType, extensionMedia = media)
        }
        writePart(
            out,
            contentType.toByteArray(),
            contentId = CONTENT_ID_IMAGE,
            contentLocation = image.location,
            data = image.bytes,
        )
    }

    private fun writePart(
        out: ByteArrayOutputStream,
        contentType: ByteArray,
        contentId: String,
        contentLocation: String?,
        data: ByteArray,
    ) {
        val headers = ByteArrayOutputStream()
        headers.write(contentType)
        headers.write(WSP_CONTENT_ID)
        writeQuotedString(headers, contentId)
        if (contentLocation != null) {
            headers.write(WSP_CONTENT_LOCATION)
            writeTextString(headers, contentLocation)
        }
        val headerBytes = headers.toByteArray()
        writeUintvar(out, headerBytes.size)
        writeUintvar(out, data.size)
        out.write(headerBytes)
        out.write(data)
    }

    private fun smilXml(imageLocation: String, includeText: Boolean): ByteArray {
        val textTag = if (includeText) {
            """<text src="$LOCATION_TEXT" region="Text"/>"""
        } else {
            ""
        }
        val xml = "<smil><head><layout><root-layout/>" +
            """<region id="Image" fit="meet"/>""" +
            """<region id="Text" fit="scroll"/>""" +
            "</layout></head><body><par>" +
            """<img src="$imageLocation" region="Image"/>""" +
            textTag +
            "</par></body></smil>"
        return xml.toByteArray(Charsets.US_ASCII)
    }

    private fun writeMultipartRelatedContentType(
        out: ByteArrayOutputStream,
        type: String? = null,
        start: String? = null,
    ) {
        val params = ArrayList<Pair<Int, ByteArray>>()
        if (type != null) {
            params += PARAM_TYPE to textStringBytes(type)
        }
        if (start != null) {
            params += PARAM_START to textStringBytes(start)
        }
        writeContentType(out, wellKnown = WELL_KNOWN_MULTIPART_RELATED, params = params)
    }

    /**
     * Content-general-form: Value-length covers the media type **and** every
     * parameter. Parameters after an under-counted length are read as the
     * next field (nEntries, or the next part).
     */
    private fun writeContentType(
        out: ByteArrayOutputStream,
        wellKnown: Int? = null,
        extensionMedia: String? = null,
        params: List<Pair<Int, ByteArray>> = emptyList(),
    ) {
        val media = ByteArrayOutputStream()
        when {
            wellKnown != null -> media.write(wellKnown or 0x80)
            extensionMedia != null -> writeTextString(media, extensionMedia)
            else -> error("content-type needs a media")
        }
        for ((id, value) in params) {
            media.write(id)
            media.write(value)
        }
        val bytes = media.toByteArray()
        writeValueLength(out, bytes.size)
        out.write(bytes)
    }

    internal fun normalizeAddress(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() || it == '+' }
        return if (digits.length >= 7) {
            "$digits/TYPE=PLMN"
        } else {
            trimmed
        }
    }

    private fun wellKnownImage(media: String): Int? = when (media) {
        "image/jpeg", "image/jpg" -> WELL_KNOWN_JPEG
        "image/gif" -> WELL_KNOWN_GIF
        "image/png" -> WELL_KNOWN_PNG
        else -> null
    }

    private fun writeTextString(out: ByteArrayOutputStream, text: String) {
        out.write(textStringBytes(text))
    }

    private fun textStringBytes(text: String): ByteArray {
        // These strings are ASCII by construction (CIDs, locations, transaction
        // ids). US_ASCII maps anything else to '?', so a 0x7F quote octet
        // would never fire — do not pretend it protects non-ASCII.
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream(bytes.size + 1)
        out.write(bytes)
        out.write(0x00)
        return out.toByteArray()
    }

    private fun writeEncodedString(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val value = ByteArrayOutputStream()
        value.write(CHARSET_UTF8_SHORT)
        value.write(bytes)
        value.write(0x00)
        val v = value.toByteArray()
        writeValueLength(out, v.size)
        out.write(v)
    }

    private fun writeQuotedString(out: ByteArrayOutputStream, text: String) {
        out.write(QUOTED_STRING_FLAG)
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.write(0x00)
    }

    private fun writeValueLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 31) {
            out.write(length)
        } else {
            out.write(31)
            writeUintvar(out, length)
        }
    }

    private fun writeUintvar(out: ByteArrayOutputStream, value: Int) {
        var v = value
        val stack = ArrayList<Int>()
        stack.add(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            stack.add((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        for (i in stack.lastIndex downTo 0) {
            out.write(stack[i])
        }
    }

    private fun readCString(pdu: ByteArray, start: Int): Pair<String, Int>? {
        var i = start
        if (i >= pdu.size) return null
        if ((pdu[i].toInt() and 0xFF) == 0x7F) i++
        val from = i
        while (i < pdu.size) {
            if (pdu[i].toInt() == 0) {
                return String(pdu, from, i - from, Charsets.US_ASCII) to (i + 1)
            }
            i++
        }
        return null
    }

    private fun skipValueLengthField(pdu: ByteArray, start: Int): Int? {
        if (start >= pdu.size) return null
        val v = pdu[start].toInt() and 0xFF
        return when {
            v <= 30 -> {
                val end = start + 1 + v
                if (end > pdu.size) null else end
            }
            v == 31 -> {
                var i = start + 1
                var len = 0
                repeat(5) {
                    if (i >= pdu.size) return null
                    val b = pdu[i].toInt() and 0xFF
                    i++
                    len = (len shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) {
                        val end = i + len
                        return if (end > pdu.size) null else end
                    }
                }
                null
            }
            v in 32..127 -> readCString(pdu, start)?.second
            else -> start + 1
        }
    }

    private const val HEADER_MESSAGE_TYPE = 0x8C
    private const val HEADER_TRANSACTION_ID = 0x98
    private const val HEADER_MMS_VERSION = 0x8D
    private const val HEADER_FROM = 0x89
    private const val HEADER_TO = 0x97
    private const val HEADER_CONTENT_TYPE = 0x84
    private const val HEADER_MESSAGE_ID = 0x8B
    private const val HEADER_RESPONSE_STATUS = 0x92
    private const val HEADER_STATUS = 0x95
    private const val TYPE_SEND_REQ = 0x80
    private const val VERSION_1_2_SHORT = 0x92
    private const val INSERT_ADDRESS_TOKEN = 0x81

    private const val WSP_CONTENT_ID = 0xC0
    private const val WSP_CONTENT_LOCATION = 0x8E
    private const val PARAM_CHARSET = 0x81
    private const val PARAM_TYPE = 0x89
    private const val PARAM_START = 0x8A
    private const val CHARSET_UTF8_SHORT = 0xEA
    private const val QUOTED_STRING_FLAG = 0x22

    /** WSP Assigned Numbers, encoded as short-integer `0x80 | n`. */
    private const val WELL_KNOWN_TEXT_PLAIN = 0x03
    private const val WELL_KNOWN_GIF = 0x1D
    private const val WELL_KNOWN_JPEG = 0x1E
    private const val WELL_KNOWN_PNG = 0x20
    private const val WELL_KNOWN_MULTIPART_RELATED = 0x33

    const val CONTENT_ID_SMIL = "<smil>"
    const val CONTENT_ID_TEXT = "<text_0>"
    const val CONTENT_ID_IMAGE = "<image_0>"
    const val LOCATION_SMIL = "smil.xml"
    const val LOCATION_TEXT = "text_0.txt"

    const val RESPONSE_STATUS_OK = 0x80
}
