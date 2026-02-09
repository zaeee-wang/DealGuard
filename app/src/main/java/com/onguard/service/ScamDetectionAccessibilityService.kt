package com.onguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.onguard.data.local.DetectionSettings
import com.onguard.data.local.DetectionSettingsDataStore
import com.onguard.detector.HybridScamDetector
import com.onguard.domain.model.ScamAnalysis
import com.onguard.util.DebugLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import javax.inject.Inject

import android.util.Log
import java.util.ArrayList
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.onguard.R
import com.onguard.presentation.ui.main.MainActivity
import android.widget.Toast
import android.provider.Settings

@AndroidEntryPoint
class ScamDetectionAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var scamDetector: HybridScamDetector

    @Inject
    lateinit var detectionSettingsStore: DetectionSettingsDataStore

    private val targetPackages = setOf(
        "com.kakao.talk",
        "org.telegram.messenger",
        "com.whatsapp",
        "com.facebook.orca",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "kr.co.daangn",
        "com.nhn.android.search",
        "jp.naver.line.android",
        "com.tencent.mm",
        "com.viber.voip",
        "kik.android",
        "com.skype.raider",
        "com.discord",
        "com.snapchat.android"
    )

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val handler = Handler(Looper.getMainLooper())
    private var debounceJob: Job? = null
    private var lastProcessedText: WeakReference<String>? = null

    private val recentAlertCache = mutableMapOf<String, Long>()

    @Volatile
    private var lastScrollTimestamp: Long = 0L

    companion object {
        private const val TAG = "OnGuardService"

        private val SKIP_VIEW_CLASSES = setOf(
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "android.inputmethodservice.InputMethodService",
            "SoftKeyboard",
            "Keyboard",
            "Ime"
        )

        private val SKIP_RESOURCE_ID_PATTERNS = setOf(
            "input", "edit", "search", "password", "id_input", "query"
        )

        private const val DEBOUNCE_DELAY_MS = 100L
        private const val MIN_TEXT_LENGTH = 10
        private const val SCAM_THRESHOLD = 0.5f
        private const val SCROLL_MODE_TIMEOUT_MS = 3_000L
        private const val CACHE_EXPIRY_MS = 3_600_000L
        private const val CACHE_MAX_SIZE = 100

        private val KEYBOARD_PACKAGE_PREFIXES = setOf(
            "com.google.android.inputmethod",
            "com.samsung.android.honeyboard",
            "com.swiftkey",
            "com.touchtype.swiftkey",
            "com.sec.android.inputmethod",
            "com.lge.ime",
            "com.huawei.inputmethod"
        )

        private val ACCESSIBILITY_LABEL_PATTERNS = listOf(
            "버튼", "button", "탭", "tab", "메뉴", "menu",
            "닫기", "close", "열기", "open", "클릭", "click",
            "선택", "select", "확인", "ok", "취소", "cancel",
            "뒤로", "back", "전송", "send", "검색", "search"
        )

        private val SUPPORTED_PACKAGES = setOf(
            "com.kakao.talk",
            "org.telegram.messenger",
            "jp.naver.line.android",
            "com.facebook.orca",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.instagram.android",
            "com.whatsapp",
            "com.discord",
            "kr.co.daangn"
        )

        private const val CHANNEL_ID = "on_guard_service_channel"
        private const val NOTIFICATION_ID = 1001
        
        // Actions
        private const val ACTION_PAUSE = "com.onguard.action.PAUSE"
        private const val ACTION_RESUME = "com.onguard.action.RESUME"
        private const val ACTION_STOP = "com.onguard.action.STOP"
        private const val ACTION_START = "com.onguard.action.START"
        private const val ACTION_OPEN = "com.onguard.action.OPEN"
    }

    private var currentSettings: DetectionSettings = DetectionSettings()

    private val isPaused: Boolean
        get() = currentSettings.remainingPauseTime() > 0

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                handleNotificationAction(action)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        
        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
            addAction(ACTION_STOP)
            addAction(ACTION_START)
            addAction(ACTION_OPEN)
        }
        ContextCompat.registerReceiver(
            this,
            notificationActionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        DebugLog.infoLog(TAG) { "step=service_connected" }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info

        observeSettings()
    }

    private fun observeSettings() {
        serviceScope.launch {
            detectionSettingsStore.settingsFlow.collect { settings ->
                val isOverlayEnabled = android.provider.Settings.canDrawOverlays(this@ScamDetectionAccessibilityService)
                val isAccessibilityEnabled = true // If we are here, accessibility is enabled
                
                // Strict Failsafe: If conditions are not met, force disable detection
                if (!settings.canStartDetection(isAccessibilityEnabled, isOverlayEnabled)) {
                    if (settings.isDetectionEnabled) {
                        detectionSettingsStore.setDetectionEnabled(false)
                        return@collect
                    }
                }

                val previousSettings = currentSettings
                currentSettings = settings
                
                if (previousSettings.isDetectionEnabled != settings.isDetectionEnabled ||
                    previousSettings.pauseUntilTimestamp != settings.pauseUntilTimestamp ||
                    previousSettings.isWidgetEnabled != settings.isWidgetEnabled) {
                    updateForegroundNotification()
                    manageFloatingControlService()
                }

                validateSafetyAndDisableIfNeeded()

                DebugLog.infoLog(TAG) {
                    "step=settings_updated enabled=${settings.isDetectionEnabled} " +
                    "pauseUntil=${settings.pauseUntilTimestamp} " +
                    "disabledApps=${settings.disabledApps.size}"
                }
            }
        }
    }

    private fun validateSafetyAndDisableIfNeeded() {
        if (!currentSettings.isDetectionEnabled) return

        val isOverlayEnabled = Settings.canDrawOverlays(this)
        val isAccessibilityEnabled = true
        
        if (!currentSettings.canStartDetection(isAccessibilityEnabled, isOverlayEnabled)) {
            serviceScope.launch {
                detectionSettingsStore.setDetectionEnabled(false)
            }
            handler.post {
                val reason = if (!isOverlayEnabled) "권한 해제" else "모든 앱 비활성화"
                Toast.makeText(this, "보호 기능이 중단되었습니다 ($reason).", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Volatile
    private var cachedSettings: DetectionSettings = DetectionSettings()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        validateSafetyAndDisableIfNeeded()

        val packageName = event.packageName?.toString()
        if (packageName !in targetPackages) return

        if (!cachedSettings.isActiveForApp(packageName ?: "")) {
            DebugLog.debugLog(TAG) {
                "step=on_event skip reason=detection_disabled app=$packageName"
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                DebugLog.debugLog(TAG) {
                    "step=on_event type=NOTIFICATION package=$packageName"
                }
                processNotificationEvent(event)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollTimestamp = System.currentTimeMillis()
                Log.d(TAG, "Scroll detected - entering scroll mode")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                DebugLog.debugLog(TAG) {
                    "step=on_event type=${event.eventType} package=$packageName"
                }
                processEvent(event)
            }

            else -> return
        }
    }

    private fun processNotificationEvent(event: AccessibilityEvent) {
        val sourceApp = event.packageName?.toString() ?: return
        val textList = event.text ?: return

        val validText = StringBuilder()
        for (charSequence in textList) {
            if (charSequence.isNotEmpty()) {
                validText.append(charSequence).append(" ")
            }
        }

        val extractedText = validText.toString().trim()

        if (extractedText.length < MIN_TEXT_LENGTH) return

        val lastText = lastProcessedText?.get()
        if (extractedText == lastText) return

        lastProcessedText = WeakReference(extractedText)

        DebugLog.debugLog(TAG) {
            val masked = DebugLog.maskText(extractedText, maxLen = 50)
            "step=notification_text_extracted length=${extractedText.length} masked=\"$masked\""
        }

        analyzeForScam(extractedText, sourceApp)
    }

    private fun processEvent(event: AccessibilityEvent) {
        debounceJob?.cancel()

        val sourceApp = event.packageName?.toString() ?: return

        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_DELAY_MS)

            var node: AccessibilityNodeInfo? = null
            var retryCount = 0
            val maxRetries = 3

            while (node == null && retryCount < maxRetries) {
                node = rootInActiveWindow
                if (node == null) {
                    retryCount++
                    delay(50)
                }
            }

            if (node == null) {
                Log.w(TAG, "rootInActiveWindow is null after $maxRetries retries")
                return@launch
            }

            val activePackage = node.packageName?.toString()
            if (activePackage != sourceApp) {
                Log.d(TAG, "Active window package ($activePackage) != Source app ($sourceApp) - skipping analysis")
                return@launch
            }

            if (!cachedSettings.isActiveForApp(sourceApp)) {
                Log.d(TAG, "Detection disabled for $sourceApp - skipping analysis")
                return@launch
            }

            try {
                if (!isInsideChatRoom(node)) {
                    Log.d(TAG, "Not inside chat room (no input field) - skipping")
                    return@launch
                }

                val extractedText = extractTextFromNode(node)

                if (extractedText.length < MIN_TEXT_LENGTH) {
                    DebugLog.debugLog(TAG) {
                        "step=process_event skip reason=too_short length=${extractedText.length}"
                    }
                    return@launch
                }

                val lastText = lastProcessedText?.get()
                if (extractedText == lastText) {
                    DebugLog.debugLog(TAG) { "step=process_event skip reason=duplicate_text" }
                    return@launch
                }

                lastProcessedText = WeakReference(extractedText)

                DebugLog.debugLog(TAG) {
                    val masked = DebugLog.maskText(extractedText, maxLen = 50)
                    "step=text_extracted length=${extractedText.length} masked=\"$masked\""
                }

                analyzeForScam(extractedText, sourceApp)

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error processing event", e)
            } finally {
                node.recycle()
            }
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        if (shouldSkipEntireSubtree(node)) {
            return ""
        }

        val textBuilder = StringBuilder()

        if (!shouldSkipNodeTextOnly(node)) {
            node.text?.let { text ->
                if (text.length >= 3) {
                    textBuilder.append(text).append(" ")
                }
            }

            node.contentDescription?.let { desc ->
                if (desc.length >= 5 && !isAccessibilityLabel(desc)) {
                    textBuilder.append(desc).append(" ")
                }
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    textBuilder.append(extractTextFromNode(child))
                } finally {
                    child.recycle()
                }
            }
        }

        return textBuilder.toString().trim()
    }

    private fun shouldSkipEntireSubtree(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) {
            DebugLog.debugLog(TAG) { "step=skip_node reason=editable" }
            return true
        }

        val className = node.className?.toString() ?: ""
        if (SKIP_VIEW_CLASSES.any { className.contains(it, ignoreCase = true) }) {
            DebugLog.debugLog(TAG) { "step=skip_node reason=class_match class=$className" }
            return true
        }

        val resourceId = node.viewIdResourceName ?: ""
        if (resourceId.isNotEmpty() && SKIP_RESOURCE_ID_PATTERNS.any {
            resourceId.contains(it, ignoreCase = true)
        }) {
            DebugLog.debugLog(TAG) { "step=skip_node reason=resource_id resourceId=$resourceId" }
            return true
        }

        try {
            val windowPackage = node.packageName?.toString()
            if (windowPackage != null && KEYBOARD_PACKAGE_PREFIXES.any {
                windowPackage.startsWith(it)
            }) {
                DebugLog.debugLog(TAG) { "step=skip_node reason=keyboard_package package=$windowPackage" }
                return true
            }
        } catch (e: Exception) {
        }

        return false
    }

    private fun shouldSkipNodeTextOnly(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        val inputClasses = setOf(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.MultiAutoCompleteTextView",
            "android.widget.SearchView"
        )
        if (inputClasses.any { className.contains(it, ignoreCase = true) }) {
            return true
        }

        val resourceId = node.viewIdResourceName ?: ""
        val strictSkipPatterns = setOf(
            "toolbar", "action_bar", "status_bar", "navigation_bar"
        )
        if (resourceId.isNotEmpty() && strictSkipPatterns.any {
            resourceId.contains(it, ignoreCase = true)
        }) {
            return true
        }

        return false
    }

    private fun isAccessibilityLabel(text: CharSequence): Boolean {
        val lowerText = text.toString().lowercase()
        return ACCESSIBILITY_LABEL_PATTERNS.any { lowerText.contains(it) }
    }

    private fun isInsideChatRoom(rootNode: AccessibilityNodeInfo): Boolean {
        return hasMessageInputField(rootNode)
    }

    private fun hasMessageInputField(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 15) return false

        if (node.isEditable) {
            Log.d(TAG, "Found editable node - inside chat room")
            return true
        }

        val className = node.className?.toString() ?: ""
        val inputClasses = setOf(
            "EditText",
            "BasicTextField",           // Jetpack Compose
            "CoreTextField",             // Compose internal
            "TextField",
            "TextInput",
            "ComposeView"                // Compose 컨테이너
        )
        if (inputClasses.any { className.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "Found input class: $className - inside chat room")
            return true
        }

        // 3. 입력 관련 리소스 ID 확인 (당근마켓 등 커스텀 앱 패턴 추가)
        val resourceId = node.viewIdResourceName ?: ""
        val inputPatterns = setOf(
            // 일반 패턴
            "input", "edit", "compose", "message_input", "chat_input",
            "text_input", "send_text", "write", "reply",
            // 당근마켓/중고거래 앱 패턴
            "message_box", "chat_field", "dm_input", "write_area",
            "input_wrapper", "text_container", "chat_box", "msg_input",
            // 전송 버튼도 채팅방 표시로 간주
            "send_button", "send_btn", "btn_send", "submit"
        )
        if (resourceId.isNotEmpty() && inputPatterns.any {
            resourceId.contains(it, ignoreCase = true)
        }) {
            Log.d(TAG, "Found input resource ID: $resourceId - inside chat room")
            return true
        }

        // 4. contentDescription으로 입력 필드 감지 (접근성 라벨 기반)
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val inputDescPatterns = setOf(
            "메시지 입력", "메시지를 입력", "내용 입력", "채팅 입력",
            "message input", "type a message", "write message",
            "전송", "보내기"
        )
        if (contentDesc.isNotEmpty() && inputDescPatterns.any { contentDesc.contains(it) }) {
            Log.d(TAG, "Found input contentDescription: $contentDesc - inside chat room")
            return true
        }

        // 5. 자식 노드 재귀 탐색
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    if (hasMessageInputField(child, depth + 1)) {
                        return true
                    }
                } finally {
                    child.recycle()
                }
            }
        }

        return false
    }

    private fun isInScrollMode(): Boolean {
        val elapsed = System.currentTimeMillis() - lastScrollTimestamp
        return elapsed < SCROLL_MODE_TIMEOUT_MS
    }

    private fun generateAlertCacheKey(
        detectedKeywords: List<String>,
        sourceApp: String
    ): String {
        val sortedKeywords = detectedKeywords.sorted().joinToString(",")
        return "$sourceApp:$sortedKeywords"
    }

    /**
     * 캐시에 해당 키워드 조합이 존재하는지 확인 (만료 체크 포함)
     *
     * @param cacheKey 캐시 키
     * @return true if cache hit (within CACHE_EXPIRY_MS)
     */
    private fun isCached(cacheKey: String): Boolean {
        val lastAlertTime = recentAlertCache[cacheKey] ?: return false
        val elapsed = System.currentTimeMillis() - lastAlertTime

        if (elapsed > CACHE_EXPIRY_MS) {
            recentAlertCache.remove(cacheKey)
            return false
        }

        return true
    }

    private fun registerAlert(cacheKey: String) {
        val now = System.currentTimeMillis()

        recentAlertCache.entries.removeIf { (_, time) ->
            now - time > CACHE_EXPIRY_MS
        }

        if (recentAlertCache.size >= CACHE_MAX_SIZE) {
            val oldest = recentAlertCache.minByOrNull { it.value }?.key
            oldest?.let { recentAlertCache.remove(it) }
        }

        recentAlertCache[cacheKey] = now
    }

    private fun analyzeForScam(text: String, sourceApp: String) {
        serviceScope.launch {
            try {
                DebugLog.debugLog(TAG) {
                    val masked = DebugLog.maskText(text, maxLen = 50)
                    "step=analyze_for_scam_start app=$sourceApp length=${text.length} masked=\"$masked\""
                }
                val analysis = scamDetector.analyze(text)

                DebugLog.debugLog(TAG) {
                    "step=analyze_for_scam_result isScam=${analysis.isScam} confidence=${analysis.confidence} method=${analysis.detectionMethod}"
                }

                if (analysis.isScam && analysis.confidence >= SCAM_THRESHOLD) {
                    showScamWarning(analysis, sourceApp)
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error analyzing text for scam", e)
            }
        }
    }

    private fun showScamWarning(analysis: ScamAnalysis, sourceApp: String) {
        handler.post {
            try {
                DebugLog.warnLog(TAG) {
                    "step=show_warning type=${analysis.scamType} confidence=${analysis.confidence} sourceApp=$sourceApp"
                }

                // OverlayService 시작 (새 필드 포함)
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_CONFIDENCE, analysis.confidence)
                    putExtra(OverlayService.EXTRA_REASONS, analysis.reasons.joinToString(", "))
                    putExtra(OverlayService.EXTRA_SOURCE_APP, sourceApp)

                    putExtra(OverlayService.EXTRA_WARNING_MESSAGE, analysis.warningMessage)
                    putExtra(OverlayService.EXTRA_SCAM_TYPE, analysis.scamType.name)
                    putStringArrayListExtra(
                        OverlayService.EXTRA_SUSPICIOUS_PARTS,
                        ArrayList(analysis.suspiciousParts)
                    )
                    putStringArrayListExtra(
                        OverlayService.EXTRA_DETECTED_KEYWORDS,
                        ArrayList(analysis.detectedKeywords)
                    )
                    putStringArrayListExtra(
                        OverlayService.EXTRA_HIGH_RISK_KEYWORDS,
                        ArrayList(analysis.highRiskKeywords)
                    )
                    putStringArrayListExtra(
                        OverlayService.EXTRA_MEDIUM_RISK_KEYWORDS,
                        ArrayList(analysis.mediumRiskKeywords)
                    )
                    putStringArrayListExtra(
                        OverlayService.EXTRA_LOW_RISK_KEYWORDS,
                        ArrayList(analysis.lowRiskKeywords)
                    )
                    putExtra(
                        OverlayService.EXTRA_HAS_COMBINATION,
                        analysis.hasSuspiciousCombination
                    )
                }
                
                Log.d(TAG, "Starting OverlayService with intent...")
                val result = startService(intent)
                Log.d(TAG, "startService() returned: $result")
                
                if (result == null) {
                    Log.e(TAG, "startService() returned null - service may not be started")
                } else {
                    Log.i(TAG, "OverlayService started successfully")
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error showing scam warning", e)
            }
        }
    }

    override fun onInterrupt() {
        DebugLog.infoLog(TAG) { "step=service_interrupted" }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        debounceJob?.cancel()
        try {
            unregisterReceiver(notificationActionReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        stopForeground(true)
        DebugLog.infoLog(TAG) { "step=service_destroyed" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OnGuard Scam Detection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows active scam detection status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateForegroundNotification() {
        val notification = buildNotification()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val isEnabled = currentSettings.isDetectionEnabled
        val isPaused = currentSettings.remainingPauseTime() > 0
        
        val contentTitle: String
        val iconRes = R.drawable.lg_brandmark_red // 3rd image style icon
        
        val baseTime = currentSettings.calculateChronometerBase()

        val openIntent = getPendingIntent(ACTION_OPEN)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)

        if (!isEnabled) {
            contentTitle = "OnGuard가 꺼져 있습니다."
            builder.setContentTitle(contentTitle)
            builder.setShowWhen(false)
            builder.setUsesChronometer(false)
            builder.addAction(R.drawable.ic_action_play, "켜기", getPendingIntent(ACTION_START))
        } else if (isPaused) {
            contentTitle = "OnGuard가 일시중지되었습니다."
            builder.setContentTitle(contentTitle)
            builder.setShowWhen(false)
            builder.setUsesChronometer(false)
            builder.addAction(R.drawable.ic_action_play, "다시 시작", getPendingIntent(ACTION_RESUME))
            builder.addAction(R.drawable.ic_action_end, "종료", getPendingIntent(ACTION_STOP))
        } else {
            val remoteViews = RemoteViews(packageName, R.layout.view_notification_active_body)
            remoteViews.setChronometer(R.id.notification_timer, baseTime, null, true)
            
            builder.setContentTitle("OnGuard가 탐지중입니다.")
            builder.setCustomBigContentView(remoteViews)
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            builder.setShowWhen(false)
            
            builder.addAction(R.drawable.ic_action_pause, "일시정지", getPendingIntent(ACTION_PAUSE))
            builder.addAction(R.drawable.ic_action_end, "종료", getPendingIntent(ACTION_STOP))
        }

        return builder.build()
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(packageName)
        }
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleNotificationAction(action: String) {
        serviceScope.launch {
            when (action) {
                ACTION_PAUSE -> {
                    detectionSettingsStore.pauseDetection(60)
                }
                ACTION_RESUME, ACTION_START -> {
                    val isOverlayEnabled = Settings.canDrawOverlays(this@ScamDetectionAccessibilityService)
                    val isAnyAppEnabled = SUPPORTED_PACKAGES.any { it !in currentSettings.disabledApps }

                    if (!isOverlayEnabled || !isAnyAppEnabled) {
                        handler.post {
                            Toast.makeText(
                                this@ScamDetectionAccessibilityService,
                                "탐지 시작을 위해 권한 허용 및 앱 설정이 필요합니다.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        
                        val intent = Intent(this@ScamDetectionAccessibilityService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("EXTRA_GO_TO_SETTINGS", true)
                        }
                        startActivity(intent)
                    } else {
                        if (action == ACTION_START) {
                            detectionSettingsStore.setDetectionEnabled(true)
                        } else {
                            detectionSettingsStore.resumeDetection()
                        }
                    }
                }
                ACTION_STOP -> {
                    detectionSettingsStore.setDetectionEnabled(false)
                }
                ACTION_OPEN -> {
                    val intent = Intent(this@ScamDetectionAccessibilityService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                }
            }
        }
    }

    
    private fun manageFloatingControlService() {
        broadcastServiceState()
    }

    private fun broadcastServiceState() {
        val baseTime = currentSettings.calculateChronometerBase()

        val intent = Intent(FloatingControlService.ACTION_UPDATE_STATE).apply {
            putExtra(FloatingControlService.EXTRA_IS_ACTIVE, currentSettings.isDetectionEnabled)
            putExtra(FloatingControlService.EXTRA_IS_PAUSED, isPaused)
            putExtra(FloatingControlService.EXTRA_START_TIME, baseTime)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
