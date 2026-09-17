package com.pinotrouge.messaging.sms

/**
 * Parses `sms:` / `smsto:` / `mms:` / `mmsto:` compose requests.
 *
 * Kept free of Android types so unit tests can cover scheme allowlisting and
 * length caps without Robolectric. [SendToActivity] supplies URI pieces.
 *
 * Pass the **encoded** scheme-specific part, never the already-decoded one.
 * Decoding twice splits on a literal `&` that was `%26` and turns `+` in the
 * body into a space.
 *
 * Opaque `sms:555?body=Hi` and hierarchical `sms://555?body=Hi` both put the
 * query on the scheme-specific part. Do not use `Uri.getQueryParameter` — it
 * throws on opaque URIs.
 */
object SendToParser {

    const val MAX_RECIPIENT_LENGTH = 64
    const val MAX_BODY_LENGTH = 10_000

    val ALLOWED_SCHEMES: Set<String> = setOf("sms", "smsto", "mms", "mmsto")

    data class Parsed(val recipient: String, val body: String)

    fun parse(
        scheme: String?,
        encodedSchemeSpecificPart: String?,
        extraBody: String? = null,
    ): Parsed? {
        val normalizedScheme = scheme?.lowercase() ?: return null
        if (normalizedScheme !in ALLOWED_SCHEMES) return null

        val ssp = encodedSchemeSpecificPart.orEmpty()
        val queryStart = ssp.indexOf('?')
        val beforeQuery = if (queryStart >= 0) ssp.substring(0, queryStart) else ssp
        val query = if (queryStart >= 0) ssp.substring(queryStart + 1) else ""

        val recipient = percentDecode(
            beforeQuery.removePrefix("//").trim(),
            plusIsSpace = false,
        )
        if (recipient.length > MAX_RECIPIENT_LENGTH) return null

        val queryBody = queryParameter(query, "body")
        val rawBody = queryBody ?: extraBody ?: ""
        val body = if (rawBody.length > MAX_BODY_LENGTH) {
            rawBody.substring(0, MAX_BODY_LENGTH)
        } else {
            rawBody
        }

        if (recipient.isEmpty() && body.isEmpty()) return null
        return Parsed(recipient = recipient, body = body)
    }

    internal fun queryParameter(query: String, name: String): String? {
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            val key = if (eq >= 0) part.substring(0, eq) else part
            if (percentDecode(key, plusIsSpace = true) != name) continue
            val value = if (eq >= 0) part.substring(eq + 1) else ""
            return percentDecode(value, plusIsSpace = true)
        }
        return null
    }

    /**
     * Percent-decode once. Query values treat `+` as space (same as
     * `Uri.getQueryParameter`). Recipients keep `+` so E.164 prefixes survive.
     * Invalid `%` sequences are left as written rather than throwing.
     */
    internal fun percentDecode(raw: String, plusIsSpace: Boolean): String {
        val bytes = ArrayList<Byte>(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '+' && plusIsSpace -> {
                    bytes.add(0x20)
                    i++
                }
                c == '%' && i + 2 < raw.length && isHex(raw[i + 1]) && isHex(raw[i + 2]) -> {
                    bytes.add(hexByte(raw[i + 1], raw[i + 2]))
                    i += 3
                }
                else -> {
                    val utf8 = c.toString().toByteArray(Charsets.UTF_8)
                    utf8.forEach { bytes.add(it) }
                    i++
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexByte(hi: Char, lo: Char): Byte =
        ((hexValue(hi) shl 4) or hexValue(lo)).toByte()

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }
}
