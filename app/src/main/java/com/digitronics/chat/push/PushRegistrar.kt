package com.digitronics.chat.push

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.digitronics.chat.ChatApplication
import com.digitronics.chat.data.AppPreferences
import com.digitronics.chat.net.ApiException
import com.digitronics.chat.net.MobileApi
import com.digitronics.chat.net.PushRegistrationRequest
import com.digitronics.chat.util.sha256Hex
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the server's copy of this device's FCM token and notification switch
 * current (`PUT /api/v1/mobile/devices/current/push`). It only talks to the
 * server when the token or the switch changed since the last success.
 */
class PushRegistrar(
    private val context: Context,
    private val prefs: AppPreferences,
    private val mobileApi: MobileApi,
) {
    suspend fun sync(force: Boolean) {
        if (!PushConfig.ensureInitialized(context)) return
        val token = FirebaseMessaging.getInstance().token.awaitResult()
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val fingerprint = sha256Hex("$token|$enabled")
        if (!force && fingerprint == prefs.pushRegistrationFingerprint()) return
        mobileApi.registerPush(PushRegistrationRequest(token = token, notificationsEnabled = enabled))
        prefs.setPushRegistrationFingerprint(fingerprint)
    }

    /** On sign-out: drop the local FCM token so this install stops being addressable. */
    suspend fun forgetToken() {
        if (!PushConfig.ensureInitialized(context)) return
        runCatching { FirebaseMessaging.getInstance().deleteToken().awaitResult() }
    }
}

class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ChatApplication).container
        container.session.awaitAccount() ?: return Result.success()
        return try {
            container.pushRegistrar.sync(force = inputData.getBoolean(KEY_FORCE, false))
            Result.success()
        } catch (e: ApiException) {
            if (e.isTransient && runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: Exception) {
            // Play services missing or Firebase refused: nothing to retry from here.
            Result.failure()
        }
    }

    companion object {
        private const val KEY_FORCE = "force"
        const val TAG = "push-registration"

        fun enqueue(context: Context, force: Boolean) {
            if (!PushConfig.isConfigured) return
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setInputData(workDataOf(KEY_FORCE to force))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("push-registration", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
