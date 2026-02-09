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
import android.content.Intent
import com.onguard.service.FloatingControlService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val repository: ScamAlertRepository,
    private val settingsStore: DetectionSettingsDataStore
) : AndroidViewModel(application) {

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                checkPermissionsAndStopIfInvalid(settings)
                manageWidgetService(settings)
            }
        }
    }

    private fun checkPermissionsAndStopIfInvalid(settings: com.onguard.data.local.DetectionSettings) {
        val context = getApplication<Application>()
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isOverlayEnabled = Settings.canDrawOverlays(context)
        
        if (!settings.canStartDetection(isAccessibilityEnabled, isOverlayEnabled)) {
            if (settings.isDetectionEnabled) {
                viewModelScope.launch {
                    settingsStore.setDetectionEnabled(false)
                }
            }
        }
    }


    private fun manageWidgetService(settings: com.onguard.data.local.DetectionSettings) {
        val context = getApplication<Application>()
        val isOverlayEnabled = Settings.canDrawOverlays(context)
        
        val currentTime = System.currentTimeMillis()
        val isPaused = settings.pauseUntilTimestamp > 0 && currentTime < settings.pauseUntilTimestamp
        
        val baseTime = settings.calculateChronometerBase()

        val serviceIntent = Intent(context, FloatingControlService::class.java).apply {
            putExtra(FloatingControlService.EXTRA_START_TIME, baseTime)
            putExtra(FloatingControlService.EXTRA_IS_ACTIVE, settings.isDetectionEnabled)
            putExtra(FloatingControlService.EXTRA_IS_PAUSED, isPaused)
        }

        if (isOverlayEnabled && settings.isWidgetEnabled) {
            context.startService(serviceIntent)
        } else {
            context.stopService(serviceIntent)
            
            // 만약 오버레이 권한이 없는데 설정이 켜져 있다면, 설정을 자동으로 끔 (동기화)
            if (!isOverlayEnabled && settings.isWidgetEnabled) {
                viewModelScope.launch {
                    settingsStore.setWidgetEnabled(false)
                }
            }
        }
    }


    /** 단일 알림 삭제 */
    fun deleteAlert(alertId: Long) {
        viewModelScope.launch {
            repository.deleteAlert(alertId)
        }
    }

    /** 모든 알림 삭제 */
    fun clearAllAlerts() {
        viewModelScope.launch {
            repository.deleteAllAlerts()
        }
    }

    private val tickerFlow = flow {
        while (true) {
            emit(Unit)
            kotlinx.coroutines.delay(1000)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.getAllAlerts(),
        settingsStore.settingsFlow,
        tickerFlow
    ) { alerts, settings, _ ->
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isOverlayEnabled = Settings.canDrawOverlays(getApplication())
        val isDetectionEnabled = settings.isDetectionEnabled
        
        val isPaused = settings.pauseUntilTimestamp > 0 && System.currentTimeMillis() < settings.pauseUntilTimestamp
        val isRealActive = settings.isDetectionEnabled && !isPaused
        
        val isProtected = isAccessibilityEnabled && isOverlayEnabled && isRealActive
        
        val totalKeywords = alerts.sumOf { it.detectedKeywords.size }
        val highRiskCount = alerts.count { it.confidence >= 0.7f }
        val mediumRiskCount = alerts.count { it.confidence in 0.4f..0.69f }
        val lowRiskCount = alerts.count { it.confidence < 0.4f }
        
        
        val now = System.currentTimeMillis()
        val currentSegment = if (settings.isActiveNow() && settings.detectionStartTime > 0) {
            now - settings.detectionStartTime
        } else {
            0L
        }

        val totalTime = settings.totalAccumulatedTime + currentSegment
        val simHigh = highRiskCount
        val simMedium = mediumRiskCount
        val simLow = lowRiskCount
        val simTotal = alerts.size
        val simKeywords = totalKeywords

        // Calculate weekly stats (7 days)
        fun calculateWeeklyStats(list: List<com.onguard.domain.model.ScamAlert>): Pair<List<Int>, List<Int>> {
            val keywordStats = MutableList(7) { 0 }
            val timeStats = MutableList(7) { 0 }
            val calendar = java.util.Calendar.getInstance()
            
            val todayYear = calendar.get(java.util.Calendar.YEAR)
            val todayDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
            
            list.forEach { alert ->
                calendar.time = alert.timestamp
                val alertYear = calendar.get(java.util.Calendar.YEAR)
                val alertDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
                
                val diffDays = if (todayYear == alertYear) {
                    todayDay - alertDay
                } else {
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

        val todayAlerts = alerts.filter { isDateToday(it.timestamp) }
        
        val todayHighRisk = todayAlerts.filter { it.confidence >= 0.7f }
        val todayMediumRisk = todayAlerts.filter { it.confidence in 0.4f..0.69f }
        val todayLowRisk = todayAlerts.filter { it.confidence < 0.4f }
        
        val todayTotal = todayAlerts.size
        
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

        DashboardUiState(
            status = if (isProtected) SecurityStatus.PROTECTED else SecurityStatus.UNPROTECTED,
            totalDetectionCount = simTotal,
            recentAlerts = alerts.sortedByDescending { it.timestamp }.take(20),
            highRiskCount = simHigh,
            mediumRiskCount = simMedium,
            lowRiskCount = simLow,
            totalKeywords = simKeywords,
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
            weeklyKeywordStats = weeklyKeywordStats,
            weeklyTimeStats = weeklyTimeStats,
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

// Helper to check if a date is today
private fun isDateToday(date: java.util.Date): Boolean {
    val calendar = java.util.Calendar.getInstance()
    val todayYear = calendar.get(java.util.Calendar.YEAR)
    val todayDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
    
    calendar.time = date
    return calendar.get(java.util.Calendar.YEAR) == todayYear &&
            calendar.get(java.util.Calendar.DAY_OF_YEAR) == todayDay
}

// Helper to get top keywords for a risk level
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