package com.pinotrouge.messaging.data.telephony

/**
 * Identity of one PDU in a thread — SMS and MMS `_id` sequences are independent,
 * so a bare [Long] collides the moment both tables are shown together.
 *
 * One ref per message, not per part. Selection, delete and undo operate on this.
 *
 * ⚠️ Do not use this data class as a LazyColumn `key`. Compose requires
 * Bundle-saveable keys; [toLazyKey] is the saveable form (`sms:42` / `mms:42`).
 */
data class MessageRef(
    val kind: Kind,
    val id: Long,
) {
    enum class Kind { SMS, MMS }

    fun toLazyKey(): String = "${kind.name.lowercase()}:$id"

    companion object {
        fun sms(id: Long): MessageRef = MessageRef(Kind.SMS, id)
        fun mms(id: Long): MessageRef = MessageRef(Kind.MMS, id)
    }
}

/**
 * Thread delete is success only when both provider tables are empty for that
 * thread and neither delete threw. A silent no-op on one table must not
 * report [SmsRepository.WriteResult.Success].
 */
internal fun threadDeleteClearedBothTables(
    smsThrew: Boolean,
    mmsThrew: Boolean,
    smsRemaining: Int,
    mmsRemaining: Int,
): Boolean = !smsThrew && !mmsThrew && smsRemaining == 0 && mmsRemaining == 0
