package com.onguard.presentation.ui.dashboard

import com.onguard.domain.model.ScamAlert

data class DashboardUiState(
    val status: SecurityStatus = SecurityStatus.UNPROTECTED,
    val totalDetectionCount: Int = 0,
    val recentAlerts: List<ScamAlert> = emptyList(),
    val highRiskCount: Int = 0,
    val mediumRiskCount: Int = 0,
    val lowRiskCount: Int = 0,
    val totalKeywords: Int = 0,
    val totalDetectionValue: Int = 0,
    val totalDetectionUnit: String = "시간",
    val weeklyKeywordStats: List<Int> = List(7) { 0 },
    val weeklyTimeStats: List<Int> = List(7) { 0 },
    val dailyStats: DailyRiskStats = DailyRiskStats(),
    val isProtected: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val isDetectionEnabled: Boolean = false,
    val isLoading: Boolean = false
)

enum class SecurityStatus {
    PROTECTED, UNPROTECTED
}

data class DailyRiskStats(
    val cumulativeCount: Int = 0,
    val highRiskRatio: Float = 0f,
    val mediumRiskRatio: Float = 0f,
    val lowRiskRatio: Float = 0f,
    val highRiskDetail: RiskDetail = RiskDetail(count = 0, tags = emptyList()),
    val mediumRiskDetail: RiskDetail = RiskDetail(count = 0, tags = emptyList()),
    val lowRiskDetail: RiskDetail = RiskDetail(count = 0, tags = emptyList())
)

data class RiskDetail(
    val count: Int,
    val tags: List<String>,
    val timeDistribution: List<Int> = List(12) { 0 }
)