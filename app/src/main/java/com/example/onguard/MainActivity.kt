package com.example.onguard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * MainActivity는 오버레이 권한을 확인하고 오버레이 서비스를 시작하는 메인 액티비티입니다.
 * 
 * 이 액티비티는 다음 기능을 제공합니다:
 * - 오버레이 권한 상태 확인
 * - 오버레이 권한 요청 (설정 화면으로 이동)
 * - 오버레이 서비스 시작
 * 
 * @author OnGuard Team
 * @since 1.0
 */
class MainActivity : ComponentActivity() {

    /**
     * 액티비티가 생성될 때 호출됩니다.
     * Compose UI를 설정합니다.
     * 
     * @param savedInstanceState 이전에 저장된 인스턴스 상태
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContent {
                MainScreen()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    /**
     * 메인 화면을 구성하는 Composable 함수입니다.
     * 
     * 오버레이 권한 상태에 따라 다른 UI를 표시합니다:
     * - 권한 없음: 권한 요청 버튼 표시
     * - 권한 있음: 오버레이 시작 버튼 표시
     * 
     * 설정 화면에서 돌아왔을 때 권한 상태를 자동으로 갱신합니다.
     */
    @Composable
    fun MainScreen() {
        var hasOverlayPermission by remember { mutableStateOf(checkOverlayPermission()) }
        val lifecycleOwner = LocalLifecycleOwner.current

        // 설정 화면에서 돌아왔을 때 권한 상태를 다시 확인
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasOverlayPermission = checkOverlayPermission()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "오버레이 버튼 예제",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                if (!hasOverlayPermission) {
                    PermissionRequestSection(
                        onRequestPermission = {
                            requestOverlayPermission()
                            hasOverlayPermission = checkOverlayPermission()
                        }
                    )
                } else {
                    OverlayStartSection(
                        onStartOverlay = {
                            startOverlayService()
                        }
                    )
                }
            }
        }
    }

    /**
     * 권한 요청 섹션을 표시하는 Composable 함수입니다.
     * 
     * @param onRequestPermission 권한 요청 버튼 클릭 시 호출될 콜백
     */
    @Composable
    private fun PermissionRequestSection(onRequestPermission: () -> Unit) {
        Text(
            text = "오버레이 권한이 필요합니다.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Button(onClick = onRequestPermission) {
            Text("권한 설정으로 이동")
        }
    }

    /**
     * 오버레이 시작 섹션을 표시하는 Composable 함수입니다.
     * 
     * @param onStartOverlay 오버레이 시작 버튼 클릭 시 호출될 콜백
     */
    @Composable
    private fun OverlayStartSection(onStartOverlay: () -> Unit) {
        Button(onClick = onStartOverlay) {
            Text("오버레이 시작")
        }
    }

    /**
     * 현재 오버레이 권한이 허용되어 있는지 확인합니다.
     * 
     * Android M (API 23) 이상에서는 Settings.canDrawOverlays()를 사용하고,
     * 그 이하 버전에서는 항상 true를 반환합니다.
     * 
     * @return 오버레이 권한이 허용되어 있으면 true, 그렇지 않으면 false
     */
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * 오버레이 권한 설정 화면으로 이동합니다.
     * 
     * Android M (API 23) 이상에서만 동작하며,
     * ACTION_MANAGE_OVERLAY_PERMISSION Intent를 사용하여
     * 시스템 설정 화면으로 이동합니다.
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    /**
     * 오버레이 서비스를 시작합니다.
     * 
     * Android O (API 26) 이상에서는 startForegroundService()를 사용하고,
     * 그 이하 버전에서는 startService()를 사용합니다.
     */
    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
