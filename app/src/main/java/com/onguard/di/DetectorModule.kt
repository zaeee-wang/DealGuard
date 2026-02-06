package com.onguard.di

import com.onguard.detector.LLMScamDetector
import com.onguard.detector.ScamLlmClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 스캠 탐지 관련 DI 모듈.
 *
 * - LLMScamDetector: Gemini 2.5 Flash API 기반 LLM 분석기
 * - ScamLlmClient: LLM 클라이언트 추상화 인터페이스
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DetectorModule {

    /**
     * ScamLlmClient 인터페이스에 LLMScamDetector 구현체를 바인딩.
     *
     * 향후 다른 LLM Provider (서버 프록시, 다른 API 등)로 교체 시
     * 이 바인딩만 변경하면 됨.
     */
    @Binds
    @Singleton
    abstract fun bindScamLlmClient(impl: LLMScamDetector): ScamLlmClient
}
