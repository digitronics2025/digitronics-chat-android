package com.digitronics.chat.data

import android.content.Context
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
import com.digitronics.chat.net.ApiException
import com.digitronics.chat.net.SendMessageRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends one queued message. Retry-safe: every attempt reuses the same
 * `clientMessageId`, and the server answers a duplicate with the message it
 * already stored, so a retry after a lost response never posts twice.
 */
class SendMessageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ChatApplication).container
        val accountKey = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val clientMessageId = inputData.getString(KEY_CLIENT_ID) ?: return Result.failure()
        val account = container.session.awaitAccount()
        // Signed out or another person signed in: this job is not theirs to send.
        if (account == null || account.key != accountKey) return Result.failure()
        val row = container.chat.outgoingRow(accountKey, clientMessageId) ?: return Result.success()

        return try {
            val response = container.dmApi.sendMessage(row.conversationId, SendMessageRequest(row.body, clientMessageId))
            container.chat.confirmSent(accountKey, clientMessageId, response.message)
            Result.success()
        } catch (e: ApiException) {
            if (e.isTransient && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                container.chat.markOutgoingFailed(accountKey, clientMessageId, e.message)
                Result.failure()
            }
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                container.chat.markOutgoingFailed(accountKey, clientMessageId, null)
                Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_CLIENT_ID = "clientMessageId"
        private const val MAX_ATTEMPTS = 6
        const val TAG = "send-message"

        fun enqueue(context: Context, accountKey: String, clientMessageId: String) {
            val request = OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setInputData(workDataOf(KEY_ACCOUNT to accountKey, KEY_CLIENT_ID to clientMessageId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("send:$clientMessageId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
