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

        // 추가 메신저
        "jp.naver.line.android",                    // 라인
        "com.tencent.mm",                           // 위챗
        "com.viber.voip",                           // 바이버
        "kik.android",                              // 킥
        "com.skype.raider",                         // 스카이프
        "com.discord",                              // 디스코드
        "com.snapchat.android"                      // 스냅챗
    )

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val handler = Handler(Looper.getMainLooper())
    private var debounceJob: Job? = null
    private var lastProcessedText: WeakReference<String>? = null

    companion object {
        private const val TAG = "ScamDetectionService"

        // 디바운스 지연: 100ms
        // - 사용자 타이핑 중 과도한 분석 방지
        // - 100ms 미만: CPU/배터리 과다 사용
        // - 200ms 초과: UX 반응성 저하
        private const val DEBOUNCE_DELAY_MS = 100L

        // 최소 텍스트 길이: 20자 (증가됨)
        // - 너무 짧은 텍스트는 스캠 판정 불가
        // - "안녕하세요"(5자) 같은 일반 인사 필터링
        // - 스캠 메시지는 보통 20자 이상
        // - 키보드/UI 요소 텍스트 필터링 강화
        private const val MIN_TEXT_LENGTH = 20

        // 스캠 임계값: 0.5 (50%)
        // - 오탐(false positive) 최소화와 미탐(false negative) 균형점
        // - 0.3 이하: 오탐 증가 (일반 메시지도 스캠 판정)
        // - 0.7 이상: 미탐 증가 (실제 스캠 놓침)
        // - KeywordMatcher와 연동: CRITICAL 2개 조합(0.8) 또는 CRITICAL+HIGH(0.65)
        private const val SCAM_THRESHOLD = 0.5f

        // ========== 노드 필터링 설정 (False Positive 방지) ==========

        // 스킵할 뷰 클래스 (입력 필드, 키보드 등)
        // - 이 클래스들은 메시지 콘텐츠가 아닌 UI 요소
        private val SKIP_VIEW_CLASSES = setOf(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.MultiAutoCompleteTextView",
            "android.widget.SearchView",
            "android.inputmethodservice.Keyboard",
            "android.inputmethodservice.KeyboardView",
            "android.widget.NumberPicker",
            "android.widget.DatePicker",
            "android.widget.TimePicker"
        )

        // 키보드/IME 패키지 프리픽스
        // - 키보드 앱에서 오는 텍스트는 분석하지 않음
        private val KEYBOARD_PACKAGE_PREFIXES = setOf(
            "com.google.android.inputmethod",    // Gboard
            "com.samsung.android.honeyboard",    // Samsung Keyboard
            "com.swiftkey",                       // SwiftKey
            "com.touchtype.swiftkey",
            "com.sec.android.inputmethod",       // Samsung default
            "com.lge.ime",                        // LG Keyboard
            "com.huawei.inputmethod"             // Huawei Keyboard
        )

        // 스킵할 리소스 ID 패턴
        // - 이 패턴이 포함된 리소스 ID는 UI 요소로 간주
        private val SKIP_RESOURCE_ID_PATTERNS = setOf(
            "input", "edit", "compose", "search", "keyboard",
            "toolbar", "action_bar", "status_bar", "navigation",
            "title", "header", "footer", "menu", "button"
        )

        // 접근성 라벨로 간주할 패턴 (버튼, 메뉴 등의 설명)
        private val ACCESSIBILITY_LABEL_PATTERNS = listOf(
            "버튼", "button", "탭", "tab", "메뉴", "menu",
            "닫기", "close", "열기", "open", "클릭", "click",
            "선택", "select", "확인", "ok", "취소", "cancel",
            "뒤로", "back", "전송", "send", "검색", "search"
        )
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

    /**
     * 텍스트 추출 (노드 필터링 적용)
     *
     * False Positive 방지를 위해 다음 노드는 스킵:
     * - 편집 가능 필드 (EditText 등)
     * - 키보드/IME 관련 뷰
     * - 시스템 UI 요소 (toolbar, status bar 등)
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        // 필터링: 입력 필드, 키보드, 시스템 UI 스킵
        if (shouldSkipNode(node)) {
            return ""
        }

        val textBuilder = StringBuilder()

        // 텍스트 추출 (최소 길이 5자 이상만 - 단일 글자/숫자 필터링)
        node.text?.let { text ->
            if (text.length >= 5) {
                textBuilder.append(text).append(" ")
            }
        }

        // contentDescription은 접근성 라벨이 아닌 경우만 포함
        // (버튼 설명, 아이콘 라벨 등은 제외)
        node.contentDescription?.let { desc ->
            if (desc.length >= 10 && !isAccessibilityLabel(desc)) {
                textBuilder.append(desc).append(" ")
            }
        }

        // 자식 노드 재귀 탐색
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

    /**
     * 텍스트 추출에서 제외해야 할 노드인지 확인
     *
     * @return true면 해당 노드와 자식 노드 전체 스킵
     */
    private fun shouldSkipNode(node: AccessibilityNodeInfo): Boolean {
        // 1. 편집 가능 노드 스킵 (입력 필드)
        if (node.isEditable) {
            Log.d(TAG, "Skipping editable node")
            return true
        }

        // 2. 뷰 클래스 필터링
        val className = node.className?.toString() ?: ""
        if (SKIP_VIEW_CLASSES.any { className.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "Skipping by class: $className")
            return true
        }

        // 3. 리소스 ID 필터링
        val resourceId = node.viewIdResourceName ?: ""
        if (resourceId.isNotEmpty() && SKIP_RESOURCE_ID_PATTERNS.any {
            resourceId.contains(it, ignoreCase = true)
        }) {
            Log.d(TAG, "Skipping by resourceId: $resourceId")
            return true
        }

        // 4. 키보드/IME 윈도우 체크
        try {
            val windowPackage = node.packageName?.toString()
            if (windowPackage != null && KEYBOARD_PACKAGE_PREFIXES.any {
                windowPackage.startsWith(it)
            }) {
                Log.d(TAG, "Skipping keyboard package: $windowPackage")
                return true
            }
        } catch (e: Exception) {
            // 패키지 확인 실패 시 무시
        }

        return false
    }

    /**
     * 접근성 라벨인지 확인
     *
     * 버튼, 메뉴, 아이콘 등의 UI 요소 설명은 메시지 콘텐츠가 아님
     */
    private fun isAccessibilityLabel(text: CharSequence): Boolean {
        val lowerText = text.toString().lowercase()
        return ACCESSIBILITY_LABEL_PATTERNS.any { lowerText.contains(it) }
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
        serviceJob.cancel()
        debounceJob?.cancel()
        Log.i(TAG, "Accessibility Service Destroyed")
    }
}
