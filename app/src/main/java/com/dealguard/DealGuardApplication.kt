package com.dealguard

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dealguard.detector.HybridScamDetector
import com.dealguard.worker.WorkManagerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DealGuard 애플리케이션 클래스
 *
 * Hilt DI 초기화, LLM 모델 사전 로딩, WorkManager 스케줄링을 담당합니다.
 */
@HiltAndroidApp
class DealGuardApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workManagerScheduler: WorkManagerScheduler

    @Inject
    lateinit var hybridScamDetector: HybridScamDetector

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "DealGuardApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DealGuard Application started")

        // LLM 모델 백그라운드 초기화 (Cold start 방지)
        initializeLLMInBackground()

        // WorkManager 초기화 및 주기적 업데이트 스케줄링
        initializeWorkManager()
    }

    /**
     * LLM 모델을 백그라운드에서 초기화
     * 앱 시작 시 미리 로드하여 첫 탐지 시 지연 최소화
     */
    private fun initializeLLMInBackground() {
        applicationScope.launch {
            try {
                Log.d(TAG, "Starting LLM initialization...")
                val success = hybridScamDetector.initializeLLM()

                if (success) {
                    Log.d(TAG, "LLM initialized successfully")
                } else {
                    Log.w(TAG, "LLM initialization failed - will use rule-based detection only")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during LLM initialization", e)
            }
        }
    }

    /**
     * WorkManager 초기화 및 피싱 DB 업데이트 스케줄링
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
