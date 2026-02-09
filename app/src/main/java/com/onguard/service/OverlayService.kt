package com.onguard.service

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
import android.provider.Settings
import android.util.Log
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.onguard.R
import com.onguard.domain.repository.ScamAlertRepository
import com.onguard.domain.model.ScamAlert
import com.onguard.domain.model.ScamType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OverlayService : Service() {

    @Inject
    lateinit var scamAlertRepository: ScamAlertRepository

    private var windowManager: WindowManager? = null
    private val overlayViews = mutableListOf<View>() // 여러 오버레이 뷰 관리
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isTransitioning = false // 전환 중 플래그
    private val maxOverlays = 1 // 최대 1개만 표시 (새 알람이 오면 기존 알람 즉시 제거)

    companion object {
        private const val TAG = "OverlayService"
        private const val FOREGROUND_NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val SCAM_ALERT_CHANNEL_ID = "scam_alert_channel"
        private const val AUTO_DISMISS_DELAY = 15000L
        
        private var alertNotificationIdCounter = 4000

        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_REASONS = "reasons"
        const val EXTRA_SOURCE_APP = "sourceApp"
        const val EXTRA_WARNING_MESSAGE = "warningMessage"
        const val EXTRA_SCAM_TYPE = "scamType"
        const val EXTRA_SUSPICIOUS_PARTS = "suspiciousParts"
        const val EXTRA_DETECTED_KEYWORDS = "detectedKeywords"
        const val EXTRA_HIGH_RISK_KEYWORDS = "highRiskKeywords"
        const val EXTRA_MEDIUM_RISK_KEYWORDS = "mediumRiskKeywords"
        const val EXTRA_LOW_RISK_KEYWORDS = "lowRiskKeywords"
        const val EXTRA_HAS_COMBINATION = "hasCombination"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService.onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        
        if (!hasOverlayPermission) {
            Log.e(TAG, "Overlay permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        val confidence = intent?.getFloatExtra(EXTRA_CONFIDENCE, 0.5f) ?: 0.5f
        val reasonsRaw = intent?.getStringExtra(EXTRA_REASONS) ?: "스캠 의심"
        val sourceApp = intent?.getStringExtra(EXTRA_SOURCE_APP) ?: "Unknown"
        val warningMessage = intent?.getStringExtra(EXTRA_WARNING_MESSAGE)
        val scamTypeStr = intent?.getStringExtra(EXTRA_SCAM_TYPE) ?: "UNKNOWN"
        val suspiciousParts = intent?.getStringArrayListExtra(EXTRA_SUSPICIOUS_PARTS) ?: arrayListOf()
        val detectedKeywords = intent?.getStringArrayListExtra(EXTRA_DETECTED_KEYWORDS) ?: arrayListOf()
        val highRiskKeywords = intent?.getStringArrayListExtra(EXTRA_HIGH_RISK_KEYWORDS) ?: arrayListOf()
        val mediumRiskKeywords = intent?.getStringArrayListExtra(EXTRA_MEDIUM_RISK_KEYWORDS) ?: arrayListOf()
        val lowRiskKeywords = intent?.getStringArrayListExtra(EXTRA_LOW_RISK_KEYWORDS) ?: arrayListOf()
        val hasCombination = intent?.getBooleanExtra(EXTRA_HAS_COMBINATION, false) ?: false
        
        val reasons = if (reasonsRaw.contains(",")) {
            reasonsRaw.split(",").map { it.trim() }
        } else {
            listOf(reasonsRaw)
        }

        val scamType = try {
            ScamType.valueOf(scamTypeStr)
        } catch (e: Exception) {
            ScamType.UNKNOWN
        }

        Log.i(TAG, "Showing overlay: confidence=$confidence, scamType=$scamType, sourceApp=$sourceApp")

        saveAlert(confidence, reasons, sourceApp, warningMessage, scamType, suspiciousParts)

        showOverlayWarning(
            confidence = confidence,
            reasons = reasons,
            sourceApp = sourceApp,
            warningMessage = warningMessage,
            scamType = scamType,
            suspiciousParts = suspiciousParts,
            highRiskKeywords = highRiskKeywords,
            mediumRiskKeywords = mediumRiskKeywords,
            lowRiskKeywords = lowRiskKeywords,
            hasCombination = hasCombination
        )

        showScamAlertNotification(
            confidence = confidence,
            scamType = scamType,
            warningMessage = warningMessage
        )

        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())

        return START_NOT_STICKY
    }

    private fun showOverlayWarning(
        confidence: Float,
        reasons: List<String>,
        sourceApp: String,
        warningMessage: String?,
        scamType: ScamType,
        suspiciousParts: List<String>,
        highRiskKeywords: List<String>,
        mediumRiskKeywords: List<String>,
        lowRiskKeywords: List<String>,
        hasCombination: Boolean
    ) {
        if (overlayViews.isNotEmpty()) {
            removeAllOverlaysImmediately()
        }
        
        createAndShowOverlay(
            confidence, reasons, sourceApp, warningMessage, scamType,
            suspiciousParts, highRiskKeywords, mediumRiskKeywords, lowRiskKeywords, hasCombination
        )
    }
    
    private fun removeAllOverlaysImmediately() {
        if (overlayViews.isEmpty()) return
        
        val viewsToRemove = overlayViews.toList()
        overlayViews.clear()
        
        viewsToRemove.forEach { view ->
            view.animate()
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(150)
                .withEndAction {
                    try {
                        windowManager?.removeView(view)
                        Log.d(TAG, "Overlay removed immediately")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to remove overlay", e)
                    }
                }
                .start()
        }
    }
    
    private fun getYPositionForIndex(index: Int): Int {
        return 0
    }
    
    private fun createAndShowOverlay(
        confidence: Float,
        reasons: List<String>,
        sourceApp: String,
        warningMessage: String?,
        scamType: ScamType,
        suspiciousParts: List<String>,
        highRiskKeywords: List<String>,
        mediumRiskKeywords: List<String>,
        lowRiskKeywords: List<String>,
        hasCombination: Boolean
    ) {
        val inflater = LayoutInflater.from(this)
        val newOverlayView = try {
            inflater.inflate(R.layout.overlay_scam_warning, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate overlay layout", e)
            null
        }
        
        if (newOverlayView == null) {
            Log.e(TAG, "Overlay view is null after inflation")
            return
        }

        val riskColor = when {
            confidence >= 0.8f -> Color.parseColor("#E56856")
            confidence >= 0.6f -> Color.parseColor("#DD9443")
            else -> Color.parseColor("#FFA705")
        }

        newOverlayView.findViewById<TextView>(R.id.scam_type_text)?.apply {
            text = getScamTypeLabel(scamType)
            setTextColor(riskColor)
        }

        newOverlayView.findViewById<TextView>(R.id.confidence_text)?.apply {
            text = "${(confidence * 100).toInt()}% 위험도"
            setTextColor(riskColor)
        }

        newOverlayView.findViewById<android.widget.ImageView>(R.id.header_icon_left)?.setColorFilter(riskColor)
        newOverlayView.findViewById<android.widget.ImageView>(R.id.header_icon_right)?.setColorFilter(riskColor)

        val displayMessage = warningMessage ?: generateDefaultWarning(scamType, confidence)
        newOverlayView.findViewById<TextView>(R.id.warning_message)?.text = displayMessage

        updateRiskAnalysisForView(newOverlayView, highRiskKeywords, mediumRiskKeywords, lowRiskKeywords, hasCombination)

        val btnDetails = newOverlayView.findViewById<View>(R.id.btn_details)
        val btnGoApp = newOverlayView.findViewById<View>(R.id.btn_go_app)
        val btnDismiss = newOverlayView.findViewById<View>(R.id.btn_dismiss)
        val analysisContainer = newOverlayView.findViewById<View>(R.id.analysis_container)

        val hasRiskFactors = highRiskKeywords.isNotEmpty() ||
                mediumRiskKeywords.isNotEmpty() ||
                lowRiskKeywords.isNotEmpty() ||
                hasCombination

        if (!hasRiskFactors) {
            btnDetails?.visibility = View.GONE
            btnGoApp?.visibility = View.VISIBLE
        } else {
            btnDetails?.visibility = View.VISIBLE
            btnGoApp?.visibility = View.GONE
        }

        btnDetails?.setOnClickListener {
            analysisContainer?.visibility = View.VISIBLE
            btnDetails.visibility = View.GONE
            btnGoApp?.visibility = View.VISIBLE
        }

        btnGoApp?.setOnClickListener {
            val intent = Intent(this, com.onguard.presentation.ui.main.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            removeAllOverlays()
            stopSelf()
        }

        btnDismiss?.setOnClickListener {
            removeSpecificOverlay(newOverlayView)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 0
        }
        
        try {
            newOverlayView.alpha = 0f
            newOverlayView.scaleX = 1.2f
            newOverlayView.scaleY = 1.2f
            newOverlayView.translationY = -50f * resources.displayMetrics.density
            
            windowManager?.addView(newOverlayView, params)
            overlayViews.add(newOverlayView)
            Log.i(TAG, "Overlay view added (${overlayViews.size}/${maxOverlays})")
            
            newOverlayView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(200)
                .start()

            handler.postDelayed({
                removeSpecificOverlay(newOverlayView)
            }, AUTO_DISMISS_DELAY)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Overlay permission issue", e)
            overlayViews.remove(newOverlayView)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            overlayViews.remove(newOverlayView)
        }
    }

    private fun getScamTypeLabel(scamType: ScamType): String {
        return when (scamType) {
            ScamType.INVESTMENT -> "투자 사기 의심"
            ScamType.USED_TRADE -> "중고거래 사기 의심"
            ScamType.PHISHING -> "피싱 의심"
            ScamType.VOICE_PHISHING -> "보이스피싱 의심"
            ScamType.IMPERSONATION -> "사칭 의심"
            ScamType.ROMANCE -> "로맨스 스캠 의심"
            ScamType.LOAN -> "대출 사기 의심"
            ScamType.SAFE -> "정상"
            ScamType.UNKNOWN -> "사기 의심"
        }
    }

    private fun generateDefaultWarning(scamType: ScamType, confidence: Float): String {
        val level = when {
            confidence >= 0.8f -> "높은"
            confidence >= 0.6f -> "중간"
            else -> "낮은"
        }

        return when (scamType) {
            ScamType.INVESTMENT ->
                "이 메시지는 투자 사기로 의심됩니다. 고수익 보장 투자는 대부분 사기입니다."

            ScamType.USED_TRADE ->
                "중고거래 사기가 의심됩니다. 선입금 요구 시 직접 만나서 거래하세요."

            ScamType.PHISHING ->
                "피싱 링크가 포함된 것 같습니다. 의심스러운 링크를 클릭하지 마세요."

            ScamType.VOICE_PHISHING ->
                "이 전화번호는 보이스피싱/스미싱 신고 이력이 있습니다. 금전 요구에 응하지 마세요."

            ScamType.IMPERSONATION ->
                "사칭 사기가 의심됩니다. 공식 채널을 통해 신원을 확인하세요."

            ScamType.LOAN ->
                "대출 사기가 의심됩니다. 선수수료를 요구하는 대출은 불법입니다."

            else ->
                "사기 의심 메시지입니다. 주의하세요."
        }
    }

    private fun saveAlert(
        confidence: Float,
        reasons: List<String>,
        sourceApp: String,
        warningMessage: String?,
        scamType: ScamType,
        suspiciousParts: List<String>
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val alert = ScamAlert(
                    id = 0,
                    message = warningMessage ?: reasons.joinToString(", "),
                    confidence = confidence,
                    sourceApp = sourceApp,
                    detectedKeywords = suspiciousParts,
                    reasons = reasons,
                    timestamp = java.util.Date(System.currentTimeMillis()),
                    isDismissed = false
                )
                scamAlertRepository.insertAlert(alert)
                Log.d(TAG, "Alert saved to database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save alert", e)
            }
        }
    }

    private fun updateRiskAnalysisForView(
        view: View,
        highKeywords: List<String>,
        mediumKeywords: List<String>,
        lowKeywords: List<String>,
        hasCombination: Boolean
    ) {
        updateRiskRow(
            view.findViewById(R.id.high_risk_row),
            view.findViewById(R.id.high_risk_count),
            view.findViewById(R.id.high_risk_tags),
            "고위험 ${highKeywords.size}개 발견",
            highKeywords,
            R.drawable.bg_tag_high,
            Color.parseColor("#E56856")
        )

        updateRiskRow(
            view.findViewById(R.id.medium_risk_row),
            view.findViewById(R.id.medium_risk_count),
            view.findViewById(R.id.medium_risk_tags),
            "중위험 ${mediumKeywords.size}개 발견",
            mediumKeywords,
            R.drawable.bg_tag_medium,
            Color.parseColor("#DD9443")
        )

        updateRiskRow(
            view.findViewById(R.id.low_risk_row),
            view.findViewById(R.id.low_risk_count),
            view.findViewById(R.id.low_risk_tags),
            "저위험 ${lowKeywords.size}개 발견",
            lowKeywords,
            R.drawable.bg_tag_low,
            Color.parseColor("#FFA705")
        )

        val comboRow = view.findViewById<View>(R.id.combination_row)
        if (hasCombination) {
            comboRow?.visibility = View.VISIBLE
            val comboTags = view.findViewById<LinearLayout>(R.id.combination_tags)
            comboTags?.removeAllViews()
            addTagToContainer(comboTags, "긴급+금전+URL", R.drawable.bg_tag_combo, Color.parseColor("#838383"))
        } else {
            comboRow?.visibility = View.GONE
        }
    }

    private fun updateRiskRow(
        row: View?,
        countText: TextView?,
        container: LinearLayout?,
        text: String,
        keywords: List<String>,
        bgRes: Int,
        textColor: Int
    ) {
        if (keywords.isEmpty()) {
            row?.visibility = View.GONE
            return
        }

        row?.visibility = View.VISIBLE
        countText?.text = text
        container?.removeAllViews()
        keywords.forEach { keyword ->
            addTagToContainer(container, keyword, bgRes, textColor)
        }
    }

    private fun addTagToContainer(container: LinearLayout?, text: String, bgRes: Int, textColor: Int) {
        if (container == null) return
        val tagView = TextView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (container.childCount > 0) {
                    marginStart = (6 * resources.displayMetrics.density).toInt()
                }
            }
            layoutParams = params
            this.text = text
            this.textSize = 12f
            this.setTextColor(textColor)
            this.setBackgroundResource(bgRes)
            this.setPadding(
                (8 * resources.displayMetrics.density).toInt(),
                (2 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (2 * resources.displayMetrics.density).toInt()
            )
            val typeface = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    resources.getFont(R.font.pretendard)
                } else {
                    null // Fallback
                }
            } catch (e: Exception) {
                null
            }
            typeface?.let { this.typeface = it }
        }
        container.addView(tagView)
    }

    private fun removeSpecificOverlay(view: View) {
        if (!overlayViews.contains(view)) {
            return
        }
        
        view.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                try {
                    windowManager?.removeView(view)
                    overlayViews.remove(view)
                    Log.i(TAG, "Overlay view removed (${overlayViews.size} remaining)")
                    
                    if (overlayViews.isEmpty()) {
                        stopSelf()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove overlay view", e)
                }
            }
            .start()
    }
    
    private fun removeAllOverlays() {
        overlayViews.toList().forEach { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
        }
        
        overlayViews.clear()
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
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "스캠 감지 서비스",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "스캠 탐지 오버레이 서비스"
                setShowBadge(false)
            }
            
            val alertChannel = NotificationChannel(
                SCAM_ALERT_CHANNEL_ID,
                "스캠 감지 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "스캠이 감지되었을 때 표시되는 알림"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager?.createNotificationChannel(serviceChannel)
            notificationManager?.createNotificationChannel(alertChannel)
        }
    }

    private fun createForegroundNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OnGuard")
            .setContentText("오버레이 표시 중")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    
    private fun showScamAlertNotification(
        confidence: Float,
        scamType: ScamType,
        warningMessage: String?
    ) {
        val notificationId = alertNotificationIdCounter++
        
        val title = when {
            confidence >= 0.8f -> "고위험 스캠 감지"
            confidence >= 0.6f -> "중위험 스캠 감지"
            else -> "저위험 스캠 감지"
        }
        
        val typeLabel = getScamTypeLabel(scamType)
        
        val message = warningMessage ?: generateDefaultWarning(scamType, confidence)
        
        val openIntent = Intent(this, com.onguard.presentation.ui.main.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, SCAM_ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("$typeLabel - ${(confidence * 100).toInt()}% 위험도")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$typeLabel\n위험도: ${(confidence * 100).toInt()}%\n\n$message"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(notificationId, notification)
        
        Log.d(TAG, "Scam alert notification shown with ID: $notificationId")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeAllOverlays()
        Log.i(TAG, "OverlayService destroyed")
    }
}
