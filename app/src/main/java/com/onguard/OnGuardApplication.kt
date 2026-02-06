package com.onguard

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.onguard.worker.WorkManagerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * OnGuard 애플리케이션 진입점.
 *
 * Hilt DI 초기화, LLM 모델 사전 로딩, WorkManager 스케줄링을 담당한다.
 * [Configuration.Provider]를 구현하여 Hilt 기반 WorkManager 초기화를 제공한다.
 *
 * @see HybridScamDetector LLM 초기화 대상
 * @see WorkManagerScheduler 피싱 DB 주기 업데이트 스케줄링
 */
@HiltAndroidApp
class OnGuardApplication : Application(), Configuration.Provider {

    /** WorkManager Hilt Worker 팩토리 */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** 피싱 DB 업데이트 등 WorkManager 작업 스케줄러 */
    @Inject
    lateinit var workManagerScheduler: WorkManagerScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "OnGuardApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OnGuard Application started")

        // WorkManager 초기화 및 주기적 업데이트 스케줄링
        initializeWorkManager()
    }


    /**
     * WorkManager를 초기화하고 피싱 DB 주기 업데이트를 스케줄링한다.
     *
     * [WorkManagerScheduler.schedulePhishingDbUpdate]로 주기 작업을 등록하고,
     * [WorkManagerScheduler.runImmediateUpdate]로 첫 실행 시 즉시 한 번 업데이트한다.
     */
    private fun initializeWorkManager() {
        applicationScope.launch {
            try {
                // Schedule periodic phishing DB updates
                workManagerScheduler.schedulePhishingDbUpdate()

                // Run immediate update on first launch
                workManagerScheduler.runImmediateUpdate()

                Log.i(TAG, "WorkManager scheduled successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WorkManager", e)
            }
        }
    }

    /** WorkManager 설정 (Hilt Worker 팩토리, 로그 레벨) */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
