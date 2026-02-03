package com.onguard

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.onguard.detector.HybridScamDetector
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

    /** 하이브리드 스캠 탐지기 (Rule-based + LLM) */
    @Inject
    lateinit var hybridScamDetector: HybridScamDetector

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "OnGuardApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OnGuard Application started")

        // LLM 모델 백그라운드 초기화 (Cold start 방지)
        initializeLLMInBackground()

        // WorkManager 초기화 및 주기적 업데이트 스케줄링
        initializeWorkManager()
    }

    /**
     * LLM 모델을 백그라운드에서 초기화한다.
     *
     * 앱 시작 시 한 번 호출되며, 첫 스캠 탐지 시 지연을 줄이기 위해
     * 미리 Gemma 모델을 로드한다. 실패 시 Rule-based 탐지만 사용된다.
     * 
     * 크래시 방지: 네이티브 크래시가 발생해도 앱이 종료되지 않도록
     * SupervisorJob과 별도 코루틴으로 격리한다.
     */
    private fun initializeLLMInBackground() {
        // 별도 코루틴으로 격리하여 크래시가 앱 전체에 영향을 주지 않도록
        val llmInitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        llmInitScope.launch {
            try {
                Log.d(TAG, "=== Starting LLM initialization in background ===")
                
                // 앱 시작 안정화 + 다른 초기화(WorkManager 등) 완료 후 메모리 여유 확보
                kotlinx.coroutines.delay(1500)
                
                // LLM 로드 전 가비지 컬렉션으로 사용 가능 메모리 확보 (저사양 기기 대응)
                System.gc()
                kotlinx.coroutines.delay(200)
                
                val success = hybridScamDetector.initializeLLM()

                if (success) {
                    Log.i(TAG, "=== LLM initialized successfully ===")
                } else {
                    Log.w(TAG, "=== LLM initialization failed - will use rule-based detection only ===")
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "=== Out of Memory during LLM initialization ===", e)
                Log.e(TAG, "LLM initialization skipped due to memory constraints")
                // OutOfMemoryError는 앱을 종료시키지 않도록 처리
            } catch (e: Exception) {
                Log.e(TAG, "=== Exception during LLM initialization ===", e)
                Log.e(TAG, "  - Exception type: ${e.javaClass.name}")
                Log.e(TAG, "  - Exception message: ${e.message}")
                e.printStackTrace()
            } catch (e: Throwable) {
                // 네이티브 크래시 등 치명적 오류도 잡아서 앱 종료 방지
                Log.e(TAG, "=== Fatal error during LLM initialization ===", e)
                Log.e(TAG, "  - Error type: ${e.javaClass.name}")
                Log.e(TAG, "  - Error message: ${e.message}")
                Log.e(TAG, "LLM initialization skipped - app will continue with rule-based detection")
                e.printStackTrace()
            }
        }
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
