package com.onguard.detector

import com.onguard.domain.model.ScamAnalysis

/**
 * 스캠 탐지용 LLM 클라이언트 추상화.
 *
 * - 현재는 [LLMScamDetector]가 Google Gemini 1.5 Flash 를 직접 호출한다.
 * - 향후에는 서버 프록시(BackendScamLlmClient)나 다른 LLM Provider로 교체할 수 있다.
 */
interface ScamLlmClient {

    /**
     * LLM 사용 가능 여부를 반환한다.
     * false이면 HybridScamDetector가 rule-only 또는 API로 폴백한다.
     */
    fun isAvailable(): Boolean

    /**
     * 스캠 분석 요청을 수행한다.
     *
     * @param request LLM 분석에 필요한 전체 컨텍스트
     * @return LLM 기반 스캠 분석 결과, 실패 시 null
     */
    suspend fun analyze(request: ScamLlmRequest): ScamAnalysis?
}

/**
 * LLM 스캠 분석 요청 모델.
 *
 * - recentContext: 최근 대화 줄들을 합친 문자열
 * - currentMessage: 현재(마지막) 메시지
 * - ruleReasons: 룰 기반 탐지 사유
 * - detectedKeywords: 탐지된 키워드
 * - explanationOnlyMode: true일 때는 점수/판정 대신 warningMessage만 생성 (Rule-only용)
 * - ruleConfidence: explanationOnlyMode=true일 때 사용 (LLM에 점수 참고용)
 * - ruleScamType: explanationOnlyMode=true일 때 사용 (LLM에 유형 참고용)
 */
data class ScamLlmRequest(
    val originalText: String,
    val recentContext: String,
    val currentMessage: String,
    val ruleReasons: List<String>,
    val detectedKeywords: List<String>,
    val explanationOnlyMode: Boolean = false,
    val ruleConfidence: Float = 0f,
    val ruleScamType: String = "UNKNOWN"
)