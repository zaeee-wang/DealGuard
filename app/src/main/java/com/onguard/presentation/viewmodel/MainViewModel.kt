package com.onguard.presentation.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onguard.domain.model.ScamAlert
import com.onguard.domain.repository.ScamAlertRepository
import com.onguard.service.ScamDetectionAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val scamAlertRepository: ScamAlertRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadRecentAlerts()
    }

    fun checkPermissions() {
        val context = getApplication<Application>()
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
        val overlayEnabled = Settings.canDrawOverlays(context)

        _uiState.value = _uiState.value.copy(
            isAccessibilityEnabled = accessibilityEnabled,
            isOverlayEnabled = overlayEnabled,
            isServiceRunning = accessibilityEnabled && overlayEnabled
        )
    }

    private fun loadRecentAlerts() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            scamAlertRepository.getAllAlerts()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "알림 로드 실패"
                    )
                }
                .collect { alerts ->
                    _uiState.value = _uiState.value.copy(
                        recentAlerts = alerts.take(10),
                        totalAlertCount = alerts.size,
                        isLoading = false,
                        error = null
                    )
                }
        }
    }

    fun dismissAlert(alertId: Long) {
        viewModelScope.launch {
            scamAlertRepository.markAsDismissed(alertId)
        }
    }

    fun deleteAlert(alertId: Long) {
        viewModelScope.launch {
            scamAlertRepository.deleteAlert(alertId)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(
            context,
            ScamDetectionAccessibilityService::class.java
        )

        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }

        return false
    }
}

data class MainUiState(
    val isServiceRunning: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val recentAlerts: List<ScamAlert> = emptyList(),
    val totalAlertCount: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null
)
