package com.digitronics.chat.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.digitronics.chat.MainActivity
import com.digitronics.chat.R

/**
 * Builds and posts every notification the app shows. Three channels:
 * Messages, Bot reports and App updates (plan §6.5). Chat notifications are
 * tagged by conversation (a newer message replaces the older one) and their
 * lock-screen form is PRIVATE with a generic public version.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val seen = context.getSharedPreferences("notified_messages", Context.MODE_PRIVATE)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_messages_description)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_BOTS, context.getString(R.string.channel_bots), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_bots_description)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_updates_description)
                },
            ),
        )
    }

    fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** Records [messageId]; false when it was already notified (FCM can deliver more than once). */
    @Synchronized
    fun claimMessage(messageId: String): Boolean {
        val ids = seen.getString(KEY_IDS, "").orEmpty().split(',').filter { it.isNotEmpty() }
        if (messageId in ids) return false
        val next = (ids + messageId).takeLast(MAX_REMEMBERED)
        seen.edit { putString(KEY_IDS, next.joinToString(",")) }
        return true
    }

    fun showMessage(conversationId: String, title: String, text: String, bot: Boolean) {
        val channel = if (bot) CHANNEL_BOTS else CHANNEL_MESSAGES
        val generic = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_generic_text))
            .build()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(ContextCompat.getColor(context, R.color.brand_teal))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(generic)
            .setAutoCancel(true)
            .setContentIntent(openConversation(conversationId))
            .build()
        post(tagFor(conversationId), ID_CHAT, notification)
    }

    fun cancelConversation(conversationId: String) = manager.cancel(tagFor(conversationId), ID_CHAT)

    fun showTest() {
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notification_test_title))
            .setContentText(context.getString(R.string.notification_test_text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openApp(null))
            .build()
        post(null, ID_TEST, notification)
    }

    fun showUpdateAvailable(versionName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notification_update_title))
            .setContentText(context.getString(R.string.notification_update_text, versionName))
            .setAutoCancel(true)
            .setContentIntent(openApp(MainActivity.DESTINATION_UPDATES))
            .build()
        post(null, ID_UPDATE, notification)
    }

    fun cancelAll() {
        manager.cancelAll()
        seen.edit { clear() }
    }

    private fun post(tag: String?, id: Int, notification: android.app.Notification) {
        if (!canPost()) return
        try {
            manager.notify(tag, id, notification)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post.
        }
    }

    private fun openConversation(conversationId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CONVERSATION)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(destination: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_DESTINATION)
            .putExtra(MainActivity.EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            (destination ?: "app").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun tagFor(conversationId: String) = "conversation:$conversationId"

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_BOTS = "bot_reports"
        const val CHANNEL_UPDATES = "app_updates"
        private const val ID_CHAT = 1001
        private const val ID_TEST = 1002
        private const val ID_UPDATE = 1003
        private const val KEY_IDS = "ids"
        private const val MAX_REMEMBERED = 200
    }
}
