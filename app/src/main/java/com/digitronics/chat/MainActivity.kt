package com.digitronics.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.digitronics.chat.ui.AppRoot

/**
 * The single activity. It also receives the sign-in hand-back
 * (`https://messenger.digitronics.app/android-auth/callback`, via the verified
 * App Link or the server's package-pinned intent://) and notification taps.
 */
class MainActivity : ComponentActivity() {
    private val container get() = (application as ChatApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) consume(intent)
        setContent { AppRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consume(intent)
    }

    /** Handles an intent once; the code-bearing URL is then dropped so a rotation cannot replay it. */
    private fun consume(intent: Intent?) {
        container.handleIntent(intent)
        setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val ACTION_OPEN_CONVERSATION = "com.digitronics.chat.OPEN_CONVERSATION"
        const val ACTION_OPEN_DESTINATION = "com.digitronics.chat.OPEN_DESTINATION"
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_UPDATES = "updates"
    }
}
