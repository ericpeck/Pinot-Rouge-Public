package com.pinotrouge.messaging.sms

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class IncomingMessagePipelineLogTest {

    @Test
    fun startDownload_does_not_interpolate_content_location_into_logs() {
        val src = sourceFile().readText()
        assertFalse(
            "Content-Location URLs must not appear in production log lines",
            src.contains("Requested MMS download for \$location"),
        )
        assertFalse(src.contains("downloadMultimediaMessage failed; leaving 130 stub\", t"))
        assertFalse(
            src.contains("Log.e(TAG, \"downloadMultimediaMessage failed; leaving 130 stub\", t)"),
        )
    }

    private fun sourceFile(): File {
        val candidates = listOf(
            File("src/main/java/com/pinotrouge/messaging/sms/IncomingMessagePipeline.kt"),
            File("app/src/main/java/com/pinotrouge/messaging/sms/IncomingMessagePipeline.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("IncomingMessagePipeline.kt not found from ${File(".").absolutePath}")
    }
}
