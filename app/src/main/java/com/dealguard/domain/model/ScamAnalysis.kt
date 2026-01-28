package com.dealguard.domain.model

data class ScamAnalysis(
    val isScam: Boolean,
    val confidence: Float,
    val reasons: List<String>,
    val detectedKeywords: List<String> = emptyList(),
    val detectionMethod: DetectionMethod = DetectionMethod.RULE_BASED
)

enum class DetectionMethod {
    RULE_BASED,
    ML_CLASSIFIER,
    HYBRID,
    EXTERNAL_DB
}
