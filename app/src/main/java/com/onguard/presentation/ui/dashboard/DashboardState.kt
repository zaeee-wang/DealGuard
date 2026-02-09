//app/src/main/java/com/onguard/presentation/ui/dashboard/DashboardState.kt

package com.onguard.presentation.ui.dashboard

import com.onguard.domain.model.ScamAlert

// 디자인에 표시되는 모든 통계 데이터를 포함하는 UI 상태 클래스
data class DashboardUiState(
    val status: SecurityStatus = SecurityStatus.UNPROTECTED,
    
    // === 메인 페이지 (총 누적 데이터 - 앱 설치 이후 전체) ===
    val totalDetectionCount: Int = 0, // 메인 거대 숫자 (총 누적 탐지 건수)
    
    // 최근 알림 목록
    val recentAlerts: List<ScamAlert> = emptyList(),
    
    // 상단 3개 카드 통계 (총 누적)
    val highRiskCount: Int = 0,
    val mediumRiskCount: Int = 0,
    val lowRiskCount: Int = 0,

    // 중간 차트 카드 통계 (총 누적)
    val totalKeywords: Int = 0,
    val totalDetectionValue: Int = 0,
    val totalDetectionUnit: String = "시간",
    
    // 주간 통계 (인덱스 0: 오늘, 1: 어제, ... 6: 6일 전)
    val weeklyKeywordStats: List<Int> = List(7) { 0 },
    val weeklyTimeStats: List<Int> = List(7) { 0 },

    // === 데일리 업데이트 탭 (당일 데이터만 - 오늘 일자) ===
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
    val tags: List<String>, // "개인정보 요구" 같은 태그들
    val timeDistribution: List<Int> = List(12) { 0 } // 0~2시, 2~4시 ... 22~24시 (총 12개 구간)
)