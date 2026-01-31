package com.dealguard.detector

import android.util.Log
import com.dealguard.domain.model.DetectionMethod
import com.dealguard.domain.model.ScamAnalysis
import com.dealguard.domain.model.ScamType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 하이브리드 스캠 탐지기
 *
 * Rule-based (KeywordMatcher, UrlAnalyzer)와 LLM 기반 탐지를 결합하여
 * 정확도 높은 스캠 탐지를 수행합니다.
 *
 * 탐지 흐름:
 * 1. Rule-based 1차 필터 (빠름)
 * 2. 애매한 경우 LLM 추가 분석 (정확함)
 * 3. 결과 결합 및 최종 판정
 *
 * 신뢰도 계산:
 * - 기본: max(키워드 신뢰도, URL 위험도)
 * - URL 보너스: +30% (의심 URL 존재 시)
 * - 조합 보너스: +15% (긴급성 + 금전 + URL 조합)
 *
 * 임계값:
 * - 0.7 이상: 고위험 (즉시 스캠 판정)
 * - 0.4~0.7: 중위험 (LLM 분석 또는 추가 조합 분석)
 * - 0.3~0.4: 저위험 (LLM 분석 트리거)
 * - 0.5 이상: 최종 스캠 판정
 */
@Singleton
class HybridScamDetector @Inject constructor(
    private val keywordMatcher: KeywordMatcher,
    private val urlAnalyzer: UrlAnalyzer,
    private val llmScamDetector: LLMScamDetector
) {

    companion object {
        private const val TAG = "HybridScamDetector"

        // 고위험 임계값: 70% 이상이면 즉시 스캠 판정
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.7f

        // 중위험 임계값: 40% 이상이면 추가 조합 분석 수행
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.4f
        private const val LOW_CONFIDENCE_THRESHOLD = 0.3f

        // LLM 분석 조건: Rule-based 결과가 애매한 경우
        private const val LLM_TRIGGER_LOW = 0.3f
        private const val LLM_TRIGGER_HIGH = 0.7f

        // 가중치
        private const val RULE_WEIGHT = 0.4f
        private const val LLM_WEIGHT = 0.6f
    }

    /**
     * LLM 모델 초기화
     * Application 시작 시 호출 권장
     */
    suspend fun initializeLLM(): Boolean {
        return llmScamDetector.initialize()
    }

    /**
     * LLM 사용 가능 여부
     */
    fun isLLMAvailable(): Boolean = llmScamDetector.isAvailable()

    /**
     * 텍스트 분석 및 스캠 탐지
     *
     * @param text 분석할 채팅 메시지
     * @param useLLM LLM 사용 여부 (기본값: 자동 판단)
     * @return ScamAnalysis 분석 결과
     */
    suspend fun analyze(text: String, useLLM: Boolean = true): ScamAnalysis {
        // 1. Rule-based keyword detection (fast)
        val keywordResult = keywordMatcher.analyze(text)

        // 2. URL analysis
        val urlResult = urlAnalyzer.analyze(text)

        // 3. Combine rule-based results
        val combinedReasons = mutableListOf<String>()
        combinedReasons.addAll(keywordResult.reasons)
        combinedReasons.addAll(urlResult.reasons)

        // 4. Calculate rule-based confidence
        var ruleConfidence = keywordResult.confidence

        // URL 분석 결과 반영
        // - 의심 URL이 있으면 최소한 URL 위험도만큼 신뢰도 보장
        // - 추가로 URL 위험도의 30%를 보너스로 부여
        // - 이유: URL이 포함된 스캠은 위험도가 높음 (피싱 링크 가능성)
        if (urlResult.suspiciousUrls.isNotEmpty()) {
            ruleConfidence = max(ruleConfidence, urlResult.riskScore)
            ruleConfidence += urlResult.riskScore * 0.3f
        }
        ruleConfidence = ruleConfidence.coerceIn(0f, 1f)

        // 5. Early return for very high confidence (명확한 스캠)
        if (ruleConfidence > HIGH_CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "High confidence rule-based detection: $ruleConfidence")
            return createRuleBasedResult(
                ruleConfidence,
                combinedReasons,
                keywordResult.detectedKeywords,
                urlResult.suspiciousUrls.isNotEmpty()
            )
        }

        // 6. Additional combination checks for medium confidence
        if (ruleConfidence > MEDIUM_CONFIDENCE_THRESHOLD) {
            val hasUrgency = text.contains("긴급", ignoreCase = true) ||
                    text.contains("급하", ignoreCase = true) ||
                    text.contains("빨리", ignoreCase = true)

            val hasMoney = text.contains("입금", ignoreCase = true) ||
                    text.contains("송금", ignoreCase = true) ||
                    text.contains("계좌", ignoreCase = true)

            // 스캠 황금 패턴: 긴급성 + 금전 요구 + URL
            // - 전형적인 피싱 패턴으로 추가 15% 보너스
            // - 예: "급하게 이 링크로 입금해주세요"
            if (hasUrgency && hasMoney && urlResult.urls.isNotEmpty()) {
                ruleConfidence += 0.15f
                combinedReasons.add("의심스러운 조합: 긴급 + 금전 + URL")
            }
        }

        // 7. LLM 분석 (애매한 경우에만)
        if (useLLM && llmScamDetector.isAvailable() &&
            ruleConfidence in LLM_TRIGGER_LOW..LLM_TRIGGER_HIGH
        ) {
            Log.d(TAG, "Triggering LLM analysis for confidence: $ruleConfidence")

            val llmResult = llmScamDetector.analyze(text)

            if (llmResult != null) {
                return combineResults(
                    ruleConfidence = ruleConfidence,
                    ruleReasons = combinedReasons,
                    detectedKeywords = keywordResult.detectedKeywords,
                    llmResult = llmResult
                )
            }
        }

        // 8. Final rule-based result
        return createRuleBasedResult(
            ruleConfidence.coerceIn(0f, 1f),
            combinedReasons,
            keywordResult.detectedKeywords,
            urlResult.suspiciousUrls.isNotEmpty()
        )
    }

    /**
     * Rule-based 결과와 LLM 결과 결합
     */
    private fun combineResults(
        ruleConfidence: Float,
        ruleReasons: List<String>,
        detectedKeywords: List<String>,
        llmResult: ScamAnalysis
    ): ScamAnalysis {
        // 가중 평균으로 최종 신뢰도 계산
        val combinedConfidence = (ruleConfidence * RULE_WEIGHT + llmResult.confidence * LLM_WEIGHT)
            .coerceIn(0f, 1f)

        // 이유 목록 결합 (중복 제거)
        val allReasons = (ruleReasons + llmResult.reasons).distinct()

        Log.d(TAG, "Combined result - Rule: $ruleConfidence, LLM: ${llmResult.confidence}, Final: $combinedConfidence")

        return ScamAnalysis(
            isScam = combinedConfidence > 0.5f || llmResult.isScam,
            confidence = combinedConfidence,
            reasons = allReasons,
            detectedKeywords = detectedKeywords,
            detectionMethod = DetectionMethod.HYBRID,
            scamType = llmResult.scamType,
            warningMessage = llmResult.warningMessage,
            suspiciousParts = llmResult.suspiciousParts
        )
    }

    /**
     * Rule-based 결과 생성
     */
    private fun createRuleBasedResult(
        confidence: Float,
        reasons: List<String>,
        detectedKeywords: List<String>,
        hasUrlIssues: Boolean
    ): ScamAnalysis {
        // Rule-based에서 스캠 유형 추론
        val scamType = inferScamType(reasons)

        // Rule-based 경고 메시지 생성
        val warningMessage = generateRuleBasedWarning(scamType, confidence)

        return ScamAnalysis(
            isScam = confidence > 0.5f,
            confidence = confidence,
            reasons = reasons,
            detectedKeywords = detectedKeywords,
            detectionMethod = if (hasUrlIssues) DetectionMethod.HYBRID else DetectionMethod.RULE_BASED,
            scamType = scamType,
            warningMessage = warningMessage,
            suspiciousParts = detectedKeywords.take(3)  // 상위 3개 키워드
        )
    }

    /**
     * Rule-based 결과에서 스캠 유형 추론
     */
    private fun inferScamType(reasons: List<String>): ScamType {
        val reasonText = reasons.joinToString(" ")

        return when {
            reasonText.contains("투자") || reasonText.contains("수익") ||
                    reasonText.contains("코인") || reasonText.contains("주식") -> ScamType.INVESTMENT

            reasonText.contains("입금") || reasonText.contains("선결제") ||
                    reasonText.contains("거래") || reasonText.contains("택배") -> ScamType.USED_TRADE

            reasonText.contains("URL") || reasonText.contains("링크") ||
                    reasonText.contains("피싱") -> ScamType.PHISHING

            reasonText.contains("사칭") || reasonText.contains("기관") -> ScamType.IMPERSONATION

            reasonText.contains("대출") -> ScamType.LOAN

            else -> ScamType.UNKNOWN
        }
    }

    /**
     * Rule-based 경고 메시지 생성
     */
    private fun generateRuleBasedWarning(scamType: ScamType, confidence: Float): String {
        val confidencePercent = (confidence * 100).toInt()

        return when (scamType) {
            ScamType.INVESTMENT ->
                "이 메시지는 투자 사기로 의심됩니다 (위험도 $confidencePercent%). 고수익을 보장하는 투자는 대부분 사기입니다."

            ScamType.USED_TRADE ->
                "중고거래 사기가 의심됩니다 (위험도 $confidencePercent%). 선입금을 요구하면 직거래로 진행하세요."

            ScamType.PHISHING ->
                "피싱 링크가 포함되어 있습니다 (위험도 $confidencePercent%). 의심스러운 링크를 클릭하지 마세요."

            ScamType.IMPERSONATION ->
                "사칭 사기가 의심됩니다 (위험도 $confidencePercent%). 공식 채널을 통해 확인하세요."

            ScamType.LOAN ->
                "대출 사기가 의심됩니다 (위험도 $confidencePercent%). 선수수료 요구는 불법입니다."

            else ->
                "사기 의심 메시지입니다 (위험도 $confidencePercent%). 주의하세요."
        }
    }

    /**
     * 리소스 해제
     */
    fun close() {
        llmScamDetector.close()
    }
}
