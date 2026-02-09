package com.onguard.detector

import com.onguard.domain.model.AccountAnalysisResult
import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.PhoneAnalysisResult
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType
import com.onguard.detector.UrlAnalyzer.UrlAnalysisResult
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
 * ## 탐지 흐름 (3-Path 분기 구조)
 * 1. Rule-based 1차 필터 (키워드 + URL + 전화번호 + 계좌번호)
 * 2. **강한 신호** 체크:
 *    - Counter Scam 112 DB 전화번호 히트
 *    - KISA 악성 URL 히트
 *    - 경찰청 사기계좌 DB 계좌번호 히트
 *    - 황금 패턴 (긴급성 + 금전 + URL)
 *    → 강한 신호 탐지 시 **Rule-only로 즉시 반환** (신뢰도 0.8~1.0 가능)
 * 3. Rule 신뢰도 0.3 이상 + 금전/긴급/URL 신호 → **LLM 분석 호출**
 *    - 최근 대화 맥락을 LLM 컨텍스트로 제공
 *    - 단순 가중 평균(Rule 30%, LLM 70%)으로 최종 판정
 * 4. 그 외 → **Rule-only로 반환** (신뢰도 0~0.3)
 *
 * ## 최종 판정 임계값
 * - 0.5 초과: 스캠으로 판정
 * - 0.5 이하: 정상 또는 주의
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

        // 가중치 (단순 고정 비율)
        private const val RULE_WEIGHT = 0.3f
        private const val LLM_WEIGHT = 0.7f
    }

    /**
     * 강한 신호 여부를 판단한다.
     * 
     * 다음 조건 중 하나라도 만족하면 강한 신호로 간주:
     * - Counter Scam 112 DB에 등록된 전화번호 탐지
     * - KISA 악성 URL 탐지
     * - 경찰청 사기계좌 DB에 등록된 계좌번호 탐지
     * - 황금 패턴 (긴급성 + 금전 + URL) 탐지
     * 
     * @return true이면 Rule-only 경로로 처리 (LLM 생략)
     */
    private fun isStrongSignal(
        phoneResult: PhoneAnalysisResult,
        urlResult: UrlAnalysisResult,
        accountResult: AccountAnalysisResult,
        hasUrgency: Boolean,
        hasMoney: Boolean,
        hasUrl: Boolean
    ): Boolean {
        // 1. Counter Scam 112 DB 전화번호 히트
        if (phoneResult.hasScamPhones) {
            return true
        }
        
        // 2. KISA 악성 URL 히트
        if (urlResult.suspiciousUrls.isNotEmpty()) {
            return true
        }
        
        // 3. 경찰청 사기계좌 DB 계좌번호 히트
        if (accountResult.hasFraudAccounts) {
            return true
        }
        
        // 4. 황금 패턴: 긴급성 + 금전 + URL
        if (hasUrgency && hasMoney && hasUrl) {
            return true
        }
        
        return false
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
        
        // 텍스트 정규화 (황금 패턴 체크 및 LLM 트리거용)
        val lowerText = text.lowercase()

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

        // 8. Additional combination checks for medium confidence
        // 황금 패턴 체크용 (간단한 체크)
        val hasUrgency = lowerText.contains("긴급") || lowerText.contains("급하") || 
                lowerText.contains("빨리") || lowerText.contains("즉시")

        val hasMoney = lowerText.contains("입금") || lowerText.contains("송금") || 
                lowerText.contains("계좌") || lowerText.contains("이체")

        if (ruleConfidence > MEDIUM_CONFIDENCE_THRESHOLD) {
            // 스캠 황금 패턴: 긴급성 + 금전 요구 + URL (보너스 6% - 완화)
            if (hasUrgency && hasMoney && urlResult.urls.isNotEmpty()) {
                ruleConfidence += 0.06f
                combinedReasons.add("의심스러운 조합: 긴급 + 금전 + URL")
            }
        }
        
        // Rule 신뢰도 정규화 (0~1 범위)
        ruleConfidence = ruleConfidence.coerceIn(0f, 1f)

        DebugLog.debugLog(TAG) {
            "step=rule_result ruleConfidence=$ruleConfidence keywordReasons=${keywordResult.reasons.size} " +
                    "urlReasons=${urlResult.reasons.size} phoneReasons=${phoneResult.reasons.size} " +
                    "accountReasons=${accountResult.reasons.size} " +
                    "suspiciousUrlCount=${urlResult.suspiciousUrls.size} scamPhones=${phoneResult.scamPhones.size} " +
                    "fraudAccounts=${accountResult.fraudAccounts.size}"
        }

        // 9. 강한 신호 체크 — DB 히트나 황금 패턴이면 Rule-only로 즉시 반환
        val hasUrl = urlResult.urls.isNotEmpty()
        if (isStrongSignal(phoneResult, urlResult, accountResult, hasUrgency, hasMoney, hasUrl)) {
            DebugLog.debugLog(TAG) {
                "step=strong_signal_detected ruleConfidence=$ruleConfidence " +
                        "hasScamPhones=${phoneResult.hasScamPhones} " +
                        "hasSuspiciousUrls=${urlResult.suspiciousUrls.isNotEmpty()} " +
                        "hasFraudAccounts=${accountResult.hasFraudAccounts} " +
                        "goldenPattern=${hasUrgency && hasMoney && hasUrl}"
            }
            
            val hasExternalDbHit = urlResult.suspiciousUrls.isNotEmpty() ||
                    phoneResult.hasScamPhones ||
                    accountResult.hasFraudAccounts
            
            // 1단계: Rule-based 결과 생성 (점수/유형/판정 결정)
            val ruleResult = createRuleBasedResult(
                ruleConfidence,
                combinedReasons,
                keywordResult.detectedKeywords,
                hasExternalDbHit
            )
            
            // 2단계: LLM에 "경고 문구 생성 전용" 모드로 요청 (useLLM 설정 존중)
            if (useLLM && scamLlmClient.isAvailable()) {
                DebugLog.debugLog(TAG) {
                    "step=strong_signal_llm_explain ruleConfidence=$ruleConfidence scamType=${ruleResult.scamType}"
                }
                
                val request = ScamLlmRequest(
                    originalText = PiiMasker.mask(text),
                    recentContext = PiiMasker.mask(recentContext),
                    currentMessage = PiiMasker.mask(currentMessage),
                    ruleReasons = combinedReasons.map { PiiMasker.mask(it) },
                    detectedKeywords = keywordResult.detectedKeywords,
                    explanationOnlyMode = true,
                    ruleConfidence = ruleResult.confidence,
                    ruleScamType = ruleResult.scamType.name
                )
                
                val llmExplain = scamLlmClient.analyze(request)

                if (llmExplain != null) {
                    val preview = llmExplain.warningMessage?.take(50).orEmpty()
                    DebugLog.debugLog(TAG) {
                        "step=strong_signal_llm_success warningMessage=\"$preview\""
                    }
                    // 3단계: Rule 점수/유형 유지, LLM 문장만 덮어쓰기
                    return ruleResult.copy(
                        warningMessage = llmExplain.warningMessage,
                        reasons = (ruleResult.reasons + llmExplain.reasons).distinct()
                    )
                } else {
                    DebugLog.warnLog(TAG) { "step=strong_signal_llm_fallback reason=llm_null" }
                }
            }
            
            // LLM 미사용 또는 실패 시 Rule 결과 그대로 반환
            return ruleResult
        }

        // 10. LLM 분석
        // - 룰 기반 결과 + 키워드/URL/전화번호 신호를 바탕으로 LLM에게 컨텍스트 설명/보조 신뢰도를 요청한다.
        // 보수적 키워드: 명시적인 금전 표현만 (일상 대화 단어 제외)
        val hasMoneyKeyword = listOf("입금", "송금", "계좌", "선입금", "대출", "급전", "이체")
            .any { lowerText.contains(it) }
        // 긴급성 키워드: 조합 판단용
        val hasUrgencyKeyword = listOf("긴급", "급하", "빨리", "지금당장", "지금바로", "오늘안에", "즉시")
            .any { lowerText.contains(it) }
        val hasScamPhone = phoneResult.hasScamPhones
        val hasScamAccount = accountResult.hasFraudAccounts

        val shouldUseLLM = useLLM &&
                scamLlmClient.isAvailable() &&
                ruleConfidence >= 0.3f &&
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
                return combineResultsSimple(
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

        // 11. Final rule-based result (LLM 미사용 또는 트리거 조건 미충족)
        val hasExternalDbHit = urlResult.suspiciousUrls.isNotEmpty() ||
                phoneResult.hasScamPhones ||
                accountResult.hasFraudAccounts
        return createRuleBasedResult(
            ruleConfidence,
            combinedReasons,
            keywordResult.detectedKeywords,
            hasExternalDbHit,
            highRiskKeywords = keywordResult.highRiskKeywords,
            mediumRiskKeywords = keywordResult.mediumRiskKeywords,
            lowRiskKeywords = keywordResult.lowRiskKeywords,
            hasCombination = keywordResult.hasSuspiciousCombination
        )
    }

    /**
     * Rule-based 결과와 LLM 결과를 단순 가중 평균(Rule 30%, LLM 70%)으로 결합한다.
     *
     * @param ruleConfidence 규칙 기반 신뢰도
     * @param ruleReasons 규칙 기반 탐지 사유
     * @param detectedKeywords 탐지된 키워드 목록
     * @param llmResult LLM 분석 결과
     * @return 결합된 [ScamAnalysis]
     */
    private fun combineResultsSimple(
        ruleConfidence: Float,
        ruleReasons: List<String>,
        detectedKeywords: List<String>,
        llmResult: ScamAnalysis
    ): ScamAnalysis {
        // 단순 가중 평균: Rule 30% + LLM 70%
        val combinedConfidence = (ruleConfidence * RULE_WEIGHT + llmResult.confidence * LLM_WEIGHT)
            .coerceIn(0f, 1f)

        // 이유 목록 결합 (중복 제거)
        val allReasons = (ruleReasons + llmResult.reasons).distinct()

        DebugLog.debugLog(TAG) {
            "step=combine_simple rule=$ruleConfidence llm=${llmResult.confidence} " +
                    "final=$combinedConfidence isScam=${combinedConfidence > FINAL_SCAM_THRESHOLD} " +
                    "scamType=${llmResult.scamType}"
        }

        return ScamAnalysis(
            isScam = combinedConfidence > FINAL_SCAM_THRESHOLD || llmResult.isScam,
            confidence = combinedConfidence,
            reasons = allReasons,
            detectedKeywords = (detectedKeywords + llmResult.detectedKeywords).distinct(),
            highRiskKeywords = llmResult.highRiskKeywords, // LLM 결과가 있으면 그걸 우선적으로 사용하거나 룰 결과와 합침
            mediumRiskKeywords = llmResult.mediumRiskKeywords,
            lowRiskKeywords = llmResult.lowRiskKeywords,
            hasSuspiciousCombination = llmResult.hasSuspiciousCombination,
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
     * @param highRiskKeywords 고위험 키워드
     * @param mediumRiskKeywords 중위험 키워드
     * @param lowRiskKeywords 저위험 키워드
     * @param hasCombination 조합 발견 여부
     * @return [ScamAnalysis]
     */
    private fun createRuleBasedResult(
        confidence: Float,
        reasons: List<String>,
        detectedKeywords: List<String>,
        hasUrlIssues: Boolean,
        highRiskKeywords: List<String> = emptyList(),
        mediumRiskKeywords: List<String> = emptyList(),
        lowRiskKeywords: List<String> = emptyList(),
        hasCombination: Boolean = false
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
            highRiskKeywords = highRiskKeywords,
            mediumRiskKeywords = mediumRiskKeywords,
            lowRiskKeywords = lowRiskKeywords,
            hasSuspiciousCombination = hasCombination,
            detectionMethod = if (hasUrlIssues) DetectionMethod.HYBRID else DetectionMethod.RULE_BASED,
            scamType = scamType,
            warningMessage = warningMessage,
            suspiciousParts = detectedKeywords.take(3)  // 상위 3개 키워드
        )
    }
}
