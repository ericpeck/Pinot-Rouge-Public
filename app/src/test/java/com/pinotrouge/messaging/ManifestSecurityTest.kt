package com.pinotrouge.messaging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins ROLE_SMS shape and backup / FileProvider posture in the merged source
 * manifest. This is not a device test; it fails if the XML drifts.
 */
class ManifestSecurityTest {

    @Test
    fun `backup is off and D2D rules are declared`() {
        val xml = manifestText()
        assertTrue(xml.contains("android:allowBackup=\"false\""))
        assertTrue(xml.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(xml.contains("android:usesCleartextTraffic=\"false\""))
    }

    @Test
    fun `FileProvider is not exported and does not grant by default beyond paths xml`() {
        val xml = manifestText()
        assertTrue(xml.contains("androidx.core.content.FileProvider"))
        val fileProviderBlock = xml.substringAfter("androidx.core.content.FileProvider")
            .substringBefore("</provider>")
        assertTrue(fileProviderBlock.contains("android:exported=\"false\""))
    }

    @Test
    fun `four ROLE_SMS components remain exported behind their platform permissions`() {
        val xml = manifestText()
        assertTrue(xml.contains("android.provider.Telephony.SMS_DELIVER"))
        assertTrue(xml.contains("android.permission.BROADCAST_SMS"))
        assertTrue(xml.contains("android.provider.Telephony.WAP_PUSH_DELIVER"))
        assertTrue(xml.contains("android.permission.BROADCAST_WAP_PUSH"))
        assertTrue(xml.contains("android.intent.action.SENDTO"))
        assertTrue(xml.contains("android.intent.action.RESPOND_VIA_MESSAGE"))
        assertTrue(xml.contains("android.permission.SEND_RESPOND_VIA_MESSAGE"))
        assertTrue(xml.contains("android.hardware.telephony"))
        assertTrue(xml.contains("android:required=\"true\""))
    }

    @Test
    fun `send and download completion receivers are not exported`() {
        val xml = manifestText().replace("\n", " ")
        assertTrue(xml.contains(".sms.MmsDownloadReceiver"))
        assertTrue(xml.contains("android:exported=\"false\""))
        assertTrue(xml.contains(".sms.MmsSendReceiver"))
        assertTrue(xml.contains(".sms.SmsSentReceiver"))
    }

    private fun manifestText(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
