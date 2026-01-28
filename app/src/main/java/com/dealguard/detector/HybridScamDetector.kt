package com.dealguard.detector

import com.dealguard.domain.model.DetectionMethod
import com.dealguard.domain.model.ScamAnalysis
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class HybridScamDetector @Inject constructor(
    private val keywordMatcher: KeywordMatcher,
    private val urlAnalyzer: UrlAnalyzer
    // private val mlClassifier: MlClassifier  // TODO: Day 10-11
) {

    companion object {
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.7f
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

        // URL analysis adds to confidence
        if (urlResult.suspiciousUrls.isNotEmpty()) {
            combinedConfidence = max(combinedConfidence, urlResult.riskScore)
            combinedConfidence += urlResult.riskScore * 0.3f  // URL bonus
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

            if (hasUrgency && hasMoney && urlResult.urls.isNotEmpty()) {
                // Suspicious: urgency + money + URL
                combinedConfidence += 0.15f
                combinedReasons.add("의심스러운 조합: 긴급 + 금전 + URL")
            }
        }

        // 7. TODO: For low confidence, use ML classifier (Day 10-11)
        // if (combinedConfidence < MEDIUM_CONFIDENCE_THRESHOLD) {
        //     val mlResult = mlClassifier?.classify(text)
        //     if (mlResult != null) {
        //         combinedConfidence = (combinedConfidence + mlResult.confidence) / 2
        //         combinedReasons.add("ML 분석 결과 포함")
        //     }
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
