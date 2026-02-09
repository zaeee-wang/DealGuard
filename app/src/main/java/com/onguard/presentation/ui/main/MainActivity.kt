package com.onguard.presentation.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.onguard.presentation.theme.OnGuardTheme
import com.onguard.presentation.ui.dashboard.DashboardScreen
import com.onguard.presentation.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // ViewModel 주입
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-Edge 활성화 (상단바까지 콘텐츠 확장)
        enableEdgeToEdge()
        
        // 시스템 바 투명하게 설정
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // 여기에 권한 체크 로직이나 서비스 시작 로직 등은 유지 (필요 시)
        
        // 알림 클릭 시 특정 화면 이동 처리
        val goToSettings = intent.getBooleanExtra("EXTRA_GO_TO_SETTINGS", false)
        if (goToSettings) {
             val settingsIntent = android.content.Intent(this, com.onguard.presentation.ui.settings.SettingsActivity::class.java)
             startActivity(settingsIntent)
        }

        setContent {
            // 우리가 만든 앱 테마 적용
            OnGuardTheme {
                // ViewModel의 StateFlow를 Compose State로 변환
                val uiState by viewModel.uiState.collectAsState()
                
                // 새로운 대시보드 화면에 데이터(State) 주입
                DashboardScreen(state = uiState)
            }
        }
    }
}
