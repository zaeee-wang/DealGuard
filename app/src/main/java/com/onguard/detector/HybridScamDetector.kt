package com.onguard.detector

import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType
import com.onguard.util.DebugLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 하이브리드 스캠 탐지기.
 *
 * Rule-based([KeywordMatcher], [UrlAnalyzer])와 LLM([LLMScamDetector]) 탐지를 결합하여
 * 정확도 높은 스캠 탐지를 수행한다.
 *
 * ## 탐지 흐름
 * 1. Rule-based 1차 필터 (키워드 + URL)
 * 2. 최근 대화 맥락(마지막 N줄)을 추출해 LLM 컨텍스트로 활용
 * 3. 신뢰도 0.5~1.0 구간이면서 금전/긴급/URL 신호가 있을 때만 LLM 추가 분석
 * 4. 가중 평균(Rule 40%, LLM 60%)으로 최종 판정
 *
 * ## 임계값
 * - 0.7 초과: 고위험, 즉시 스캠 판정 (LLM 미호출)
 * - 0.4~0.7: 중위험, 조합 보너스 후 필요 시 LLM 호출
 * - 0.5~1.0: LLM 트리거 구간 (금전/긴급/URL 신호가 있을 때)
 * - 0.5 초과: 최종 스캠 판정
 *
 * @param keywordMatcher 키워드 기반 규칙 탐지기
 * @param urlAnalyzer URL 위험도 분석기
 * @param llmScamDetector LLM 기반 탐지기 (Gemini API)
 */
