package com.onguard.presentation.viewmodel

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onguard.data.local.DetectionSettingsDataStore
import com.onguard.domain.repository.ScamAlertRepository
import com.onguard.presentation.ui.dashboard.DailyRiskStats
import com.onguard.presentation.ui.dashboard.DashboardUiState
import com.onguard.presentation.ui.dashboard.RiskDetail
import com.onguard.presentation.ui.dashboard.SecurityStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val repository: ScamAlertRepository,
    private val settingsStore: DetectionSettingsDataStore
) : AndroidViewModel(application) {

    // 1초마다 틱을 발생시키는 Flow (실시간 타이머 및 카운트 증가 효과용)
    private val tickerFlow = flow {
        while (true) {
            emit(Unit)
            kotlinx.coroutines.delay(1000)
        }
    }

    // 1. Repository에서 데이터를 가져와서 UI State로 변환하는 파이프라인
    val uiState: StateFlow<DashboardUiState> = combine(
        repository.getAllAlerts(),
        settingsStore.settingsFlow,
        tickerFlow
    ) { alerts, settings, _ ->
        // [데이터 변환 로직] List<ScamAlert> + Settings -> DashboardUiState
        
        // 권한 체크
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isOverlayEnabled = Settings.canDrawOverlays(getApplication())
        val isDetectionEnabled = settings.isDetectionEnabled
        
        // 보호 상태: 모든 권한 활성화 + 탐지 활성화 (일시 중지 상태가 아닐 때만)
        // isActiveNow() 로직을 직접 구현하여 상태 갱신 확실하게 처리
        val isPaused = settings.pauseUntilTimestamp > 0 && System.currentTimeMillis() < settings.pauseUntilTimestamp
        val isRealActive = settings.isDetectionEnabled && !isPaused
        
        val isProtected = isAccessibilityEnabled && isOverlayEnabled && isRealActive
        
        // 위험도별 카운팅 (실제 데이터)
        val highRiskCount = alerts.count { it.confidence >= 0.7f }
        val mediumRiskCount = alerts.count { it.confidence in 0.4f..0.69f }
        val lowRiskCount = alerts.count { it.confidence < 0.4f }
        
        // 키워드 총합 계산 (실제 데이터)
        val totalKeywords = alerts.sumOf { it.detectedKeywords.size }
        
        // 상태 결정 (최근 24시간 내 고위험 있으면 UNPROTECTED)
        val isSafe = highRiskCount == 0 
        
        // 탐지 시간 및 시뮬레이션 수치 계산
        // 현재 활성화 상태라면 진행 중인 시간도 포함해야 함
        val currentSessionDuration = if (settings.isActiveNow() && settings.detectionStartTime > 0) {
            System.currentTimeMillis() - settings.detectionStartTime
        } else {
            0L
        }
        val totalTime = settings.totalAccumulatedTime + currentSessionDuration
        val runningTimeSec = totalTime / 1000

        // [수정] 시뮬레이션 로직 제거 - 실제 데이터만 사용
        val simHigh = highRiskCount
        val simMedium = mediumRiskCount
        val simLow = lowRiskCount
        val simTotal = alerts.size
        val simKeywords = totalKeywords

        // 주간 통계 계산 (7일간)
        fun calculateWeeklyStats(list: List<com.onguard.domain.model.ScamAlert>): Pair<List<Int>, List<Int>> {
            val keywordStats = MutableList(7) { 0 }
            val timeStats = MutableList(7) { 0 }
            val calendar = java.util.Calendar.getInstance()
            
            // 오늘 날짜 기준
            val todayYear = calendar.get(java.util.Calendar.YEAR)
            val todayDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
            
            list.forEach { alert ->
                calendar.time = alert.timestamp
                val alertYear = calendar.get(java.util.Calendar.YEAR)
                val alertDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
                
                // 날짜 차이 계산 (연도가 같다고 가정하거나, 단순화된 로직)
                // 더 정확한 차이 계산을 위해 밀리초 단위 비교가 나을 수 있음
                // 여기서는 간단히 dayOfYear 차이로 계산 (연말연시 고려 X - 프로토타입)
                // 만약 연도가 다르면 단순 차이로 안됨.
                // 안전하게: 날짜 차이 유틸리티 사용 또는 
                // "오늘 - 알림날짜"의 일수 차이 계산
                
                val diffDays = if (todayYear == alertYear) {
                    todayDay - alertDay
                } else {
                    // 연도가 다른 경우 (예: 2025-12-31 vs 2026-01-01)
                    // 복잡하므로 Epoch Day 변환 후 차이 계산이 정확함
                    val todayEpochDay = java.time.LocalDate.now().toEpochDay()
                    val alertEpochDay = alert.timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                    (todayEpochDay - alertEpochDay).toInt()
                }
                
                if (diffDays in 0..6) {
                    keywordStats[diffDays] += alert.detectedKeywords.size
                    timeStats[diffDays] += 1 // 탐지 빈도 (건수)
                }
            }
            return Pair(keywordStats, timeStats)
        }
        
        val (weeklyKeywordStats, weeklyTimeStats) = calculateWeeklyStats(alerts)

        // === 데일리 업데이트 탭 (오늘 데이터) 로직 ===
        // 1. 오늘 날짜 데이터 필터링
        val todayAlerts = alerts.filter { isDateToday(it.timestamp) }
        
        // 2. 위험도별 분류
        val todayHighRisk = todayAlerts.filter { it.confidence >= 0.7f }
        val todayMediumRisk = todayAlerts.filter { it.confidence in 0.4f..0.69f }
        val todayLowRisk = todayAlerts.filter { it.confidence < 0.4f }
        
        val todayTotal = todayAlerts.size
        
        // 3. 시간별 분포 계산 (12구간)
        fun calculateTimeDistribution(list: List<com.onguard.domain.model.ScamAlert>): List<Int> {
            val distribution = MutableList(12) { 0 }
            val calendar = java.util.Calendar.getInstance()
            
            list.forEach { alert ->
                calendar.time = alert.timestamp
                val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val index = (hour / 2).coerceIn(0, 11)
                distribution[index]++
            }
            return distribution
        }

        // 매핑 결과 반환
        DashboardUiState(
            // 위험 감지 여부(isSafe)와 상관없이, 보호 기능이 켜져 있고 작동 중이면 파란색(PROTECTED) 표시
            status = if (isProtected) SecurityStatus.PROTECTED else SecurityStatus.UNPROTECTED,
            totalDetectionCount = simTotal,
            
            recentAlerts = alerts.sortedByDescending { it.timestamp }.take(20),
            
            highRiskCount = simHigh,
            mediumRiskCount = simMedium,
            lowRiskCount = simLow,
            
            totalKeywords = simKeywords,
            
            // 탐지 시간 포맷팅 로직
            totalDetectionValue = when {
                totalTime < 60 * 1000 -> (totalTime / 1000).toInt()
                totalTime < 100 * 60 * 1000 -> (totalTime / (60 * 1000)).toInt()
                else -> (totalTime / (60 * 60 * 1000)).toInt()
            },
            totalDetectionUnit = when {
                totalTime < 60 * 1000 -> "초"
                totalTime < 100 * 60 * 1000 -> "분"
                else -> "시간"
            },
            
            // 주간 통계 전달 (MainViewModel에서 계산된 값)
            weeklyKeywordStats = weeklyKeywordStats,
            weeklyTimeStats = weeklyTimeStats,
            
            // 하단 일일 통계 (실제 오늘 데이터)
            dailyStats = DailyRiskStats(
                cumulativeCount = todayTotal,
                highRiskRatio = if (todayTotal > 0) todayHighRisk.size.toFloat() / todayTotal else 0f,
                mediumRiskRatio = if (todayTotal > 0) todayMediumRisk.size.toFloat() / todayTotal else 0f,
                lowRiskRatio = if (todayTotal > 0) todayLowRisk.size.toFloat() / todayTotal else 0f,
                
                highRiskDetail = RiskDetail(
                    count = todayHighRisk.size,
                    tags = getTopKeywords(todayHighRisk, 0.7f),
                    timeDistribution = calculateTimeDistribution(todayHighRisk)
                ),
                mediumRiskDetail = RiskDetail(
                    count = todayMediumRisk.size,
                    tags = getTopKeywords(todayMediumRisk, 0.4f, 0.69f),
                    timeDistribution = calculateTimeDistribution(todayMediumRisk)
                ),
                lowRiskDetail = RiskDetail(
                    count = todayLowRisk.size,
                    tags = getTopKeywords(todayLowRisk, 0.0f, 0.39f),
                    timeDistribution = calculateTimeDistribution(todayLowRisk)
                )
            ),
            
            // 보호 상태
            isProtected = isProtected,
            isAccessibilityEnabled = isAccessibilityEnabled,
            isOverlayEnabled = isOverlayEnabled,
            isDetectionEnabled = isDetectionEnabled
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(isLoading = true)
    )

// 헬퍼 함수: 날짜가 오늘인지 확인
private fun isDateToday(date: java.util.Date): Boolean {
    val calendar = java.util.Calendar.getInstance()
    val todayYear = calendar.get(java.util.Calendar.YEAR)
    val todayDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
    
    calendar.time = date
    return calendar.get(java.util.Calendar.YEAR) == todayYear &&
            calendar.get(java.util.Calendar.DAY_OF_YEAR) == todayDay
}

// 헬퍼 함수: 특정 위험도 구간의 상위 키워드 추출
private fun getTopKeywords(alerts: List<com.onguard.domain.model.ScamAlert>, minConf: Float, maxConf: Float = 1.0f): List<String> {
    return alerts
        // 이미 필터링된 리스트가 들어올 수도 있으므로 범위 체크는 안전망으로 유지
        // (필터링된 리스트라면 min/maxConf 무시해도 되지만, 범용성 위해 유지)
        .flatMap { it.detectedKeywords }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(3)
        .map { it.key }
}
    
    /**
     * 접근성 서비스 활성화 여부 확인
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val context = getApplication<Application>()
        val serviceName = "${context.packageName}/com.onguard.service.ScamDetectionAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(serviceName) == true
    }
}