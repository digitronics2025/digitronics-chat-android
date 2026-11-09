package com.digitronics.chat.push

import android.util.Log
import com.digitronics.chat.ChatApplication
import com.digitronics.chat.R
import com.digitronics.chat.update.UpdateCheckWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FCM entry point. A push never carries message text (native-push.md): for
 * `dm.message` the app fetches the message with its own token and builds the
 * notification itself, so a signed-out or removed person sees nothing.
 */
class ChatMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Never log the token. The worker re-registers it if someone is signed in.
        PushRegistrationWorker.enqueue(applicationContext, force = true)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "dm.message" -> handleChat(data)
            "app_release_available" -> UpdateCheckWorker.enqueueOnce(applicationContext, fromPush = true)
            "test" -> (application as ChatApplication).container.notifier.showTest()
            else -> Log.i(TAG, "Ignored push of unknown type")
        }
    }

    private fun handleChat(data: Map<String, String>) {
        val conversationId = data["conversationId"] ?: return
        val messageId = data["messageId"] ?: return
        val container = (application as ChatApplication).container
        // onMessageReceived runs off the main thread with a ~20 s budget.
        runBlocking(Dispatchers.IO) {
            val account = container.session.awaitAccount() ?: return@runBlocking
            if (container.activeThread.isVisible(conversationId)) {
                runCatching { container.chat.syncThread(account, conversationId) }
                return@runBlocking
            }
            val plan = withTimeoutOrNull(FETCH_BUDGET_MS) {
                runCatching { container.chat.syncThread(account, conversationId) }
                runCatching { container.chat.refreshConversations(account) }
                val stored = container.chat.message(account, messageId)
                if (stored != null && stored.senderId == account.userId) return@withTimeoutOrNull null
                val conversation = container.chat.conversationOnce(account, conversationId)
                val bot = data["system"] == "true" || stored?.messageType == "system" || conversation?.isBot == true
                val title = when {
                    conversation?.isGroup == true -> conversation.title
                    stored?.senderName != null -> stored.senderName
                    conversation != null -> conversation.title
                    else -> data["title"] ?: getString(R.string.app_name)
                }
                val text = when {
                    stored == null -> getString(R.string.notification_generic_text)
                    stored.messageType == "system" -> stored.systemTitle ?: stored.body
                    conversation?.isGroup == true && stored.senderName != null -> "${stored.senderName}: ${stored.body}"
                    stored.body.isNotBlank() -> stored.body
                    else -> getString(R.string.notification_generic_text)
                }
                Plan(title, text.take(MAX_TEXT), bot)
            } ?: if (container.chat.message(account, messageId)?.senderId == account.userId) {
                null
            } else {
                // Too slow to fetch: still say something arrived, without content.
                Plan(data["title"] ?: getString(R.string.app_name), getString(R.string.notification_generic_text), data["system"] == "true")
            }
            if (plan != null && container.notifier.claimMessage(messageId)) {
                container.notifier.showMessage(conversationId, plan.title, plan.text, plan.bot)
            }
        }
    }

    private data class Plan(val title: String, val text: String, val bot: Boolean)

    private companion object {
        const val TAG = "DigitronicsPush"
        const val FETCH_BUDGET_MS = 15_000L
        const val MAX_TEXT = 300
    }
}
