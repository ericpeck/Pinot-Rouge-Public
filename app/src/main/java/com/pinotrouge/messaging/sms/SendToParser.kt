package com.pinotrouge.messaging.sms

import java.net.URLDecoder

/**
 * Parses `sms:` / `smsto:` / `mms:` / `mmsto:` compose requests.
 *
 * Kept free of Android types so unit tests can cover scheme allowlisting and
 * length caps without Robolectric. [SendToActivity] supplies URI pieces.
 *
 * Opaque `sms:555?body=Hi` and hierarchical `sms://555?body=Hi` both put the
 * query on [schemeSpecificPart]. Do not use `Uri.getQueryParameter` — it
 * throws on opaque URIs.
 */
object SendToParser {

    const val MAX_RECIPIENT_LENGTH = 64
    const val MAX_BODY_LENGTH = 10_000

    val ALLOWED_SCHEMES: Set<String> = setOf("sms", "smsto", "mms", "mmsto")

    data class Parsed(val recipient: String, val body: String)

    fun parse(
        scheme: String?,
        schemeSpecificPart: String?,
        extraBody: String? = null,
    ): Parsed? {
        val normalizedScheme = scheme?.lowercase() ?: return null
        if (normalizedScheme !in ALLOWED_SCHEMES) return null

        val ssp = schemeSpecificPart.orEmpty()
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
     * Percent-decode. Query values treat `+` as space (same as
     * `Uri.getQueryParameter`). Recipients keep `+` so E.164 prefixes survive.
     */
    internal fun percentDecode(raw: String, plusIsSpace: Boolean): String {
        val prepared = if (plusIsSpace) {
            raw.replace("+", "%20")
        } else {
            raw.replace("+", "%2B")
        }
        return try {
            URLDecoder.decode(prepared, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            raw
        }
    }
}
