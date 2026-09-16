package com.pinotrouge.messaging.ui.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Resolve a phone number from a system contact-picker URI
 * ([ContactsContract.CommonDataKinds.Phone.CONTENT_URI] row).
 *
 * Shared by compose "To" and thread "Add people" — both use the platform
 * picker so we do not invent a contact UI (V3/V4 open question).
 */
fun resolvePhoneNumber(context: Context, contactUri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (idx < 0) return@use null
            cursor.getString(idx)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}
