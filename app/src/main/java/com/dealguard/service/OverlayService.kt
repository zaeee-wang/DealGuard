package com.dealguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.dealguard.R
import com.dealguard.domain.repository.ScamAlertRepository
import com.dealguard.domain.model.ScamAlert
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/**
 * 스캠 경고 오버레이 서비스
 *
 * ScamDetectionAccessibilityService에서 스캠 탐지 시 호출됨.
 * 화면 상단에 경고 배너를 표시하고 DB에 기록.
 *
 * UI/UX팀 연동 포인트:
 * - btn_details 클릭 시 상세 화면으로 이동 (구현 필요)
 * - 배경색은 신뢰도에 따라 자동 변경 (아래 색상 로직 참고)
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject
    lateinit var scamAlertRepository: ScamAlertRepository

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val AUTO_DISMISS_DELAY = 10000L // 10 seconds
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val confidence = intent?.getFloatExtra("confidence", 0.5f) ?: 0.5f
        val reasons = intent?.getStringExtra("reasons") ?: "스캠 의심"
        val sourceApp = intent?.getStringExtra("sourceApp") ?: "Unknown"

        Log.i(TAG, "Showing overlay: confidence=$confidence, sourceApp=$sourceApp")

        // Save alert to database
        saveAlert(confidence, reasons, sourceApp)

        // Show overlay
        showOverlayWarning(confidence, reasons, sourceApp)

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        return START_NOT_STICKY
    }

    private fun showOverlayWarning(confidence: Float, reasons: String, sourceApp: String) {
        // Remove existing overlay if any
        removeOverlay()

        // Create overlay view
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_scam_warning, null)

        // 신뢰도별 배경색 (Material Design 색상)
        // - 90% 이상: 빨강 (#D32F2F) - 거의 확정적 스캠, 즉시 주의 필요
        // - 70~89%: 주황 (#F57C00) - 높은 위험, 주의 권고
        // - 50~69%: 노랑 (#FBC02D) - 의심 단계, 확인 권장
        val backgroundColor = when {
            confidence >= 0.9f -> Color.parseColor("#D32F2F")
            confidence >= 0.7f -> Color.parseColor("#F57C00")
            else -> Color.parseColor("#FBC02D")
        }

        overlayView?.setBackgroundColor(backgroundColor)

        // Set text
        overlayView?.findViewById<TextView>(R.id.warning_message)?.text =
            "⚠️ 스캠 의심 메시지 감지!"

        overlayView?.findViewById<TextView>(R.id.confidence_text)?.text =
            "위험도: ${(confidence * 100).toInt()}%"

        overlayView?.findViewById<TextView>(R.id.reasons_text)?.text = reasons

        // Set button listeners
        overlayView?.findViewById<Button>(R.id.btn_details)?.setOnClickListener {
            // TODO(UI/UX팀): AlertDetailActivity 구현 필요
            // 전달할 데이터:
            // - confidence: 위험도 (0.0~1.0)
            // - reasons: 탐지 이유 문자열
            // - sourceApp: 출처 앱 패키지명
            // 구현 내용:
            // - 상세 탐지 정보 표시
            // - "신고하기" 버튼 (KISA/경찰청 연동)
            // - "무시하기" 버튼 (DB에 isDismissed=true 저장)
            removeOverlay()
            stopSelf()
        }

        overlayView?.findViewById<Button>(R.id.btn_dismiss)?.setOnClickListener {
            removeOverlay()
            stopSelf()
        }

        // Create layout params
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 100
        }

        // Add view to window
        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay view added successfully")

            // Auto-dismiss after 10 seconds
            handler.postDelayed({
                removeOverlay()
                stopSelf()
            }, AUTO_DISMISS_DELAY)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun saveAlert(confidence: Float, reasons: String, sourceApp: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val alert = ScamAlert(
                    id = 0,
                    message = reasons,
                    confidence = confidence,
                    sourceApp = sourceApp,
                    detectedKeywords = emptyList(),
                    reasons = listOf(reasons),
                    timestamp = Date(),
                    isDismissed = false
                )
                scamAlertRepository.insertAlert(alert)
                Log.d(TAG, "Alert saved to database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save alert", e)
            }
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
                overlayView = null
                Log.d(TAG, "Overlay view removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
        }
    }

    private fun getWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "스캠 감지 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "스캠 탐지 오버레이 서비스"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DealGuard 실행 중")
            .setContentText("스캠 탐지 서비스가 실행 중입니다")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        Log.i(TAG, "OverlayService destroyed")
    }
}
