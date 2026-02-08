package com.onguard.detector

import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType
import com.onguard.util.DebugLog
import com.onguard.util.PiiMasker
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 하이브리드 스캠 탐지기.
 *
 * Rule-based([KeywordMatcher], [UrlAnalyzer], [PhoneAnalyzer], [AccountAnalyzer])와 LLM([ScamLlmClient]) 탐지를 결합하여
 * 정확도 높은 스캠 탐지를 수행한다.
 *
 * ## 탐지 흐름
 * 1. Rule-based 1차 필터 (키워드 + URL + 전화번호 + 계좌번호)
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
 * @param phoneAnalyzer 전화번호 위험도 분석기 (Counter Scam 112 DB)
 * @param accountAnalyzer 계좌번호 위험도 분석기 (경찰청 사기계좌 DB)
 * @param scamLlmClient LLM 기반 탐지기 (Gemini API 또는 온디바이스 Gemma)
 */
@Singleton
class HybridScamDetector @Inject constructor(
    private val keywordMatcher: KeywordMatcher,
    private val urlAnalyzer: UrlAnalyzer,
    private val phoneAnalyzer: PhoneAnalyzer,
    private val accountAnalyzer: AccountAnalyzer,
    private val scamLlmClient: ScamLlmClient
) {

    companion object {
        private const val TAG = "OnGuardHybrid"

        // 최종 스캠 판정 임계값: 결합된 신뢰도가 0.5를 넘으면 스캠으로 간주
        private const val FINAL_SCAM_THRESHOLD = 0.5f

        // LLM 트리거 구간 내 임계값 (0.4 ~ 0.7 구간은 중위험으로 관리)
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.4f

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

        // 3. Phone number analysis (Counter Scam 112 DB)
        val phoneResult = phoneAnalyzer.analyze(text)

        // 4. Account number analysis (Police Fraud DB)
        val accountResult = accountAnalyzer.analyze(text)

        // 6. Combine rule-based results
        val combinedReasons = mutableListOf<String>()
        combinedReasons.addAll(keywordResult.reasons)
        combinedReasons.addAll(urlResult.reasons)
        combinedReasons.addAll(phoneResult.reasons)
        combinedReasons.addAll(accountResult.reasons)

        // 7. Calculate rule-based confidence
        var ruleConfidence = keywordResult.confidence

        // URL 분석 결과 반영 (보너스 15% — Rule 점수 과다 방지)
        if (urlResult.suspiciousUrls.isNotEmpty()) {
            ruleConfidence = max(ruleConfidence, urlResult.riskScore)
            ruleConfidence += urlResult.riskScore * 0.15f
        }

        // 전화번호 분석 결과 반영 (DB 등록 15%, 의심 대역만 10%)
        if (phoneResult.hasScamPhones) {
            ruleConfidence = max(ruleConfidence, phoneResult.riskScore)
            ruleConfidence += phoneResult.riskScore * 0.15f
        } else if (phoneResult.isSuspiciousPrefix) {
            ruleConfidence += phoneResult.riskScore * 0.1f
        }

        // 계좌번호 분석 결과 반영 (보너스 15%)
        if (accountResult.hasFraudAccounts) {
            ruleConfidence = max(ruleConfidence, accountResult.riskScore)
            ruleConfidence += accountResult.riskScore * 0.15f
        }
        ruleConfidence = ruleConfidence.coerceIn(0f, 1f)

        DebugLog.debugLog(TAG) {
            "step=rule_result ruleConfidence=$ruleConfidence keywordReasons=${keywordResult.reasons.size} " +
                    "urlReasons=${urlResult.reasons.size} phoneReasons=${phoneResult.reasons.size} " +
                    "accountReasons=${accountResult.reasons.size} " +
                    "suspiciousUrlCount=${urlResult.suspiciousUrls.size} scamPhones=${phoneResult.scamPhones.size} " +
                    "fraudAccounts=${accountResult.fraudAccounts.size}"
        }

        // 8. Additional combination checks for medium confidence
        if (ruleConfidence > MEDIUM_CONFIDENCE_THRESHOLD) {
            val hasUrgency = text.contains("긴급", ignoreCase = true) ||
                    text.contains("급하", ignoreCase = true) ||
                    text.contains("빨리", ignoreCase = true)

            val hasMoney = text.contains("입금", ignoreCase = true) ||
                    text.contains("송금", ignoreCase = true) ||
                    text.contains("계좌", ignoreCase = true)

            // 스캠 황금 패턴: 긴급성 + 금전 요구 + URL (보너스 8%)
            if (hasUrgency && hasMoney && urlResult.urls.isNotEmpty()) {
                ruleConfidence += 0.08f
                combinedReasons.add("의심스러운 조합: 긴급 + 금전 + URL")
            }
        }
        // Rule 신뢰도 상한 0.65 — LLM 가중 평균(4:6)에서 LLM 기여도가 충분히 반영되도록
        ruleConfidence = ruleConfidence.coerceIn(0f, 0.65f)

        // 9. LLM 분석
        // - 룰 기반 결과 + 키워드/URL/전화번호 신호를 바탕으로 LLM에게 컨텍스트 설명/보조 신뢰도를 요청한다.
        val lowerText = text.lowercase()
        val hasMoneyKeyword = listOf("입금", "송금", "계좌", "선입금", "대출", "돈", "급전")
            .any { lowerText.contains(it) }
        val hasUrgencyKeyword = listOf("긴급", "급하", "빨리", "지금당장", "지금 바로", "오늘안에")
            .any { lowerText.contains(it) }
        val hasUrl = urlResult.urls.isNotEmpty()
        val hasScamPhone = phoneResult.hasScamPhones
        val hasScamAccount = accountResult.hasFraudAccounts

        val shouldUseLLM = useLLM &&
                scamLlmClient.isAvailable() &&
                ruleConfidence in LLM_TRIGGER_LOW..LLM_TRIGGER_HIGH &&
                (hasMoneyKeyword || hasUrl || hasUrgencyKeyword || hasScamPhone || hasScamAccount)

        if (shouldUseLLM) {
            DebugLog.debugLog(TAG) {
                "step=llm_trigger ruleConfidence=$ruleConfidence useLLM=$useLLM llmAvailable=${scamLlmClient.isAvailable()} " +
                        "hasMoneyKeyword=$hasMoneyKeyword hasUrgencyKeyword=$hasUrgencyKeyword hasUrl=$hasUrl " +
                        "hasScamPhone=$hasScamPhone hasScamAccount=$hasScamAccount"
            }

            // PII 마스킹 적용 (API 직전): 전화번호/계좌번호/주민번호 보호
            // - originalText, recentContext, currentMessage: PiiMasker.mask 적용
            // - ruleReasons: PhoneAnalyzer 등에서 raw 전화번호가 들어갈 수 있으므로 항목별 마스킹
            val request = ScamLlmRequest(
                originalText = PiiMasker.mask(text),
                recentContext = PiiMasker.mask(recentContext),
                currentMessage = PiiMasker.mask(currentMessage),
                ruleReasons = combinedReasons.map { PiiMasker.mask(it) },
                detectedKeywords = keywordResult.detectedKeywords
            )

            // LLM 분석 호출
            val llmResult = scamLlmClient.analyze(request)

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
        } else if (!scamLlmClient.isAvailable()) {
            DebugLog.warnLog(TAG) { "step=llm_fallback reason=llm_not_available ruleConfidence=$ruleConfidence" }
        } else {
            DebugLog.debugLog(TAG) {
                "step=llm_bypass reason=outside_trigger_window ruleConfidence=$ruleConfidence " +
                        "hasMoneyKeyword=$hasMoneyKeyword hasUrgencyKeyword=$hasUrgencyKeyword hasUrl=$hasUrl"
            }
        }

        // 10. Final rule-based result
        val hasExternalDbHit = urlResult.suspiciousUrls.isNotEmpty() ||
                phoneResult.hasScamPhones ||
                accountResult.hasFraudAccounts
        return createRuleBasedResult(
            ruleConfidence.coerceIn(0f, 1f),
            combinedReasons,
            keywordResult.detectedKeywords,
            hasExternalDbHit
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
        val scamType = inferScamType(reasons)

        // Rule-based 경고 메시지 생성
        val warningMessage = generateRuleBasedWarning(scamType, confidence)

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
    // Note: inferScamType() and generateRuleBasedWarning() removed
    // → Use ScamTypeInferrer.inferScamType() and RuleBasedWarningGenerator.generateWarning() instead
}
