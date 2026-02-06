package com.onguard.detector

import com.onguard.domain.model.ScamAnalysis

/**
 * LLM 기반 텍스트 스캠 분석기 계약.
 *
 * @deprecated 이 인터페이스는 llama.cpp 기반 LLM용으로 설계됨.
 * 현재는 Gemini API를 사용하는 [LLMScamDetector]가 [ScamLlmClient] 인터페이스를 구현함.
 * 오프라인 LLM 지원이 필요한 경우 이 인터페이스를 다시 활성화할 수 있음.
 */
@Deprecated("Use ScamLlmClient interface instead for Gemini API integration")
interface LLMScamAnalyzer {

    suspend fun initialize(): Boolean
    fun isAvailable(): Boolean
    suspend fun analyze(text: String): ScamAnalysis?
    fun close()
}
