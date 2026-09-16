package com.pinotrouge.messaging.notify

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory recent messages per thread for [androidx.core.app.NotificationCompat.MessagingStyle].
 *
 * Lost on process death — the next notification for that thread starts a fresh style
 * with a single message, which is still one notification (stable id).
 */
internal class ThreadMessageBuffer(
    private val maxMessages: Int = MAX_MESSAGES,
) {
    data class Entry(
        val body: String,
        val timestampMillis: Long,
        val senderDisplayName: String,
    )

    private val byThread = ConcurrentHashMap<Long, List<Entry>>()

    fun append(threadId: Long, entry: Entry): List<Entry> {
        val next = synchronized(byThread) {
            val current = byThread[threadId].orEmpty()
            val merged = (current + entry).takeLast(maxMessages)
            byThread[threadId] = merged
            merged
        }
        return next
    }

    fun clear(threadId: Long) {
        byThread.remove(threadId)
    }

    fun snapshot(threadId: Long): List<Entry> = byThread[threadId].orEmpty()

    companion object {
        const val MAX_MESSAGES = 5
    }
}
