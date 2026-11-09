package com.digitronics.chat

/** Which thread is on screen right now, so a push for it syncs instead of ringing. */
class ActiveThreadTracker {
    @Volatile var foreground: Boolean = false
    @Volatile var conversationId: String? = null

    fun isVisible(id: String): Boolean = foreground && conversationId == id
}
