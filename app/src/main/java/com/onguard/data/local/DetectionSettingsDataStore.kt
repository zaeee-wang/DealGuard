package com.onguard.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 탐지 설정 저장소 (DataStore 기반)
 *
 * 다음 설정을 관리:
 * - 전역 탐지 활성화/비활성화
 * - 시간 제한 비활성화 (일시 중지)
 * - 앱별 탐지 설정
 */

private val Context.detectionSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "detection_settings"
)

/**
 * 탐지 설정 데이터 클래스
 */
data class DetectionSettings(
    /** 전역 탐지 활성화 여부 */
    val isDetectionEnabled: Boolean = true,

    /** 일시 중지 종료 시각 (epoch ms), 0이면 일시 중지 아님 */
    val pauseUntilTimestamp: Long = 0L,

    /** 비활성화된 앱 패키지 목록 */
    val disabledApps: Set<String> = emptySet()
) {
    /**
     * 현재 탐지가 활성 상태인지 확인
     *
     * - 전역 비활성화: false
     * - 일시 중지 중: false
     * - 그 외: true
     */
    fun isActiveNow(): Boolean {
        if (!isDetectionEnabled) return false
        if (pauseUntilTimestamp > 0 && System.currentTimeMillis() < pauseUntilTimestamp) {
            return false
        }
        return true
    }

    /**
     * 특정 앱에 대해 탐지가 활성 상태인지 확인
     */
    fun isActiveForApp(packageName: String): Boolean {
        if (!isActiveNow()) return false
        return packageName !in disabledApps
    }

    /**
     * 일시 중지 남은 시간 (밀리초)
     */
    fun remainingPauseTime(): Long {
        if (pauseUntilTimestamp <= 0) return 0
        val remaining = pauseUntilTimestamp - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }
}

@Singleton
class DetectionSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.detectionSettingsDataStore

    companion object {
        private val KEY_DETECTION_ENABLED = booleanPreferencesKey("detection_enabled")
        private val KEY_PAUSE_UNTIL = longPreferencesKey("pause_until_timestamp")
        private val KEY_DISABLED_APPS = stringSetPreferencesKey("disabled_apps")
    }

    /**
     * 현재 설정을 Flow로 관찰
     */
    val settingsFlow: Flow<DetectionSettings> = dataStore.data.map { preferences ->
        DetectionSettings(
            isDetectionEnabled = preferences[KEY_DETECTION_ENABLED] ?: true,
            pauseUntilTimestamp = preferences[KEY_PAUSE_UNTIL] ?: 0L,
            disabledApps = preferences[KEY_DISABLED_APPS] ?: emptySet()
        )
    }

    /**
     * 전역 탐지 활성화/비활성화 설정
     */
    suspend fun setDetectionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_DETECTION_ENABLED] = enabled
            // 활성화 시 일시 중지 해제
            if (enabled) {
                preferences[KEY_PAUSE_UNTIL] = 0L
            }
        }
    }

    /**
     * 일시 중지 설정 (시간 제한 비활성화)
     *
     * @param durationMinutes 일시 중지 시간 (분), 0이면 해제
     */
    suspend fun pauseDetection(durationMinutes: Int) {
        dataStore.edit { preferences ->
            if (durationMinutes > 0) {
                val pauseUntil = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
                preferences[KEY_PAUSE_UNTIL] = pauseUntil
            } else {
                preferences[KEY_PAUSE_UNTIL] = 0L
            }
        }
    }

    /**
     * 일시 중지 해제
     */
    suspend fun resumeDetection() {
        dataStore.edit { preferences ->
            preferences[KEY_PAUSE_UNTIL] = 0L
        }
    }

    /**
     * 특정 앱 탐지 활성화/비활성화
     */
    suspend fun setAppEnabled(packageName: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            val currentDisabled = preferences[KEY_DISABLED_APPS] ?: emptySet()
            preferences[KEY_DISABLED_APPS] = if (enabled) {
                currentDisabled - packageName
            } else {
                currentDisabled + packageName
            }
        }
    }

    /**
     * 모든 앱 탐지 활성화 (비활성화 목록 초기화)
     */
    suspend fun enableAllApps() {
        dataStore.edit { preferences ->
            preferences[KEY_DISABLED_APPS] = emptySet()
        }
    }
}
