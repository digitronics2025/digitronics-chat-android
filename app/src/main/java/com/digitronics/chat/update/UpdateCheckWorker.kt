package com.digitronics.chat.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.digitronics.chat.ChatApplication
import java.util.concurrent.TimeUnit

/**
 * Background update check: periodic (every 12 h, so at most twice a day) and
 * once on an `app_release_available` push hint. The push is only a hint — the
 * offer still has to verify before anything is shown.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ChatApplication).container
        container.session.awaitAccount() ?: return Result.success()
        val updates = container.updates
        if (!updates.isConfigured) return Result.success()
        return when (val state = updates.check()) {
            is UpdateState.Available -> {
                val version = state.manifest.versionCode
                if (container.prefs.lastNotifiedUpdateVersion() < version) {
                    container.notifier.showUpdateAvailable(state.manifest.versionName)
                    container.prefs.setLastNotifiedUpdateVersion(version)
                }
                Result.success()
            }
            UpdateState.Offline -> if (runAttemptCount < 3) Result.retry() else Result.success()
            is UpdateState.Failed -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        private const val KEY_FROM_PUSH = "fromPush"
        const val TAG = "update-check"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun enqueueOnce(context: Context, fromPush: Boolean) {
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setInputData(workDataOf(KEY_FROM_PUSH to fromPush))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("update-check-once", ExistingWorkPolicy.KEEP, request)
        }
    }
}
