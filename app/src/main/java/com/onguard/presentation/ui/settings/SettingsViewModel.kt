package com.onguard.presentation.ui.settings

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.onguard.data.local.DetectionSettings
import com.onguard.data.local.DetectionSettingsDataStore
import com.onguard.service.FloatingControlService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: DetectionSettings = DetectionSettings(),
    val isLoading: Boolean = true,
    val pauseDurationMinutes: Int? = null,
    val showPauseDialog: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false
)

enum class PauseDuration(val minutes: Int, val label: String) {
    MINUTES_15(15, "15분"),
    MINUTES_30(30, "30분"),
    HOUR_1(60, "1시간"),
    HOURS_2(120, "2시간"),
    HOURS_4(240, "4시간"),
    HOURS_8(480, "8시간")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: DetectionSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        checkPermissions()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLoading = false) }
                manageWidgetService(settings)
            }
        }
    }

    private fun manageWidgetService(settings: DetectionSettings) {
        val isOverlayEnabled = Settings.canDrawOverlays(context)
        
        // Calculate Chronometer base time: elapsedRealtime - (currentTime - detectionStartTime)
        val currentTime = System.currentTimeMillis()
        val elapsedRealtime = android.os.SystemClock.elapsedRealtime()
        val isPaused = settings.pauseUntilTimestamp > 0 && currentTime < settings.pauseUntilTimestamp
        
        val calculatedStartTime = if (settings.detectionStartTime > 0) {
            elapsedRealtime - (currentTime - settings.detectionStartTime)
        } else {
            elapsedRealtime
        }

        val serviceIntent = Intent(context, FloatingControlService::class.java).apply {
            putExtra(FloatingControlService.EXTRA_START_TIME, calculatedStartTime)
            putExtra(FloatingControlService.EXTRA_IS_ACTIVE, settings.isDetectionEnabled)
            putExtra(FloatingControlService.EXTRA_IS_PAUSED, isPaused)
        }

        if (isOverlayEnabled && settings.isWidgetEnabled) {
            context.startService(serviceIntent)
        } else if (!settings.isWidgetEnabled || !isOverlayEnabled) {
            context.stopService(serviceIntent)
        }
    }

    fun setDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                checkPermissions()
                val currentState = _uiState.value
                if (!currentState.settings.canStartDetection(currentState.isAccessibilityEnabled, currentState.isOverlayEnabled)) {
                    return@launch
                }
                
                if (currentState.settings.disabledApps.containsAll(DetectionSettings.SUPPORTED_PACKAGES)) {
                    settingsStore.enableAllApps()
                }
            }
            settingsStore.setDetectionEnabled(enabled)
        }
    }

    fun showPauseDialog() {
        _uiState.update { it.copy(showPauseDialog = true) }
    }

    fun dismissPauseDialog() {
        _uiState.update { it.copy(showPauseDialog = false) }
    }

    fun pauseDetection(durationMinutes: Int) {
        viewModelScope.launch {
            settingsStore.pauseDetection(durationMinutes)
            _uiState.update { it.copy(showPauseDialog = false) }
        }
    }

    fun resumeDetection() {
        viewModelScope.launch {
            settingsStore.resumeDetection()
        }
    }

    fun setWidgetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setWidgetEnabled(enabled)
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAppEnabled(packageName, enabled)
            
            if (!enabled) {
                val currentDisabled = _uiState.value.settings.disabledApps + packageName
                if (currentDisabled.containsAll(DetectionSettings.SUPPORTED_PACKAGES)) {
                    setDetectionEnabled(false)
                }
            }
        }
    }
    

    fun enableAllApps() {
        viewModelScope.launch {
            settingsStore.enableAllApps()
        }
    }

    fun enableApps(packageNames: Collection<String>) {
        viewModelScope.launch {
            settingsStore.enableApps(packageNames)
        }
    }

    fun checkPermissions() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isOverlayEnabled = Settings.canDrawOverlays(context)
        
        _uiState.update {
            it.copy(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isOverlayEnabled = isOverlayEnabled
            )
        }

        val currentSettings = _uiState.value.settings
        if (!currentSettings.canStartDetection(isAccessibilityEnabled, isOverlayEnabled)) {
            if (currentSettings.isDetectionEnabled) {
                viewModelScope.launch {
                    settingsStore.setDetectionEnabled(false)
                }
            }
        }

        if (!isOverlayEnabled) {
            if (currentSettings.isWidgetEnabled) {
                viewModelScope.launch {
                    settingsStore.setWidgetEnabled(false)
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${context.packageName}/com.onguard.service.ScamDetectionAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(serviceName) == true
    }
}
