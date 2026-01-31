package com.dealguard.detector

import com.dealguard.domain.model.DetectionMethod
import com.dealguard.domain.model.ScamAnalysis
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 하이브리드 스캠 탐지기
 *
 * 탐지 방식:
 * 1. KeywordMatcher: 텍스트 내 위험 키워드 분석 (Rule-based)
 * 2. UrlAnalyzer: URL 위험도 분석 (KISA DB + 휴리스틱)
 * 3. (TODO) MlClassifier: 저신뢰도 케이스 ML 분류 (Day 10-11, SLM 팀 구현)
 *
 * 신뢰도 계산 공식:
 * - 기본: max(키워드 신뢰도, URL 위험도)
 * - URL 보너스: +30% (의심 URL 존재 시)
 * - 조합 보너스: +15% (긴급성 + 금전 + URL 조합)
 *
 * 임계값:
 * - 0.7 이상: 고위험 (즉시 스캠 판정)
 * - 0.4~0.7: 중위험 (추가 조합 분석)
 * - 0.4 미만: 저위험 (ML 분류 필요 - 미구현)
 * - 0.5 이상: 최종 스캠 판정
 */
@Singleton
class HybridScamDetector @Inject constructor(
    private val keywordMatcher: KeywordMatcher,
    private val urlAnalyzer: UrlAnalyzer
    // TODO(SLM팀): MlClassifier 구현 필요
    // - 호출 시점: 신뢰도 < 0.4 일 때
    // - TensorFlow Lite 모델 사용 (assets/ml/scam_detector.tflite)
    // - 비동기 처리 (suspend function)
    // private val mlClassifier: MlClassifier
) {

    companion object {
        // 고위험 임계값: 70% 이상이면 즉시 스캠 판정
        // - 오탐 최소화를 위해 높게 설정
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.7f

        // 중위험 임계값: 40% 이상이면 추가 조합 분석 수행
        // - 긴급성+금전+URL 조합 시 보너스 부여
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.4f
    }

    suspend fun analyze(text: String): ScamAnalysis {
        // 1. Rule-based keyword detection (fast)
        val keywordResult = keywordMatcher.analyze(text)

        // 2. URL analysis
        val urlResult = urlAnalyzer.analyze(text)

        // 3. Combine results
        val combinedReasons = mutableListOf<String>()
        combinedReasons.addAll(keywordResult.reasons)
        combinedReasons.addAll(urlResult.reasons)

        // 4. Calculate combined confidence
        var combinedConfidence = keywordResult.confidence

        // URL 분석 결과 반영
        // - 의심 URL이 있으면 최소한 URL 위험도만큼 신뢰도 보장
        // - 추가로 URL 위험도의 30%를 보너스로 부여
        // - 이유: URL이 포함된 스캠은 위험도가 높음 (피싱 링크 가능성)
        if (urlResult.suspiciousUrls.isNotEmpty()) {
            combinedConfidence = max(combinedConfidence, urlResult.riskScore)
            combinedConfidence += urlResult.riskScore * 0.3f
        }

        // Normalize to 0-1
        combinedConfidence = combinedConfidence.coerceIn(0f, 1f)

        // 5. Early return for high confidence
        if (combinedConfidence > HIGH_CONFIDENCE_THRESHOLD) {
            return ScamAnalysis(
                isScam = true,
                confidence = combinedConfidence,
                reasons = combinedReasons,
                detectedKeywords = keywordResult.detectedKeywords,
                detectionMethod = DetectionMethod.HYBRID
            )
        }

        // 6. For medium confidence, additional checks
        if (combinedConfidence > MEDIUM_CONFIDENCE_THRESHOLD) {
            // Check for suspicious combinations
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
                combinedConfidence += 0.15f
                combinedReasons.add("의심스러운 조합: 긴급 + 금전 + URL")
            }
        }

        // TODO(SLM팀): 저신뢰도 케이스 ML 분류 구현 필요
        // 호출 조건: combinedConfidence < 0.4
        // 기대 인터페이스:
        //   interface MlClassifier {
        //       suspend fun classify(text: String): ClassificationResult
        //   }
        //   data class ClassificationResult(
        //       val isScam: Boolean,
        //       val confidence: Float,
        //       val reasons: List<String>
        //   )
        // 신뢰도 계산: (규칙 기반 신뢰도 + ML 신뢰도) / 2
        // if (combinedConfidence < MEDIUM_CONFIDENCE_THRESHOLD) {
        //     val mlResult = mlClassifier.classify(text)
        //     combinedConfidence = (combinedConfidence + mlResult.confidence) / 2
        //     combinedReasons.add("ML 분석 결과 포함")
        // }

        // 8. Final result
        val finalConfidence = combinedConfidence.coerceIn(0f, 1f)

        return ScamAnalysis(
            isScam = finalConfidence > 0.5f,
            confidence = finalConfidence,
            reasons = combinedReasons,
            detectedKeywords = keywordResult.detectedKeywords,
            detectionMethod = if (urlResult.suspiciousUrls.isNotEmpty()) {
                DetectionMethod.HYBRID
            } else {
                DetectionMethod.RULE_BASED
            }
        )
    }
}
