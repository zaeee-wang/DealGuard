package com.onguard.di

import com.onguard.detector.HybridScamDetectorConfig
import com.onguard.detector.LLMScamAnalyzer
import com.onguard.detector.LLMScamDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DetectorModule {

    @Binds
    @Singleton
    abstract fun bindLLMScamAnalyzer(impl: LLMScamDetector): LLMScamAnalyzer

    companion object {
        @Provides
        @Singleton
        @JvmStatic
        fun provideHybridScamDetectorConfig(): HybridScamDetectorConfig =
            HybridScamDetectorConfig.Default
    }
}
