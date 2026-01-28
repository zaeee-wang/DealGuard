package com.dealguard

import android.app.Application
import android.util.Log
import com.dealguard.detector.HybridScamDetector
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DealGuard 애플리케이션 클래스
 *
 * Hilt DI 초기화 및 LLM 모델 사전 로딩을 담당합니다.
 */
@HiltAndroidApp
class DealGuardApplication : Application() {

    companion object {
        private const val TAG = "DealGuardApp"
    }

    @Inject
    lateinit var hybridScamDetector: HybridScamDetector

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // LLM 모델 백그라운드 초기화 (Cold start 방지)
        initializeLLMInBackground()
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
}
