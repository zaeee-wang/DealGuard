package com.onguard.presentation.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.onguard.presentation.ui.theme.OnGuardTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 설정 화면 Activity
 *
 * 다음 설정을 관리:
 * - 전역 탐지 활성화/비활성화
 * - 시간 제한 비활성화 (일시 중지)
 * - 앱별 탐지 설정
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OnGuardTheme {
                SettingsScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }
}
