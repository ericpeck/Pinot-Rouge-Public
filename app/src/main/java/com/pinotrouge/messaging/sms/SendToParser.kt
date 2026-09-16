package com.pinotrouge.messaging.sms

/**
 * Parses `sms:` / `smsto:` / `mms:` / `mmsto:` compose requests.
 *
 * Kept free of Android types so unit tests can cover scheme allowlisting and
 * length caps without Robolectric. [SendToActivity] supplies URI pieces.
 */
object SendToParser {

    const val MAX_RECIPIENT_LENGTH = 64
    const val MAX_BODY_LENGTH = 10_000

    val ALLOWED_SCHEMES: Set<String> = setOf("sms", "smsto", "mms", "mmsto")

    data class Parsed(val recipient: String, val body: String)

    fun parse(
        scheme: String?,
        schemeSpecificPart: String?,
        queryBody: String?,
        extraBody: String?,
    ): Parsed? {
        val normalizedScheme = scheme?.lowercase() ?: return null
        if (normalizedScheme !in ALLOWED_SCHEMES) return null

        val recipient = schemeSpecificPart
            ?.substringBefore('?')
            ?.removePrefix("//")
            ?.trim()
            .orEmpty()
        if (recipient.length > MAX_RECIPIENT_LENGTH) return null

        val rawBody = queryBody ?: extraBody ?: ""
        val body = if (rawBody.length > MAX_BODY_LENGTH) {
            rawBody.substring(0, MAX_BODY_LENGTH)
        } else {
            rawBody
        }

        if (recipient.isEmpty() && body.isEmpty()) return null
        return Parsed(recipient = recipient, body = body)
    }
}
