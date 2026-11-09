package com.digitronics.chat.push

import android.content.Context
import android.util.Log
import com.digitronics.chat.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Firebase without the google-services plugin: the four client values come from
 * BuildConfig (local.properties / Gradle properties / environment at build time)
 * and default to empty. Empty → push is off and Settings says so.
 */
object PushConfig {
    val isConfigured: Boolean
        get() = listOf(
            BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_APP_ID,
            BuildConfig.FIREBASE_API_KEY,
            BuildConfig.FIREBASE_SENDER_ID,
        ).all { it.isNotBlank() }

    @Volatile private var initialized = false

    /** Initialises the default FirebaseApp by hand. Returns false when push is unavailable. */
    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (!isConfigured) return false
        if (initialized) return true
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            initialized = true
            true
        } catch (e: Exception) {
            Log.w("DigitronicsPush", "Firebase could not start: ${e.javaClass.simpleName}")
            false
        }
    }
}

suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}
