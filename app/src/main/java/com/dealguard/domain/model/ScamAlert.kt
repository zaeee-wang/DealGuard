package com.dealguard.domain.model

import java.util.Date

data class ScamAlert(
    val id: Long = 0,
    val message: String,
    val confidence: Float,
    val sourceApp: String,
    val detectedKeywords: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val timestamp: Date = Date(),
    val isDismissed: Boolean = false
)
