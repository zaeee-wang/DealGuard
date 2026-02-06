package com.onguard.domain.model

/**
 * 스캠 분석 결과를 담는 데이터 클래스
 *
 * @property isScam 스캠 여부
 * @property confidence 신뢰도 (0.0 ~ 1.0)
 * @property reasons 탐지 이유 목록
 * @property detectedKeywords 탐지된 키워드 목록
 * @property detectionMethod 탐지에 사용된 방법
 * @property scamType 스캠 유형 (투자사기, 중고거래사기 등)
 * @property warningMessage 사용자에게 표시할 경고 메시지 (LLM 생성)
 * @property suspiciousParts 의심되는 문구 인용 목록
 */
data class ScamAnalysis(
    val isScam: Boolean,
    val confidence: Float,
    val reasons: List<String>,
    val detectedKeywords: List<String> = emptyList(),
    val detectionMethod: DetectionMethod = DetectionMethod.RULE_BASED,
    val scamType: ScamType = ScamType.UNKNOWN,
    val warningMessage: String? = null,
    val suspiciousParts: List<String> = emptyList()
)

/**
 * 스캠 탐지에 사용된 방식을 나타내는 열거형.
 *
 * - RULE_BASED: 키워드/정규식 기반 규칙 탐지
 * - ML_CLASSIFIER: ML 분류기 (미구현)
 * - HYBRID: 규칙 + URL 또는 규칙 + LLM 결합
 * - EXTERNAL_DB: 더치트/KISA 등 외부 DB 조회
 * - LLM: Sherpa-ONNX + 온디바이스 LLM(SmolLM2 등) 기반 탐지
 */
enum class DetectionMethod {
    RULE_BASED,
    ML_CLASSIFIER,
    HYBRID,
    EXTERNAL_DB,
    LLM
}

/**
 * 스캠 유형 분류.
 *
 * UI 경고 문구 및 통계 집계에 사용된다.
 */
enum class ScamType {
    /** 미분류 */
    UNKNOWN,
    /** 투자/코인/주식 사기 */
    INVESTMENT,
    /** 중고거래/선입금 사기 */
    USED_TRADE,
    /** 피싱 링크/사이트 */
    PHISHING,
    /** 기관/지인 사칭 */
    IMPERSONATION,
    /** 로맨스 스캠 */
    ROMANCE,
    /** 대출/선수수료 사기 */
    LOAN,
    /** 정상(스캠 아님) */
    SAFE
}
