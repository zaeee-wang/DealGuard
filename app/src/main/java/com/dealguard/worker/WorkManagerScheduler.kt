package com.dealguard.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WorkManagerScheduler"
        private const val PHISHING_DB_UPDATE_INTERVAL_DAYS = 7L
    }

    fun schedulePhishingDbUpdate() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<PhishingDbUpdateWorker>(
            PHISHING_DB_UPDATE_INTERVAL_DAYS, TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PhishingDbUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Log.i(TAG, "Scheduled periodic phishing DB update (every $PHISHING_DB_UPDATE_INTERVAL_DAYS days)")
    }

    fun runImmediateUpdate() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PhishingDbUpdateWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)

        Log.i(TAG, "Triggered immediate phishing DB update")
    }

    fun cancelPhishingDbUpdate() {
        WorkManager.getInstance(context).cancelUniqueWork(PhishingDbUpdateWorker.WORK_NAME)
        Log.i(TAG, "Cancelled phishing DB update work")
    }
}