@Singleton
class HybridScamDetector @Inject constructor(
    private val keywordMatcher: KeywordMatcher,
    private val urlAnalyzer: UrlAnalyzer,
    private val llmScamDetector: LLMScamDetector
) {

    companion object {
        private const val TAG = "OnGuardHybrid"

        // 최종 스캠 판정 임계값: 결합된 신뢰도가 0.5를 넘으면 스캠으로 간주
        private const val FINAL_SCAM_THRESHOLD = 0.5f

        // 고위험 임계값: 70% 이상이면 즉시 스캠 판정
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.7f

        // 중위험 임계값: 40% 이상이면 추가 조합 분석 수행
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.4f
        private const val LOW_CONFIDENCE_THRESHOLD = 0.25f

        // LLM 분석 조건: Rule-based 결과가 애매한 경우(0.5~1.0) + 금전/긴급/URL 신호 존재
        private const val LLM_TRIGGER_LOW = 0.5f
        private const val LLM_TRIGGER_HIGH = 1.0f

        // 가중치
        private const val RULE_WEIGHT = 0.4f
        private const val LLM_WEIGHT = 0.6f
    }

    /**
     * 주어진 텍스트를 분석하여 스캠 여부와 상세 결과를 반환한다.
     *
     * @param text 분석할 채팅 메시지
     * @param useLLM true이면 애매한 구간에서 LLM 분석 시도, false이면 Rule-based만 사용
     * @return [ScamAnalysis] 최종 분석 결과 (스캠 여부, 신뢰도, 이유, 경고 메시지 등)
     */
    suspend fun analyze(text: String, useLLM: Boolean = true): ScamAnalysis {
        // 0. 최근 대화 맥락 추출 (마지막 N줄)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val recentLines = if (lines.size <= 10) lines else lines.takeLast(10)
        val recentContext = recentLines.joinToString("\n")
        val currentMessage = recentLines.lastOrNull().orEmpty()

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

        DebugLog.debugLog(TAG) {
            "step=rule_result ruleConfidence=$ruleConfidence keywordReasons=${keywordResult.reasons.size} urlReasons=${urlResult.reasons.size} suspiciousUrlCount=${urlResult.suspiciousUrls.size}"
        }

        // 5. Additional combination checks for medium confidence
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
        // 조합 보너스 추가 후에도 1.0 초과 방지
        ruleConfidence = ruleConfidence.coerceIn(0f, 1f)

        // 7. LLM 분석
        // - 룰 기반 결과 + 키워드/URL 신호를 바탕으로 LLM에게 컨텍스트 설명/보조 신뢰도를 요청한다.
        val lowerText = text.lowercase()
        val hasMoneyKeyword = listOf("입금", "송금", "계좌", "선입금", "대출", "돈", "급전")
            .any { lowerText.contains(it) }
        val hasUrgencyKeyword = listOf("긴급", "급하", "빨리", "지금당장", "지금 바로", "오늘안에")
            .any { lowerText.contains(it) }
        val hasUrl = urlResult.urls.isNotEmpty()

        val shouldUseLLM = useLLM &&
                llmScamDetector.isAvailable() &&
                ruleConfidence in LLM_TRIGGER_LOW..LLM_TRIGGER_HIGH &&
                (hasMoneyKeyword || hasUrl || hasUrgencyKeyword)

        if (shouldUseLLM) {
            DebugLog.debugLog(TAG) {
                "step=llm_trigger ruleConfidence=$ruleConfidence useLLM=$useLLM llmAvailable=${llmScamDetector.isAvailable()} " +
                        "hasMoneyKeyword=$hasMoneyKeyword hasUrgencyKeyword=$hasUrgencyKeyword hasUrl=$hasUrl"
            }

            val llmResult = llmScamDetector.analyze(
                originalText = text,
                recentContext = recentContext,
                currentMessage = currentMessage,
                ruleReasons = combinedReasons,
                detectedKeywords = keywordResult.detectedKeywords
            )

            if (llmResult != null) {
                return combineResults(
                    ruleConfidence = ruleConfidence,
                    ruleReasons = combinedReasons,
                    detectedKeywords = keywordResult.detectedKeywords,
                    llmResult = llmResult
                )
            } else {
                DebugLog.warnLog(TAG) { "step=llm_fallback reason=llm_result_null ruleConfidence=$ruleConfidence" }
            }
        } else if (!useLLM) {
            DebugLog.debugLog(TAG) { "step=llm_bypass reason=useLLM_false ruleConfidence=$ruleConfidence" }
        } else if (!llmScamDetector.isAvailable()) {
            DebugLog.warnLog(TAG) { "step=llm_fallback reason=llm_not_available ruleConfidence=$ruleConfidence" }
        } else {
            DebugLog.debugLog(TAG) {
                "step=llm_bypass reason=outside_trigger_window ruleConfidence=$ruleConfidence " +
                        "hasMoneyKeyword=$hasMoneyKeyword hasUrgencyKeyword=$hasUrgencyKeyword hasUrl=$hasUrl"
            }
        }

        // 7. Final rule-based result
        return createRuleBasedResult(
            ruleConfidence.coerceIn(0f, 1f),
            combinedReasons,
            keywordResult.detectedKeywords,
            urlResult.suspiciousUrls.isNotEmpty()
        )
    }

    /**
     * Rule-based 결과와 LLM 결과를 가중 평균으로 결합한다.
     *
     * @param ruleConfidence 규칙 기반 신뢰도
     * @param ruleReasons 규칙 기반 탐지 사유
     * @param detectedKeywords 탐지된 키워드 목록
     * @param llmResult LLM 분석 결과
     * @return 결합된 [ScamAnalysis]
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

        DebugLog.debugLog(TAG) {
            "step=combine rule=$ruleConfidence llm=${llmResult.confidence} final=$combinedConfidence isScam=${combinedConfidence > 0.5f || llmResult.isScam} scamType=${llmResult.scamType}"
        }

        return ScamAnalysis(
            isScam = combinedConfidence > FINAL_SCAM_THRESHOLD || llmResult.isScam,
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
     * Rule-based만으로 [ScamAnalysis]를 생성한다.
     *
     * @param confidence 신뢰도
     * @param reasons 탐지 사유 목록
     * @param detectedKeywords 탐지된 키워드
     * @param hasUrlIssues URL 이상 여부 (HYBRID vs RULE_BASED 구분용)
     * @return [ScamAnalysis]
     */
    private fun createRuleBasedResult(
        confidence: Float,
        reasons: List<String>,
        detectedKeywords: List<String>,
        hasUrlIssues: Boolean
    ): ScamAnalysis {
        // Rule-based에서 스캠 유형 추론
        val scamType = ScamTypeInferrer.inferScamType(reasons)

        // Rule-based 경고 메시지 생성
        val warningMessage = RuleBasedWarningGenerator.generateWarning(scamType, confidence)

        return ScamAnalysis(
            isScam = confidence > FINAL_SCAM_THRESHOLD,
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
     * 규칙 기반 사유 문자열에서 [ScamType]을 추론한다.
     *
     * @param reasons 탐지 사유 목록 (키워드/패턴 설명)
     * @return 추론된 [ScamType]
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
     * Rule-based 전용 경고 메시지를 생성한다.
     *
     * @param scamType 스캠 유형
     * @param confidence 신뢰도 (퍼센트 표시용)
     * @return 사용자에게 표시할 한글 경고 문구
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

}
