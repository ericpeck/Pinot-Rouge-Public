package com.pinotrouge.messaging.sms

/**
 * Production log lines for MMS retrieve. Carrier Content-Location URLs can
 * carry message ids and retrieval tokens — never interpolate them, and do
 * not pass a throwable whose message may include the URL to [android.util.Log].
 */
object MmsDownloadLog {

    private val HTTP_URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    fun requested(mmsId: Long): String = "Requested MMS download op=$mmsId"

    fun failed(mmsId: Long, errorClass: String): String =
        "MMS download failed op=$mmsId error=$errorClass leaving 130 stub"

    fun redact(text: String): String = HTTP_URL.replace(text, "<redacted-url>")

    fun containsRetrievalUrl(text: String): Boolean = HTTP_URL.containsMatchIn(text)
}
