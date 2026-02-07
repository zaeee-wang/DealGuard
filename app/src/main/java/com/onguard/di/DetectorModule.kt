package com.onguard.di

import com.onguard.device.DefaultOnDeviceEligibilityChecker
import com.onguard.device.OnDeviceEligibilityChecker
import com.onguard.detector.LLMScamDetector
import com.onguard.detector.OnDeviceScamDetector
import com.onguard.detector.ScamLlmClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 스캠 탐지 관련 DI 모듈.
 *
 * - [ScamLlmClient]: [OnDeviceEligibilityChecker.isOnDeviceEligible] 이 true이면 [OnDeviceScamDetector](Gemma),
 *   false이면 [LLMScamDetector](Gemini API)를 제공.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DetectorModule {

    @Binds
    @Singleton
    abstract fun bindOnDeviceEligibilityChecker(
        impl: DefaultOnDeviceEligibilityChecker
    ): OnDeviceEligibilityChecker

    companion object {
        @Provides
        @Singleton
        @JvmStatic
        fun provideScamLlmClient(
            onDeviceEligibilityChecker: OnDeviceEligibilityChecker,
            llmScamDetector: LLMScamDetector,
            onDeviceScamDetector: OnDeviceScamDetector
        ): ScamLlmClient {
            return if (onDeviceEligibilityChecker.isOnDeviceEligible()) {
                onDeviceScamDetector
            } else {
                llmScamDetector
            }
        }
    }
}
