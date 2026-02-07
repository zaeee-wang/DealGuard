//app/src/main/java/com/onguard/presentation/ui/dashboard/DashboardState.kt

package com.onguard.presentation.ui.dashboard

import com.onguard.domain.model.ScamAlert

// 디자인에 표시되는 모든 통계 데이터를 포함하는 UI 상태 클래스
data class DashboardUiState(
    val status: SecurityStatus = SecurityStatus.PROTECTED,
    val totalDetectionCount: Int = 0, // 메인 거대 숫자
    
    // 최근 알림 목록
    val recentAlerts: List<ScamAlert> = emptyList(),
    
    // 상단 3개 카드 통계
    val highRiskCount: Int = 0,
    val mediumRiskCount: Int = 0,
    val lowRiskCount: Int = 0,

    // 중간 차트 카드 통계
    val totalKeywords: Int = 0,
    val totalDetectionValue: Int = 0,
    val totalDetectionUnit: String = "시간",

    // 하단 일일 위험 탐지 섹션
    val dailyStats: DailyRiskStats = DailyRiskStats(),
    
    // 보호 상태 관련
    val isProtected: Boolean = false, // 권한 + 탐지 활성화 여부
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val isDetectionEnabled: Boolean = false,
    
    val isLoading: Boolean = false
)

enum class SecurityStatus {
    PROTECTED, UNPROTECTED
}

// 일일 통계 및 상세 내역
data class DailyRiskStats(
    val cumulativeCount: Int = 0, // 원형 게이지 중앙 숫자
    // 각 위험도별 비율 (0.0 ~ 1.0)
    val highRiskRatio: Float = 0f,
    val mediumRiskRatio: Float = 0f,
    val lowRiskRatio: Float = 0f,
    // 하단 상세 카드 데이터
    val highRiskDetail: RiskDetail = RiskDetail(
        count = 0,
        tags = emptyList()
    ),
    val mediumRiskDetail: RiskDetail = RiskDetail(
        count = 0,
        tags = emptyList()
    ),
    val lowRiskDetail: RiskDetail = RiskDetail(
        count = 0,
        tags = emptyList()
    )
)

data class RiskDetail(
    val count: Int,
    val tags: List<String> // "개인정보 요구" 같은 태그들
)