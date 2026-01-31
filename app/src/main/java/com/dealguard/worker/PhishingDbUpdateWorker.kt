package com.dealguard.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dealguard.domain.repository.PhishingUrlRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PhishingDbUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val phishingUrlRepository: PhishingUrlRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "phishing_db_update"
        private const val TAG = "PhishingDbUpdateWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting phishing DB update")

        return try {
            // Clear old data and reload from CSV
            phishingUrlRepository.clearAll()

            val result = phishingUrlRepository.loadFromCsv()

            result.fold(
                onSuccess = { count ->
                    Log.i(TAG, "Successfully loaded $count phishing URLs")
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load phishing URLs", error)
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
