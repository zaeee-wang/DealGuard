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

/**
 * 스캠 경고 오버레이 서비스.
 *
 * [ScamDetectionAccessibilityService]에서 스캠 탐지 시 Intent로 호출된다.
 * 화면 상단에 경고 배너를 띄우고, [ScamAlertRepository]에 알림을 저장한다.
 * LLM이 생성한 경고 메시지·위험 요소·의심 문구를 함께 표시하며,
 * 신뢰도에 따라 배경색(빨강/주황/노랑)이 자동 변경된다.
 *
 * @see ScamAlertRepository 알림 저장
 */
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
        private const val FOREGROUND_NOTIFICATION_ID = 3001 // 포그라운드 서비스용 고정 ID
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val SCAM_ALERT_CHANNEL_ID = "scam_alert_channel" // 스캠 알림용 채널
        private const val AUTO_DISMISS_DELAY = 15000L // 15 seconds
        
        // 스캠 알림용 동적 ID 생성 (타임스탬프 기반)
        private var alertNotificationIdCounter = 4000

        // Intent extra keys
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
        Log.d(TAG, "=== OverlayService.onCreate ===")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "  - WindowManager obtained: ${windowManager != null}")
        createNotificationChannel()
        Log.d(TAG, "OverlayService created successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== OverlayService.onStartCommand called ===")
        Log.d(TAG, "  - Intent: ${intent != null}")
        Log.d(TAG, "  - Flags: $flags")
        Log.d(TAG, "  - StartId: $startId")
        
        // 오버레이 권한 체크
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        Log.d(TAG, "  - Overlay permission: $hasOverlayPermission")
        
        if (!hasOverlayPermission) {
            Log.e(TAG, "=== Overlay permission not granted - cannot show warning ===")
            Log.e(TAG, "Please enable 'Display over other apps' permission in Settings")
            stopSelf()
            return START_NOT_STICKY
        }

        // Intent에서 데이터 추출
        Log.d(TAG, "Extracting data from intent...")
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
        
        Log.d(TAG, "Extracted data:")
        Log.d(TAG, "  - Confidence: $confidence")
        Log.d(TAG, "  - Reasons: $reasonsRaw")
        Log.d(TAG, "  - Source app: $sourceApp")
        Log.d(TAG, "  - Warning message: $warningMessage")
        Log.d(TAG, "  - Scam type: $scamTypeStr")
        Log.d(TAG, "  - Suspicious parts: $suspiciousParts")
        Log.d(TAG, "  - Detected keywords: $detectedKeywords")

        // String을 List로 변환 (기존 호환성)
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

        // Save alert to database
        saveAlert(confidence, reasons, sourceApp, warningMessage, scamType, suspiciousParts)

        // Show overlay
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

        // Show stacked notification for this scam alert
        showScamAlertNotification(
            confidence = confidence,
            scamType = scamType,
            warningMessage = warningMessage
        )

        // Start foreground service (포그라운드 서비스 유지용)
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())

        return START_NOT_STICKY
    }

    /**
     * 경고 오버레이 뷰를 생성하여 화면 상단에 표시한다.
     *
     * @param confidence 위험도 (0~1, 배경색 및 퍼센트 표시용)
     * @param reasons 탐지 사유 목록
     * @param sourceApp 출처 앱 패키지명
     * @param warningMessage LLM 생성 경고 문구 (null이면 기본 문구 사용)
     * @param scamType 스캠 유형 (라벨 표시)
     * @param suspiciousParts 의심 문구 인용 목록
     * @param highRiskKeywords 고위험 키워드 목록
     * @param mediumRiskKeywords 중위험 키워드 목록
     * @param lowRiskKeywords 저위험 키워드 목록
     * @param hasCombination 복합 위험 여부
     */
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
        Log.d(TAG, "=== showOverlayWarning called ===")
        Log.d(TAG, "  - Current overlays count: ${overlayViews.size}")
        
        // 기존 오버레이가 있으면 즉시 페이드 아웃하여 제거
        if (overlayViews.isNotEmpty()) {
            removeAllOverlaysImmediately()
        }
        
        // 새 오버레이 생성 및 추가
        createAndShowOverlay(
            confidence, reasons, sourceApp, warningMessage, scamType,
            suspiciousParts, highRiskKeywords, mediumRiskKeywords, lowRiskKeywords, hasCombination
        )
    }
    
    /**
     * 모든 기존 오버레이를 즉시 페이드 아웃하여 제거
     */
    private fun removeAllOverlaysImmediately() {
        if (overlayViews.isEmpty()) return
        
        val viewsToRemove = overlayViews.toList()
        overlayViews.clear()
        
        viewsToRemove.forEach { view ->
            // 빠른 페이드 아웃 (150ms)
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
    
    /**
     * 인덱스에 따른 Y 위치 계산 (최상단에 정렬)
     */
    private fun getYPositionForIndex(index: Int): Int {
        return 0 // 최상단 (상태바 위)
    }
    
    /**
     * 새 오버레이를 생성하고 위에서 아래로 페이드 인으로 표시
     */
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
        Log.d(TAG, "Creating new overlay view from layout...")
        val inflater = LayoutInflater.from(this)
        val newOverlayView = try {
            inflater.inflate(R.layout.overlay_scam_warning, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate overlay layout", e)
            null
        }
        
        if (newOverlayView == null) {
            Log.e(TAG, "Overlay view is null after inflation - cannot show warning")
            return
        }
        
        Log.d(TAG, "Overlay view created successfully")

        // 위험도에 따른 색상 결정
        val riskColor = when {
            confidence >= 0.8f -> Color.parseColor("#E56856") // 고위험: 빨강
            confidence >= 0.6f -> Color.parseColor("#DD9443") // 중위험: 주황
            else -> Color.parseColor("#FFA705") // 저위험: 노랑
        }

        // 스캠 유형 표시 및 색상 적용
        newOverlayView.findViewById<TextView>(R.id.scam_type_text)?.apply {
            text = getScamTypeLabel(scamType)
            setTextColor(riskColor)
        }

        // 위험도 퍼센트 표시 및 색상 적용
        newOverlayView.findViewById<TextView>(R.id.confidence_text)?.apply {
            text = "${(confidence * 100).toInt()}% 위험도"
            setTextColor(riskColor)
        }

        // 헤더 아이콘 틴트 적용
        newOverlayView.findViewById<android.widget.ImageView>(R.id.header_icon_left)?.setColorFilter(riskColor)
        newOverlayView.findViewById<android.widget.ImageView>(R.id.header_icon_right)?.setColorFilter(riskColor)

        // LLM 생성 경고 메시지 또는 기본 메시지
        val displayMessage = warningMessage ?: generateDefaultWarning(scamType, confidence)
        newOverlayView.findViewById<TextView>(R.id.warning_message)?.text = displayMessage

        // 위험 요소 분석 업데이트
        updateRiskAnalysisForView(newOverlayView, highRiskKeywords, mediumRiskKeywords, lowRiskKeywords, hasCombination)

        // Set button listeners
        val btnDetails = newOverlayView.findViewById<View>(R.id.btn_details)
        val btnGoApp = newOverlayView.findViewById<View>(R.id.btn_go_app)
        val btnDismiss = newOverlayView.findViewById<View>(R.id.btn_dismiss)
        val analysisContainer = newOverlayView.findViewById<View>(R.id.analysis_container)

        // 버튼 노출 조건
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

        // Create layout params
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
            y = 0 // 최상단
        }

        // Add view to window
        Log.d(TAG, "Adding overlay view to WindowManager...")
        
        try {
            // 초기 상태: 위쪽에서 크게 시작 (앞에서 날아오는 효과)
            newOverlayView.alpha = 0f
            newOverlayView.scaleX = 1.2f
            newOverlayView.scaleY = 1.2f
            newOverlayView.translationY = -50f * resources.displayMetrics.density
            
            windowManager?.addView(newOverlayView, params)
            overlayViews.add(newOverlayView) // 리스트에 추가
            Log.i(TAG, "=== Overlay view added successfully (${overlayViews.size}/${maxOverlays}) ===")
            
            // 위에서 날아와서 제자리로 페이드 인 애니메이션 (200ms)
            newOverlayView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(200)
                .start()

            // Auto-dismiss after delay (맨 위 오버레이만)
            handler.postDelayed({
                Log.d(TAG, "=== Auto-dismissing overlay after $AUTO_DISMISS_DELAY ms ===")
                removeSpecificOverlay(newOverlayView)
            }, AUTO_DISMISS_DELAY)

        } catch (e: SecurityException) {
            Log.e(TAG, "=== SecurityException: Overlay permission issue ===", e)
            overlayViews.remove(newOverlayView)
        } catch (e: Exception) {
            Log.e(TAG, "=== Failed to add overlay view ===", e)
            e.printStackTrace()
            overlayViews.remove(newOverlayView)
        }
    }

    /**
     * [ScamType]에 대응하는 한글 라벨을 반환한다.
     *
     * @param scamType 스캠 유형
     * @return UI에 표시할 한글 문자열
     */
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

    /**
     * LLM 경고가 없을 때 사용할 기본 경고 문구를 생성한다.
     *
     * @param scamType 스캠 유형
     * @param confidence 위험도 (문구 톤 조정용)
     * @return 한글 경고 문구
     */
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

    /**
     * 탐지 결과를 [ScamAlert]로 변환하여 DB에 저장한다.
     *
     * @param confidence 위험도
     * @param reasons 탐지 사유
     * @param sourceApp 출처 앱
     * @param warningMessage 경고 문구
     * @param scamType 스캠 유형
     * @param suspiciousParts 의심 문구 목록
     */
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

    /**
     * 위험 요소 분석 섹션을 동적으로 업데이트한다.
     */
    private fun updateRiskAnalysisForView(
        view: View,
        highKeywords: List<String>,
        mediumKeywords: List<String>,
        lowKeywords: List<String>,
        hasCombination: Boolean
    ) {
        // 1. 고위험
        updateRiskRow(
            view.findViewById(R.id.high_risk_row),
            view.findViewById(R.id.high_risk_count),
            view.findViewById(R.id.high_risk_tags),
            "고위험 ${highKeywords.size}개 발견",
            highKeywords,
            R.drawable.bg_tag_high,
            Color.parseColor("#E56856")
        )

        // 2. 중위험
        updateRiskRow(
            view.findViewById(R.id.medium_risk_row),
            view.findViewById(R.id.medium_risk_count),
            view.findViewById(R.id.medium_risk_tags),
            "중위험 ${mediumKeywords.size}개 발견",
            mediumKeywords,
            R.drawable.bg_tag_medium,
            Color.parseColor("#DD9443")
        )

        // 3. 저위험
        updateRiskRow(
            view.findViewById(R.id.low_risk_row),
            view.findViewById(R.id.low_risk_count),
            view.findViewById(R.id.low_risk_tags),
            "저위험 ${lowKeywords.size}개 발견",
            lowKeywords,
            R.drawable.bg_tag_low,
            Color.parseColor("#FFA705")
        )

        // 4. 의심스러운 조합
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

    /**
     * 특정 오버레이 제거
     */
    private fun removeSpecificOverlay(view: View) {
        Log.d(TAG, "=== removeSpecificOverlay() called ===")
        
        if (!overlayViews.contains(view)) {
            Log.d(TAG, "  - View not in list")
            return
        }
        
        // 페이드 아웃 애니메이션
        view.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                try {
                    windowManager?.removeView(view)
                    overlayViews.remove(view)
                    Log.i(TAG, "=== Overlay view removed (${overlayViews.size} remaining) ===")
                    
                    // 모든 오버레이가 제거되면 서비스 종료
                    if (overlayViews.isEmpty()) {
                        stopSelf()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "=== Failed to remove overlay view ===", e)
                    e.printStackTrace()
                }
            }
            .start()
    }
    
    /**
     * 모든 오버레이 제거
     */
    private fun removeAllOverlays() {
        Log.d(TAG, "=== removeAllOverlays() called ===")
        Log.d(TAG, "  - Overlay views count: ${overlayViews.size}")
        
        overlayViews.toList().forEach { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "  - Removed overlay view")
            } catch (e: Exception) {
                Log.e(TAG, "  - Failed to remove overlay view", e)
            }
        }
        
        overlayViews.clear()
        Log.i(TAG, "=== All overlay views removed ===")
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
            
            // 1. 포그라운드 서비스용 채널 (낮은 우선순위, 소리 없음)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "스캠 감지 서비스",
                NotificationManager.IMPORTANCE_MIN // MIN으로 변경하여 조용히 실행
            ).apply {
                description = "스캠 탐지 오버레이 서비스"
                setShowBadge(false)
            }
            
            // 2. 스캠 알림용 채널 (중간 우선순위, 소리/진동 있음)
            val alertChannel = NotificationChannel(
                SCAM_ALERT_CHANNEL_ID,
                "스캠 감지 알림",
                NotificationManager.IMPORTANCE_DEFAULT // DEFAULT로 낮춤
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
    
    /**
     * 스캠 감지 시 누적되는 알림을 생성한다.
     * 각 알림은 고유한 ID를 가지며, 기존 탐지 알림 아래에 쌓인다.
     */
    private fun showScamAlertNotification(
        confidence: Float,
        scamType: ScamType,
        warningMessage: String?
    ) {
        val notificationId = alertNotificationIdCounter++
        
        // 위험도에 따른 제목 (이모지 제거)
        val title = when {
            confidence >= 0.8f -> "고위험 스캠 감지"
            confidence >= 0.6f -> "중위험 스캠 감지"
            else -> "저위험 스캠 감지"
        }
        
        // 스캠 유형 라벨
        val typeLabel = getScamTypeLabel(scamType)
        
        // 알림 메시지
        val message = warningMessage ?: generateDefaultWarning(scamType, confidence)
        
        // 앱 열기 Intent
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // DEFAULT 우선순위
            .setAutoCancel(true) // 탭하면 자동으로 사라짐
            .setContentIntent(pendingIntent)
            .setShowWhen(true) // 날짜 표시
            .setWhen(System.currentTimeMillis()) // 현재 시간으로 설정
            .setOngoing(false) // 스와이프로 삭제 가능
            .setCategory(NotificationCompat.CATEGORY_MESSAGE) // 메시지 카테고리
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
