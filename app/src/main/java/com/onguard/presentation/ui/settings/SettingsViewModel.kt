package com.onguard.presentation.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onguard.data.local.DetectionSettings
import com.onguard.data.local.DetectionSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 설정 화면 UI 상태
 */
data class SettingsUiState(
    val settings: DetectionSettings = DetectionSettings(),
    val isLoading: Boolean = true,
    val pauseDurationMinutes: Int? = null,  // 선택된 일시 중지 시간
    val showPauseDialog: Boolean = false
)

/**
 * 일시 중지 옵션
 */
enum class PauseDuration(val minutes: Int, val label: String) {
    MINUTES_15(15, "15분"),
    MINUTES_30(30, "30분"),
    HOUR_1(60, "1시간"),
    HOURS_2(120, "2시간"),
    HOURS_4(240, "4시간"),
    HOURS_8(480, "8시간")
}

/**
 * 설정 화면 ViewModel
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: DetectionSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLoading = false) }
            }
        }
    }

    /**
     * 전역 탐지 활성화/비활성화
     */
    fun setDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDetectionEnabled(enabled)
        }
    }

    /**
     * 일시 중지 다이얼로그 표시
     */
    fun showPauseDialog() {
        _uiState.update { it.copy(showPauseDialog = true) }
    }

    /**
     * 일시 중지 다이얼로그 닫기
     */
    fun dismissPauseDialog() {
        _uiState.update { it.copy(showPauseDialog = false) }
    }

    /**
     * 탐지 일시 중지
     */
    fun pauseDetection(durationMinutes: Int) {
        viewModelScope.launch {
            settingsStore.pauseDetection(durationMinutes)
            _uiState.update { it.copy(showPauseDialog = false) }
        }
    }

    /**
     * 일시 중지 해제 (탐지 재개)
     */
    fun resumeDetection() {
        viewModelScope.launch {
            settingsStore.resumeDetection()
        }
    }

    /**
     * 특정 앱 탐지 설정
     */
    fun setAppEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAppEnabled(packageName, enabled)
        }
    }

    /**
     * 모든 앱 탐지 활성화
     */
    fun enableAllApps() {
        viewModelScope.launch {
            settingsStore.enableAllApps()
        }
    }
}
