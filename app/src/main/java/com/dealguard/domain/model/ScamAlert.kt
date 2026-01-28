package com.dealguard.domain.model

data class ScamAlert(
    val id: Long = 0,
    val text: String,
    val sourceApp: String,
    val analysis: ScamAnalysis,
    val timestamp: Long = System.currentTimeMillis(),
    val isDismissed: Boolean = false
)
