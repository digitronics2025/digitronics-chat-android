package com.digitronics.chat

import android.app.Application
import com.digitronics.chat.push.PushConfig

class ChatApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        PushConfig.ensureInitialized(this)
        container.start()
    }
}
