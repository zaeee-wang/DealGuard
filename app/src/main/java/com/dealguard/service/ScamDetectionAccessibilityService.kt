package com.dealguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dealguard.detector.HybridScamDetector
import com.dealguard.domain.model.ScamAnalysis
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import javax.inject.Inject

@AndroidEntryPoint
class ScamDetectionAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var scamDetector: HybridScamDetector

    private val targetPackages = setOf(
        // 메신저 앱
        "com.kakao.talk",                           // 카카오톡
        "org.telegram.messenger",                   // 텔레그램
        "com.whatsapp",                             // 왓츠앱
        "com.facebook.orca",                        // 페이스북 메신저
        "com.instagram.android",                    // 인스타그램

        // SMS/MMS 앱
        "com.google.android.apps.messaging",        // Google Messages
        "com.samsung.android.messaging",            // Samsung Messages
        "com.android.mms",                          // 기본 메시지 앱

        // 거래 플랫폼
        "kr.co.daangn",                             // 당근마켓
        "com.nhn.android.search",                   // 네이버 (채팅)
        "com.kakao.talk",                           // 카카오톡 (중고거래)

        // 추가 메신저
        "jp.naver.line.android",                    // 라인
        "com.tencent.mm",                           // 위챗
        "com.viber.voip",                           // 바이버
        "kik.android",                              // 킥
        "com.skype.raider",                         // 스카이프
        "com.discord",                              // 디스코드
        "com.snapchat.android"                      // 스냅챗
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var debounceJob: Job? = null
    private var lastProcessedText: WeakReference<String>? = null

    companion object {
        private const val TAG = "ScamDetectionService"
        private const val DEBOUNCE_DELAY_MS = 100L
        private const val MIN_TEXT_LENGTH = 10
        private const val SCAM_THRESHOLD = 0.5f
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 패키지 체크
        val packageName = event.packageName?.toString()
        if (packageName !in targetPackages) return

        // 이벤트 타입 체크
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                processEvent(event)
            }
            else -> return
        }
    }

    private fun processEvent(event: AccessibilityEvent) {
        // Debouncing: 이전 작업 취소
        debounceJob?.cancel()

        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_DELAY_MS)

            val node = rootInActiveWindow ?: return@launch

            try {
                val extractedText = extractTextFromNode(node)

                // 최소 길이 체크
                if (extractedText.length < MIN_TEXT_LENGTH) return@launch

                // 중복 텍스트 체크 (캐시)
                val lastText = lastProcessedText?.get()
                if (extractedText == lastText) return@launch

                // 새로운 텍스트 저장 (WeakReference)
                lastProcessedText = WeakReference(extractedText)

                Log.d(TAG, "Extracted text (${extractedText.length} chars): ${extractedText.take(100)}...")

                // 스캠 분석
                analyzeForScam(extractedText, event.packageName.toString())

            } catch (e: Exception) {
                Log.e(TAG, "Error processing event", e)
            } finally {
                node.recycle()
            }
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val textBuilder = StringBuilder()

        // Extract text from current node
        node.text?.let { textBuilder.append(it).append(" ") }
        node.contentDescription?.let { textBuilder.append(it).append(" ") }

        // Recursively extract from children
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

    private fun analyzeForScam(text: String, sourceApp: String) {
        serviceScope.launch {
            try {
                val analysis = scamDetector.analyze(text)

                Log.d(TAG, "Analysis result - isScam: ${analysis.isScam}, confidence: ${analysis.confidence}")

                if (analysis.isScam && analysis.confidence >= SCAM_THRESHOLD) {
                    showScamWarning(analysis, sourceApp)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing text for scam", e)
            }
        }
    }

    private fun showScamWarning(analysis: ScamAnalysis, sourceApp: String) {
        handler.post {
            try {
                Log.w(TAG, "SCAM DETECTED! Confidence: ${analysis.confidence}, Reasons: ${analysis.reasons}")

                // OverlayService 시작
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra("confidence", analysis.confidence)
                    putExtra("reasons", analysis.reasons.joinToString(", "))
                    putExtra("sourceApp", sourceApp)
                }
                startService(intent)

            } catch (e: Exception) {
                Log.e(TAG, "Error showing scam warning", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        debounceJob?.cancel()
        Log.i(TAG, "Accessibility Service Destroyed")
    }
}
