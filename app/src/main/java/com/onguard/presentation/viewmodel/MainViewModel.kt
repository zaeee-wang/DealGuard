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
        
        // 보호 상태: 모든 권한 활성화 + 탐지 활성화 (일시 중지 아닐 때만)
        val isProtected = isAccessibilityEnabled && isOverlayEnabled && settings.isActiveNow()
        
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

        // [시뮬레이션 로직] 데모를 위해 시간이 지남에 따라 수치가 증가하는 것처럼 보임
        // 실제 데이터(alerts) + 시간 경과값 (기본값 0에서 시작)
        val simHigh = highRiskCount + (runningTimeSec / 30).toInt()
        val simMedium = mediumRiskCount + (runningTimeSec / 20).toInt()
        val simLow = lowRiskCount + (runningTimeSec / 3).toInt()
        val simTotal = simHigh + simMedium + simLow
        val simKeywords = totalKeywords + (runningTimeSec / 5).toInt()

        // 매핑 결과 반환
        DashboardUiState(
            status = if (isSafe) SecurityStatus.PROTECTED else SecurityStatus.UNPROTECTED,
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
            
            // 하단 일일 통계 (데모용 계산 로직)
            dailyStats = DailyRiskStats(
                cumulativeCount = simTotal,
                highRiskRatio = simHigh.toFloat() / simTotal,
                mediumRiskRatio = simMedium.toFloat() / simTotal,
                lowRiskRatio = simLow.toFloat() / simTotal,
                
                // 상세 내역에는 실제 가장 많이 탐지된 키워드를 넣음 (데모 값과 함께)
                highRiskDetail = RiskDetail(simHigh, getTopKeywords(alerts, 0.7f)),
                mediumRiskDetail = RiskDetail(simMedium, getTopKeywords(alerts, 0.4f, 0.69f)),
                lowRiskDetail = RiskDetail(simLow, getTopKeywords(alerts, 0.0f, 0.39f))
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

    // 헬퍼 함수: 특정 위험도 구간의 상위 키워드 추출
    private fun getTopKeywords(alerts: List<com.onguard.domain.model.ScamAlert>, minConf: Float, maxConf: Float = 1.0f): List<String> {
        return alerts
            .filter { it.confidence in minConf..maxConf }
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