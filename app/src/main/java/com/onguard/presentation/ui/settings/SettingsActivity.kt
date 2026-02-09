package com.onguard.presentation.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.onguard.presentation.theme.OnGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnGuardTheme {
                SettingsScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(
            com.onguard.R.anim.slide_in_left,
            com.onguard.R.anim.slide_out_right
        )
    }
}
