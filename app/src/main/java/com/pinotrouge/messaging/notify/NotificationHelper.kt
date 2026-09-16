package com.pinotrouge.messaging.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.pinotrouge.messaging.MainActivity
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incoming-message notifications.
 *
 * **Channels** (created in [init] when Hilt first provides this singleton, and
 * again from [ensureChannels] before every post — call [ensureChannels] from
 * [com.pinotrouge.messaging.PinotRougeApp] if you need channels before any SMS):
 * - [CHANNEL_NORMAL] (`messages`): [NotificationManager.IMPORTANCE_HIGH] —
 *   ordinary incoming messages (heads-up + sound).
 * - [CHANNEL_SILENT] (`messages_silent`): [NotificationManager.IMPORTANCE_LOW] —
 *   `Action.SILENCE` and the weekly digest. Low importance is what actually
 *   suppresses sound/heads-up; `setSilent(true)` on a high channel does not.
 *
 * **Importance note:** Android freezes a channel's importance after first
 * creation. Installs that already created `messages` at IMPORTANCE_DEFAULT
 * keep that rank until the user changes it or reinstalls. New installs get HIGH.
 *
 * **Deep link:** content intent uses [threadDeepLinkUri] (`pinotrouge://thread/{id}`)
 * plus [EXTRA_THREAD_ID], built with [TaskStackBuilder] so back returns to the
 * launcher/root task. NavHost must register a matching deep link (or read the
 * extra) — see Log for the consumer wire-up.
 *
 * Held messages: **no** notification (caller must not invoke this).
 */
@Singleton
class NotificationHelper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val contactsRepository: ContactsRepository,
) {
    private val messageBuffer = ThreadMessageBuffer()

    init {
        ensureChannels()
    }

    /**
     * Post (or update) the notification for [sender]'s thread.
     *
     * Signature kept for [com.pinotrouge.messaging.sms.IncomingMessagePipeline].
     * Resolves display name and Telephony thread id on IO inside.
     */
    suspend fun notifyIncoming(sender: String, body: String, silent: Boolean) {
        ensureChannels()
        if (!canPostNotifications()) return

        val displayName = resolveDisplayName(sender)
        val threadId = resolveThreadId(sender)
        val timestamp = System.currentTimeMillis()
        val entries = messageBuffer.append(
            threadId,
            ThreadMessageBuffer.Entry(
                body = body,
                timestampMillis = timestamp,
                senderDisplayName = displayName,
            ),
        )

        val channelId = if (silent) CHANNEL_SILENT else CHANNEL_NORMAL
        val notificationId = NotificationIds.forThread(threadId)
        val pending = threadContentIntent(threadId)

        val me = Person.Builder()
            .setName(context.getString(R.string.app_name))
            .build()
        val senderPerson = Person.Builder()
            .setName(displayName)
            .setKey(sender)
            .build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(displayName)
            .setGroupConversation(false)
        for (entry in entries) {
            style.addMessage(
                entry.body,
                entry.timestampMillis,
                senderPerson,
            )
        }

        val latestBody = entries.lastOrNull()?.body ?: body
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(displayName)
            .setContentText(latestBody)
            .setStyle(style)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(entries.size > 1)
            .setGroup(NotificationIds.groupKey(threadId))
            .setNumber(entries.size)
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_HIGH,
            )
            .setSilent(silent)
            .setWhen(timestamp)
            .setShowWhen(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — message is still in the provider.
        }
    }

    /**
     * Cancel the notification for [threadId] and drop its MessagingStyle buffer.
     * Call from the thread screen when the user opens that conversation.
     */
    fun clearThread(threadId: Long) {
        messageBuffer.clear(threadId)
        NotificationManagerCompat.from(context)
            .cancel(NotificationIds.forThread(threadId))
    }

    /** Create both channels if missing. Safe to call repeatedly. */
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val normal = NotificationChannel(
            CHANNEL_NORMAL,
            context.getString(R.string.notif_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_messages_desc)
        }
        val silent = NotificationChannel(
            CHANNEL_SILENT,
            context.getString(R.string.notif_channel_silent),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_silent_desc)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(normal)
        manager.createNotificationChannel(silent)
    }

    private suspend fun resolveDisplayName(sender: String): String {
        if (sender.isBlank()) return context.getString(R.string.app_name)
        val contact = runCatching { contactsRepository.resolveDisplayName(sender) }.getOrNull()
        return contact?.takeIf { it.isNotBlank() } ?: sender
    }

    private fun resolveThreadId(sender: String): Long {
        if (sender.isBlank()) return 0L
        return runCatching {
            Telephony.Threads.getOrCreateThreadId(context, sender)
        }.getOrElse {
            // Not default SMS app / provider failure — still need a stable id.
            NotificationIds.forThread(sender.hashCode().toLong()).toLong()
        }
    }

    private fun threadContentIntent(threadId: Long): PendingIntent {
        val deepLink = threadDeepLinkUri(threadId)
        val openThread = Intent(Intent.ACTION_VIEW, deepLink, context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_THREAD_ID, threadId)
        }
        // Parent = plain launcher MainActivity so system back leaves the thread
        // for the app root (inbox) rather than exiting the task.
        val root = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = TaskStackBuilder.create(context)
            .addNextIntent(root)
            .addNextIntent(openThread)
            .getPendingIntent(
                NotificationIds.forThread(threadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // TaskStackBuilder always returns non-null for valid intents; fall back
        // to a plain activity PendingIntent if the stack builder fails.
        return pending ?: PendingIntent.getActivity(
            context,
            NotificationIds.forThread(threadId),
            openThread,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return granted
    }

    companion object {
        const val CHANNEL_NORMAL = "messages"
        const val CHANNEL_SILENT = "messages_silent"

        /** Long extra: open this Telephony thread when the notification is tapped. */
        const val EXTRA_THREAD_ID = "com.pinotrouge.messaging.extra.THREAD_ID"

        const val DEEP_LINK_SCHEME = "pinotrouge"
        const val DEEP_LINK_HOST_THREAD = "thread"
        const val DEEP_LINK_HOST_FILTERED = "filtered"

        fun threadDeepLinkUri(threadId: Long): Uri =
            Uri.Builder()
                .scheme(DEEP_LINK_SCHEME)
                .authority(DEEP_LINK_HOST_THREAD)
                .appendPath(threadId.toString())
                .build()

        fun threadDeepLinkPattern(): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST_THREAD/{threadId}"

        fun filteredDeepLinkUri(): Uri =
            Uri.Builder()
                .scheme(DEEP_LINK_SCHEME)
                .authority(DEEP_LINK_HOST_FILTERED)
                .build()

        fun filteredDeepLinkPattern(): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST_FILTERED"
    }
}
