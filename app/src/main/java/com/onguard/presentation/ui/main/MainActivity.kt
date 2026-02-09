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

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val goToSettings = intent.getBooleanExtra("EXTRA_GO_TO_SETTINGS", false)
        if (goToSettings) {
             val settingsIntent = android.content.Intent(this, com.onguard.presentation.ui.settings.SettingsActivity::class.java)
             startActivity(settingsIntent)
        }

        setContent {
            OnGuardTheme {
                val uiState by viewModel.uiState.collectAsState()
                DashboardScreen(
                    state = uiState,
                    viewModel = viewModel
                )
            }
        }
    }
}
