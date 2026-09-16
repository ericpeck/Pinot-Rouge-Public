package com.pinotrouge.messaging.util

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.pinotrouge.messaging.data.telephony.SmsRepository

const val PINOT_PACKAGE = "com.pinotrouge.messaging"

private const val ROLE_SETTLE_TIMEOUT_MS = 15_000L
private const val ROLE_POLL_MS = 250L
private const val DRAIN_BUFFER_BYTES = 4_096

fun grantSmsRoleTo(packageName: String) {
    drainShell("cmd role add-role-holder android.app.role.SMS $packageName")
}

fun awaitRoleHeld(sms: SmsRepository, held: Boolean) {
    val deadline = SystemClock.elapsedRealtime() + ROLE_SETTLE_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        if (sms.isDefaultSmsApp() == held) return
        SystemClock.sleep(ROLE_POLL_MS)
    }
}

fun drainShell(command: String) {
    val pfd = InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .executeShellCommand(command)
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        while (input.read(buffer) != -1) {
            // Drain to EOF so the shell command actually finishes.
        }
    }
}
