package com.onguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import com.onguard.R
import com.onguard.data.local.DetectionSettingsDataStore
import com.onguard.presentation.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Floating Control Widget Service
 * 
 * Displays a draggable floating overlay widget when the service is running.
 * Synchronizes state (Timer, Pause/Resume) with ScamDetectionAccessibilityService.
 */
@AndroidEntryPoint
class FloatingControlService : Service() {

    @Inject
    lateinit var detectionSettingsStore: DetectionSettingsDataStore

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isExpanded = false
    private var currentEdge: Edge = Edge.RIGHT
    
    private var rootContainer: android.widget.FrameLayout? = null
    
    private var expandedView: View? = null
    private var collapsedView: View? = null
    
    // State variables
    private var isServiceActive = false
    private var isServicePaused = false
    private var serviceStartTime = 0L
    private var currentSettings = com.onguard.data.local.DetectionSettings()
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    private var lastAction = MotionEvent.ACTION_UP
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    enum class Edge {
        LEFT, RIGHT
    }

    companion object {
        private const val TAG = "FloatingControlService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "floating_control_channel"
        
        const val EXTRA_START_TIME = "start_time"
        const val ACTION_UPDATE_STATE = "com.onguard.action.UPDATE_STATE"
        const val EXTRA_IS_ACTIVE = "is_active"
        const val EXTRA_IS_PAUSED = "is_paused"
        
        private const val EDGE_SNAP_THRESHOLD_DP = 100 // dp from edge to trigger snap
        private const val SWIPE_THRESHOLD_DP = 50 // minimum swipe distance
    }

    private var settingsJob: Job? = null
    private var safetySyncJob: Job? = null
    


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingControlService created")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 1. 관찰 리스너: DataStore를 직접 실시간 관찰하여 앱/알림창과 완벽 동기화
        settingsJob = serviceScope.launch {
            detectionSettingsStore.settingsFlow.collect { settings ->
                currentSettings = settings
                isServiceActive = settings.isDetectionEnabled
                isServicePaused = settings.remainingPauseTime() > 0
                serviceStartTime = settings.calculateChronometerBase()
                
                updateUIState(currentSettings)
            }
        }

