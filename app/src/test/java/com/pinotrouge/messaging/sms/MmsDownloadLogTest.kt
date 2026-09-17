package com.pinotrouge.messaging.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsDownloadLogTest {

    @Test
    fun requested_line_uses_local_op_id_not_the_url() {
        val location = "https://mmsc.carrier.example/retrieve?id=msg-99&token=sekrit"
        val line = MmsDownloadLog.requested(mmsId = 42L)
        assertEquals("Requested MMS download op=42", line)
        assertFalse(MmsDownloadLog.containsRetrievalUrl(line))
        assertFalse(line.contains(location))
        assertFalse(line.contains("sekrit"))
    }

    @Test
    fun failed_line_uses_error_class_not_throwable_message() {
        val thrown = RuntimeException(
            "download failed https://mmsc.carrier.example/n?token=sekrit",
        )
        val line = MmsDownloadLog.failed(7L, thrown.javaClass.simpleName)
        assertEquals("MMS download failed op=7 error=RuntimeException leaving 130 stub", line)
        assertFalse(MmsDownloadLog.containsRetrievalUrl(line))
        assertFalse(line.contains("sekrit"))
    }

    @Test
    fun redact_strips_http_urls_from_exception_text() {
        val raw = "Failed https://mmsc.example/pdu?token=abc123 extra"
        assertEquals("Failed <redacted-url> extra", MmsDownloadLog.redact(raw))
        assertTrue(MmsDownloadLog.containsRetrievalUrl(raw))
    }
}
