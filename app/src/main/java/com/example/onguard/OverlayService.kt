package com.example.onguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * OverlayService는 화면 위에 항상 떠 있는 오버레이 버튼을 표시하는 서비스입니다.
 * 
 * 이 서비스는 다음 기능을 제공합니다:
 * - WindowManager를 사용하여 다른 앱 위에 오버레이 뷰 표시
 * - Jetpack Compose를 사용한 UI 구성
 * - Foreground Service로 백그라운드에서 실행
 * 
 * 주의사항:
 * - SYSTEM_ALERT_WINDOW 권한이 필요합니다.
 * - TYPE_APPLICATION_OVERLAY 타입을 사용하여 다른 앱 위에 표시됩니다.
 * - FLAG_NOT_FOCUSABLE과 FLAG_NOT_TOUCH_MODAL을 사용하여 다른 앱의 터치를 방해하지 않습니다.
 * 
 * @author OnGuard Team
 * @since 1.0
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    companion object {
        /** 알림 채널 ID */
        private const val CHANNEL_ID = "overlay_service_channel"
        
        /** 알림 ID */
        private const val NOTIFICATION_ID = 1
        
        /** 알림 채널 이름 */
        private const val CHANNEL_NAME = "Overlay Service Channel"
        
        /** 알림 채널 설명 */
        private const val CHANNEL_DESCRIPTION = "오버레이 서비스 채널"
        
        /** 알림 제목 */
        private const val NOTIFICATION_TITLE = "오버레이 서비스 실행 중"
        
        /** 알림 내용 */
        private const val NOTIFICATION_TEXT = "화면 위 오버레이 버튼이 표시됩니다"
        
        /** 오버레이 버튼 크기 (dp) */
        private const val BUTTON_SIZE_DP = 60
        
        /** 오버레이 버튼 텍스트 크기 (sp) */
        private const val BUTTON_TEXT_SIZE_SP = 24
    }

    /** WindowManager 인스턴스 */
    private var windowManager: WindowManager? = null
    
    /** 오버레이 뷰 인스턴스 */
    private var overlayView: ComposeView? = null
    
    /** SavedStateRegistryController */
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    /** SavedStateRegistryOwner 구현 */
    override val savedStateRegistry = savedStateRegistryController.savedStateRegistry

    /**
     * 서비스가 생성될 때 호출됩니다.
     * 
     * 다음 작업을 수행합니다:
     * 1. 알림 채널 생성
     * 2. Foreground Service 시작
     * 3. WindowManager 초기화
     * 4. 오버레이 뷰 생성 및 표시
     */
    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    /**
     * 알림 채널을 생성합니다.
     * 
     * Android O (API 26) 이상에서만 동작하며,
     * Foreground Service를 위해 필수입니다.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Foreground Service용 알림을 생성합니다.
     * 
     * @return 생성된 Notification 객체
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    /**
     * 오버레이 뷰를 생성하고 화면에 표시합니다.
     * 
     * 다음 작업을 수행합니다:
     * 1. ComposeView 생성 및 Lifecycle 연결
     * 2. WindowManager.LayoutParams 설정
     * 3. 화면 좌측 상단에 오버레이 뷰 추가
     * 
     * WindowManager.LayoutParams 설정:
     * - TYPE_APPLICATION_OVERLAY: 다른 앱 위에 표시
     * - FLAG_NOT_FOCUSABLE: 포커스를 받지 않음
     * - FLAG_NOT_TOUCH_MODAL: 버튼 영역만 터치를 받고 나머지는 통과
     */
    private fun createOverlay() {
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                MaterialTheme {
                    OverlayButton()
                }
            }
        }

        val layoutParams = createLayoutParams()

        windowManager?.addView(composeView, layoutParams)
        overlayView = composeView
    }

    /**
     * WindowManager.LayoutParams를 생성합니다.
     * 
     * Android O (API 26) 이상에서는 TYPE_APPLICATION_OVERLAY를 사용하고,
     * 그 이하 버전에서는 TYPE_PHONE을 사용합니다.
     * 
     * @return 설정된 WindowManager.LayoutParams 객체
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    /**
     * 오버레이 버튼을 구성하는 Composable 함수입니다.
     * 
     * 빨간색 원형 버튼을 화면 좌측 상단에 표시하며,
     * 버튼을 클릭하면 서비스를 종료합니다.
     */
    @Composable
    fun OverlayButton() {
        Box(
            modifier = Modifier
                .size(BUTTON_SIZE_DP.dp)
                .background(
                    color = Color.Red,
                    shape = CircleShape
                )
                .clickable(
                    onClick = {
                        stopSelf()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "X",
                color = Color.White,
                fontSize = BUTTON_TEXT_SIZE_SP.sp,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }

    /**
     * 서비스가 종료될 때 호출됩니다.
     * 
     * 오버레이 뷰를 WindowManager에서 제거하고
     * 리소스를 정리합니다.
     */
    override fun onDestroy() {
        super.onDestroy()
        removeOverlayView()
        cleanup()
    }

    /**
     * 오버레이 뷰를 WindowManager에서 제거합니다.
     */
    private fun removeOverlayView() {
        overlayView?.let { view ->
            windowManager?.removeView(view)
        }
    }

    /**
     * 서비스 리소스를 정리합니다.
     */
    private fun cleanup() {
        overlayView = null
        windowManager = null
    }
}
