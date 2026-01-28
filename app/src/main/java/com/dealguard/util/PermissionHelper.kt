package com.dealguard.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat

object PermissionHelper {

    /**
     * 접근성 서비스가 활성화되어 있는지 확인
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/${context.packageName}.service.ScamDetectionAccessibilityService"

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (enabledServices.isNullOrEmpty()) {
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    /**
     * 접근성 서비스 설정 화면으로 이동하는 Intent 생성
     */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * 오버레이 권한(SYSTEM_ALERT_WINDOW)이 허용되어 있는지 확인
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            // Android 6.0 미만에서는 설치 시 자동 허용
            true
        }
    }

    /**
     * 오버레이 권한 설정 화면으로 이동하는 Intent 생성
     */
    fun getOverlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * 알림 권한이 허용되어 있는지 확인 (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Android 13 미만에서는 알림 권한 불필요
            true
        }
    }

    /**
     * 모든 필수 권한이 허용되어 있는지 확인
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return isAccessibilityServiceEnabled(context) &&
                canDrawOverlays(context) &&
                hasNotificationPermission(context)
    }

    /**
     * 권한 상태를 로그로 출력 (디버깅용)
     */
    fun logPermissionStatus(context: Context, tag: String = "PermissionHelper") {
        android.util.Log.d(tag, """
            Permission Status:
            - Accessibility Service: ${isAccessibilityServiceEnabled(context)}
            - Overlay Permission: ${canDrawOverlays(context)}
            - Notification Permission: ${hasNotificationPermission(context)}
            - All Required: ${hasAllRequiredPermissions(context)}
        """.trimIndent())
    }

    /**
     * 권한 결과 데이터 클래스
     */
    data class PermissionStatus(
        val accessibilityEnabled: Boolean,
        val overlayEnabled: Boolean,
        val notificationEnabled: Boolean
    ) {
        val allGranted: Boolean
            get() = accessibilityEnabled && overlayEnabled && notificationEnabled

        val missingPermissions: List<String>
            get() = buildList {
                if (!accessibilityEnabled) add("접근성 서비스")
                if (!overlayEnabled) add("오버레이 권한")
                if (!notificationEnabled) add("알림 권한")
            }
    }

    /**
     * 현재 권한 상태를 객체로 반환
     */
    fun getPermissionStatus(context: Context): PermissionStatus {
        return PermissionStatus(
            accessibilityEnabled = isAccessibilityServiceEnabled(context),
            overlayEnabled = canDrawOverlays(context),
            notificationEnabled = hasNotificationPermission(context)
        )
    }
}
