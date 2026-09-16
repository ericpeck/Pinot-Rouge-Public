package com.pinotrouge.messaging.notify

/**
 * Stable notification id / group key helpers.
 *
 * Same thread must always map to the same id so MessagingStyle updates
 * coalesce instead of stacking duplicate notifications.
 */
object NotificationIds {

    /** NotificationManager id for a conversation. Stable across process restarts. */
    fun forThread(threadId: Long): Int {
        // Keep positive; fold high bits so large Telephony thread ids stay in int range.
        val folded = (threadId xor (threadId ushr 32)).toInt()
        return if (folded == Int.MIN_VALUE) 1 else kotlin.math.abs(folded).coerceAtLeast(1)
    }

    fun groupKey(threadId: Long): String = "thread_$threadId"
}
