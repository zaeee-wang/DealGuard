package com.onguard.detector

/**
 * HybridScamDetector 임계값·가중치 설정.
 *
 * 테스트 시 다른 값을 주입하여 동작을 검증할 수 있다.
 */
data class HybridScamDetectorConfig(
    val highConfidenceThreshold: Float = 0.7f,
    val mediumConfidenceThreshold: Float = 0.4f,
    val llmTriggerLow: Float = 0.3f,
    val llmTriggerHigh: Float = 0.7f,
    val ruleWeight: Float = 0.4f,
    val llmWeight: Float = 0.6f,
    val finalScamThreshold: Float = 0.5f
) {
    companion object {
        val Default = HybridScamDetectorConfig()
    }
}