        // 2. 주기적 보정 및 권한 감시: 1초마다 보정하며 권한 상태 변화를 체크하여 UI 즉시 반영
        safetySyncJob = serviceScope.launch {
            var lastAccessibilityState: Boolean? = null
            var lastOverlayState: Boolean? = null
            
            while (true) {
                kotlinx.coroutines.delay(1000)
                
                // 권한 상태 실시간 체크
                val isAccessibilityEnabled = isAccessibilityServiceEnabled(this@FloatingControlService, ScamDetectionAccessibilityService::class.java)
                val isOverlayEnabled = Settings.canDrawOverlays(this@FloatingControlService)
                
                // 권한 상실 시 자동 탐지 중지 로직 (접근성 권한 필수)
                if (!isAccessibilityEnabled && currentSettings.isDetectionEnabled) {
                    Log.w(TAG, "Accessibility permission revoked while active. Auto-disabling detection.")
                    detectionSettingsStore.setDetectionEnabled(false)
                }

                // 권한 상태가 변경되었을 때만 UI 즉시 갱신 (아이콘 색상 등 반영)
                if (isAccessibilityEnabled != lastAccessibilityState || isOverlayEnabled != lastOverlayState) {
                    lastAccessibilityState = isAccessibilityEnabled
                    lastOverlayState = isOverlayEnabled
                    updateUIState(currentSettings)
                }

                // 타이머 시간 오차 보정
                if (isServiceActive || isServicePaused) {
                    val correctedBase = currentSettings.calculateChronometerBase()
                    
                    expandedView?.findViewById<Chronometer>(R.id.timer)?.let { 
                        if (abs(it.base - correctedBase) > 500) {
                            it.base = correctedBase
                        }
                    }
                    collapsedView?.findViewById<Chronometer>(R.id.timer_collapsed)?.let {
                        if (abs(it.base - correctedBase) > 500) {
                            it.base = correctedBase
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // 초기 뷰 생성 (최초 1회 또는 설정 변경 시)
        showFloatingWidget(serviceStartTime)
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingControlService destroyed")
        
        settingsJob?.cancel()
        safetySyncJob?.cancel()
        
        try {
            if (rootContainer != null) {
                windowManager?.removeView(rootContainer)
                rootContainer = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view", e)
        }
    }

    private fun showFloatingWidget(startTime: Long) {
        if (rootContainer != null) {
            Log.d(TAG, "Widget already showing")
            return
        }
        
        // Inflate views
        val inflater = LayoutInflater.from(this)
        expandedView = inflater.inflate(R.layout.floating_control_expanded, null)
        collapsedView = inflater.inflate(R.layout.floating_control_collapsed, null)
        
        // Setup individual views
        setupExpandedView(startTime)
        setupCollapsedView(startTime)
        
        // Initialize root container
        rootContainer = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            // Add initial view
            addView(if (isExpanded) expandedView else collapsedView)
            
            // Touch listener for drag and swipe
            setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f
                private var startY = 0f
                private var hasMoved = false
                
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val params = rootContainer?.layoutParams as? WindowManager.LayoutParams ?: return false
                    
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            startX = event.rawX
                            startY = event.rawY
                            hasMoved = false
                            lastAction = MotionEvent.ACTION_DOWN
                            return true
                        }
                        
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.rawX - initialTouchX
                            val deltaY = event.rawY - initialTouchY
                            
                            if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                                hasMoved = true
                            }
                            
                            params.x = initialX - deltaX.toInt()
                            params.y = initialY + deltaY.toInt()
                            
                            windowManager?.updateViewLayout(rootContainer, params)
                            lastAction = MotionEvent.ACTION_MOVE
                            return true
                        }
                        
                        MotionEvent.ACTION_UP -> {
                            if (!hasMoved) {
                                // Tap - toggle expand/collapse
                                toggleExpandCollapse(startTime)
                            } else {
                                // Released after drag - snap to edge
                                snapToEdge(params)
                                
                                // Check for swipe gesture
                                val swipeDistanceX = event.rawX - startX
                                val swipeThreshold = dpToPx(SWIPE_THRESHOLD_DP.toFloat())
                                
                                if (abs(swipeDistanceX) > swipeThreshold) {
                                    // Swipe detected
                                    val isSwipeToExpand = (currentEdge == Edge.RIGHT && swipeDistanceX < 0) ||
                                                          (currentEdge == Edge.LEFT && swipeDistanceX > 0)
                                    val isSwipeToCollapse = (currentEdge == Edge.RIGHT && swipeDistanceX > 0) ||
                                                            (currentEdge == Edge.LEFT && swipeDistanceX < 0)
                                    
                                    if ((isExpanded && isSwipeToCollapse) || (!isExpanded && isSwipeToExpand)) {
                                        toggleExpandCollapse(startTime)
                                    } else if (!isExpanded && isSwipeToCollapse) {
                                        // Swipe Right in Collapsed state -> Dismiss Widget
                                        dismissWidget()
                                    }
                                }
                            }
                            lastAction = MotionEvent.ACTION_UP
                            return true
                        }
                    }
                    return false
                }
            })
        }
        
        // Create layout params
        val params = createWindowParams()
        
        try {
            windowManager?.addView(rootContainer, params)
            Log.d(TAG, "Floating widget added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating widget", e)
        }
    }

    private fun dismissWidget() {
        Log.d(TAG, "Dismissing widget via swipe")
        serviceScope.launch {
            // Step 1: Slide Out animation to the right
            rootContainer?.getChildAt(0)?.animate()
                ?.translationX(dpToPx(200f).toFloat())
                ?.alpha(0f)
                ?.setDuration(300)
                ?.setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        serviceScope.launch {
                            // Step 2: Update settings to disable widget
                            detectionSettingsStore.setWidgetEnabled(false)
                            // Step 3: Stop the service (which will call onDestroy and remove view)
                            stopSelf()
                        }
                    }
                })
        }
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0 // Right edge
            y = screenHeight / 2 // Center vertically
        }
    }

    private fun setupCollapsedView(startTime: Long) {
        collapsedView?.let { view ->
            val timer = view.findViewById<Chronometer>(R.id.timer_collapsed)
            timer.base = startTime
            // DO NOT start here, manage in updateUIState
        }
    }

    private fun setupExpandedView(startTime: Long) {
        expandedView?.let { view ->
            val timer = view.findViewById<Chronometer>(R.id.timer)
            timer.base = startTime
            // DO NOT start here, manage in updateUIState
            timer.visibility = if (isServiceActive || isServicePaused) View.VISIBLE else View.GONE
            
            // Close button
            view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
                toggleExpandCollapse(startTime)
            }
            
            // Open App
            view.findViewById<View>(R.id.btn_open_app)?.setOnClickListener {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
            
            // Accessibility Settings
            view.findViewById<View>(R.id.btn_accessibility_settings)?.setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            
            // Overlay Settings
            view.findViewById<View>(R.id.btn_overlay_settings)?.setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            
            // Pause/Resume
            // Initial state: Stopped (Show Play button)
            val btnPauseResume = view.findViewById<android.view.ViewGroup>(R.id.btn_pause_resume)
            val icon = btnPauseResume?.getChildAt(0) as? android.widget.ImageView
            icon?.setImageResource(R.drawable.ic_action_play)
            btnPauseResume?.contentDescription = "재생"
            
            btnPauseResume?.setOnClickListener {
                serviceScope.launch {
                    detectionSettingsStore.setDetectionEnabled(true)
                }
            }
            
            // Stop (Initial: Hidden)
            val btnStop = view.findViewById<View>(R.id.btn_stop)
            val spacer = view.findViewById<View>(R.id.space_pause_stop)
            btnStop?.visibility = View.GONE
            spacer?.visibility = View.GONE

            // Initial Padding for Stopped state
            val capsule = view.findViewById<android.widget.LinearLayout>(R.id.capsule_pause_stop)
            capsule?.setPadding(dpToPx(8f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            
            // Stop Click Listener
            btnStop?.setOnClickListener {
                serviceScope.launch {
                    detectionSettingsStore.setDetectionEnabled(false)
                    Log.d(TAG, "Detection stopped")
                }
            }
        }
    }

    private fun toggleExpandCollapse(startTime: Long) {
        val container = rootContainer ?: return
        val currentView = container.getChildAt(0) ?: return
        
        try {
            val slideDistance = container.width.toFloat().coerceAtLeast(dpToPx(200f).toFloat())
            
            // Step 1: Slide Out current view to the right
            currentView.animate()
                .translationX(slideDistance)
                .alpha(0f)
                .setDuration(200)
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Step 2: Swap the view
                        isExpanded = !isExpanded
                        container.removeAllViews()
                        val nextView = if (isExpanded) expandedView else collapsedView
                        nextView?.let { v ->
                            // Prepare next view for Slide In
                            v.translationX = slideDistance
                            v.alpha = 0f
                            container.addView(v)
                            
                            // Ensure UI reflects current state (timer visibility, etc.)
                            updateUIState(currentSettings)
                            
                            // Step 3: Slide In next view from the right
                            v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(250)
                                .setListener(null)
                        }
                    }
                })
            
            Log.d(TAG, "Toggling with sequential slide animation to ${if (isExpanded) "expanded" else "collapsed"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle state with animation", e)
        }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        // Force snap to the right edge
        params.x = 0
        currentEdge = Edge.RIGHT
        
        windowManager?.updateViewLayout(rootContainer, params)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OnGuard Control Widget")
            .setContentText("제어 위젯 실행중")
            .setSmallIcon(R.drawable.lg_brandmark_red)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Control Widget",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "제어 위젯 서비스 알림"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun updateUIState(settings: com.onguard.data.local.DetectionSettings) {
        val isActive = settings.isDetectionEnabled
        val isPaused = settings.remainingPauseTime() > 0
        val startTime = settings.calculateChronometerBase()
        
        // Update both expanded and collapsed views
        expandedView?.let { view ->
            val timer = view.findViewById<Chronometer>(R.id.timer)
            val capsule = view.findViewById<android.widget.LinearLayout>(R.id.capsule_pause_stop)
            val btnStop = view.findViewById<View>(R.id.btn_stop)
            val spacer = view.findViewById<View>(R.id.space_pause_stop)
            
            // Timer Visibility: Gone if inactive, Visible if active/paused
            timer.visibility = if (isActive || isPaused) View.VISIBLE else View.GONE

            // Handle Pause/Play button
            val container = view.findViewById<android.view.ViewGroup>(R.id.btn_pause_resume)
            val btnAccessibility = view.findViewById<android.view.View>(R.id.btn_accessibility_settings)
            val btnOverlay = view.findViewById<android.view.View>(R.id.btn_overlay_settings)
            
            val isAccessibilityEnabled = isAccessibilityServiceEnabled(this, ScamDetectionAccessibilityService::class.java)
            val isOverlayEnabled = Settings.canDrawOverlays(this)
            
            // Check if we CAN start (using the helper from model)
            // But we need the settings object. We have it from Broadcast (in companion action logic)
            // Wait, updateUIState is called from Broadcast too.
            // Let's get the latest settings from the store if possible, or assume it's passed or available.
            // Actually, we can check basic things here.
            
            val canStart = isAccessibilityEnabled && isOverlayEnabled && 
                           !currentSettings.disabledApps.containsAll(com.onguard.data.local.DetectionSettings.SUPPORTED_PACKAGES)

            if (container != null && container.childCount > 0) {
                val icon = container.getChildAt(0) as? android.widget.ImageView
                
                if (isActive && !isPaused) {
                    // Active -> Show Pause
                    timer.base = startTime
                    timer.start()
                    icon?.setImageResource(R.drawable.ic_action_pause)
                    container.contentDescription = "일시정지"
                    container.alpha = 1.0f
                    container.setOnClickListener {
                         serviceScope.launch {
                            detectionSettingsStore.pauseDetection(60)
                        }
                    }
                    
                    // Reset Padding for Pause/Stop state
                    capsule?.setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
                    
                    // Update settings icons (in case they changed)
                    updateSettingsIcons(view)
                } else {
                    // Inactive or Paused -> Show Play
                    timer.stop()
                    timer.base = startTime
                    icon?.setImageResource(R.drawable.ic_action_play)
                    container.contentDescription = "재생"
                    
                    if (canStart) {
                        container.alpha = 1.0f
                        container.setOnClickListener {
                             serviceScope.launch {
                                 if (!isActive) {
                                     detectionSettingsStore.setDetectionEnabled(true)
                                 } else {
                                     detectionSettingsStore.resumeDetection()
                                 }
                            }
                        }
                    } else {
                        // CANNOT START -> Disable button
                        container.alpha = 0.5f
                        container.setOnClickListener {
                            android.widget.Toast.makeText(this@FloatingControlService, 
                                "권한이 없거나 선택된 앱이 없어 시작할 수 없습니다.", 
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    // Adjust Padding for Play-only state (Visually balance the triangle)
                    capsule?.setPadding(dpToPx(8f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
                    
                    // Update settings icons
                    updateSettingsIcons(view)
                }
            }
            
            // Handle Stop button visibility
            if (btnStop != null) {
                val visibility = if (isActive) View.VISIBLE else View.GONE
                btnStop.visibility = visibility
                spacer?.visibility = visibility
            }
        }
        
        collapsedView?.let { view ->
            val timer = view.findViewById<Chronometer>(R.id.timer_collapsed)
            timer.base = startTime
            
            // Timer Visibility in Collapsed State: Only show when active or paused
            timer.visibility = if (isActive || isPaused) android.view.View.VISIBLE else android.view.View.GONE
            
             if (isActive && !isPaused) {
                timer.start()
            } else {
                timer.stop()
            }
        }
    }

    private fun updateSettingsIcons(view: View) {
        // Accessibility Settings Icon
        val btnAccessibility = view.findViewById<android.widget.FrameLayout>(R.id.btn_accessibility_settings)
        val ivAccessibility = btnAccessibility?.getChildAt(0) as? android.widget.ImageView
        val isAccessibilityEnabled = isAccessibilityServiceEnabled(this, ScamDetectionAccessibilityService::class.java)
        
        if (isAccessibilityEnabled) {
            ivAccessibility?.setImageResource(R.drawable.ic_access_blue)
            btnAccessibility?.setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        } else {
            ivAccessibility?.setImageResource(R.drawable.ic_access_red)
             btnAccessibility?.setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        }

        // Overlay Settings Icon
        val btnOverlay = view.findViewById<android.widget.FrameLayout>(R.id.btn_overlay_settings)
        val ivOverlay = btnOverlay?.getChildAt(0) as? android.widget.ImageView
        val isOverlayEnabled = Settings.canDrawOverlays(this)
        
        if (isOverlayEnabled) {
            ivOverlay?.setImageResource(R.drawable.ic_overlay_blue)
             btnOverlay?.setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        } else {
            ivOverlay?.setImageResource(R.drawable.ic_overlay_red)
             btnOverlay?.setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        
        val myComponentName = android.content.ComponentName(context, service).flattenToString()
        
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(myComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
