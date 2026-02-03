package com.onguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.onguard.detector.HybridScamDetector
import com.onguard.domain.model.ScamAnalysis
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * 접근성 서비스를 이용한 실시간 스캠 탐지 서비스.
 *
 * [targetPackages]에 포함된 앱(카카오톡, 당근마켓 등)의 화면 텍스트를 수집하고,
 * [HybridScamDetector]로 분석한 뒤, 임계값 이상이면 [OverlayService]를 통해 경고를 표시한다.
 *
 * - 디바운스(100ms)로 타이핑 중 과도한 분석 방지
 * - 최소 텍스트 길이(20자)로 짧은 인사·UI 요소 필터링
 * - 스캠 임계값 0.5로 오탐·미탐 균형
 *
 * @see HybridScamDetector 스캠 분석
 * @see OverlayService 경고 표시
 */
@AndroidEntryPoint
class ScamDetectionAccessibilityService : AccessibilityService() {

    /** 하이브리드 스캠 탐지기 (Rule-based + LLM) */
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

        // 최소 텍스트 길이: 10자
        // - 노드 필터링(shouldSkipEntireSubtree)이 키보드/UI 요소를 이미 제외
        // - 짧은 스캠 메시지도 탐지 필요: "입금해주세요"(6자), "OTP알려줘"(7자)
        // - 10자 미만은 의미 있는 분석 어려움 (단어 1-2개)
        // - 주의: 개별 노드 텍스트는 5자 이상만 추출 (extractTextFromNode)
        private const val MIN_TEXT_LENGTH = 10

        // 스캠 임계값: 0.5 (50%)
        // - 오탐(false positive) 최소화와 미탐(false negative) 균형점
        // - 0.3 이하: 오탐 증가 (일반 메시지도 스캠 판정)
        // - 0.7 이상: 미탐 증가 (실제 스캠 놓침)
        // - KeywordMatcher와 연동: CRITICAL 2개 조합(0.8) 또는 CRITICAL+HIGH(0.65)
        private const val SCAM_THRESHOLD = 0.5f

        // ========== 노드 필터링 설정 (False Positive 방지) ==========

        // 키보드/IME 패키지 프리픽스 (전체 서브트리 스킵)
        // - 키보드 앱에서 오는 텍스트(숫자 버튼 등)는 분석하지 않음
        private val KEYBOARD_PACKAGE_PREFIXES = setOf(
            "com.google.android.inputmethod",    // Gboard
            "com.samsung.android.honeyboard",    // Samsung Keyboard
            "com.swiftkey",                       // SwiftKey
            "com.touchtype.swiftkey",
            "com.sec.android.inputmethod",       // Samsung default
            "com.lge.ime",                        // LG Keyboard
            "com.huawei.inputmethod"             // Huawei Keyboard
        )

        // 접근성 라벨로 간주할 패턴 (버튼, 메뉴 등의 설명)
        // - contentDescription에 이 패턴이 포함되면 UI 라벨로 간주하여 제외
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

            // rootInActiveWindow null 재시도 로직
            // 윈도우가 완전히 로드되지 않았을 때 null 반환 가능
            var node: AccessibilityNodeInfo? = null
            var retryCount = 0
            val maxRetries = 3

            while (node == null && retryCount < maxRetries) {
                node = rootInActiveWindow
                if (node == null) {
                    retryCount++
                    delay(50)  // 50ms 대기 후 재시도
                }
            }

            if (node == null) {
                Log.w(TAG, "rootInActiveWindow is null after $maxRetries retries")
                return@launch
            }

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
     * - 편집 가능 필드 (EditText 등) → 전체 서브트리 스킵
     * - 키보드/IME 관련 뷰 → 전체 서브트리 스킵
     * - 시스템 UI 요소 (toolbar 등) → 현재 노드 텍스트만 스킵, 자식은 탐색
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        // 1. 전체 서브트리 스킵 대상 (EditText, 키보드 등)
        if (shouldSkipEntireSubtree(node)) {
            return ""
        }

        val textBuilder = StringBuilder()

        // 2. 현재 노드 텍스트만 스킵 대상이 아니면 텍스트 추출
        if (!shouldSkipNodeTextOnly(node)) {
            // 텍스트 추출 (최소 길이 3자 이상)
            // - "OTP"(3자), "급전"(2자+조사) 같은 짧은 키워드도 추출
            // - 단일 문자/숫자 ("1", "가")는 제외
            node.text?.let { text ->
                if (text.length >= 3) {
                    textBuilder.append(text).append(" ")
                }
            }

            // contentDescription은 접근성 라벨이 아닌 경우만 포함
            // - 5자 이상 + 라벨 패턴 제외
            node.contentDescription?.let { desc ->
                if (desc.length >= 5 && !isAccessibilityLabel(desc)) {
                    textBuilder.append(desc).append(" ")
                }
            }
        }

        // 3. 자식 노드 재귀 탐색 (항상 수행 - 컨테이너 안의 메시지 추출을 위해)
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
     * 전체 서브트리를 스킵해야 하는지 확인
     *
     * 다음 경우 노드와 모든 자식 노드를 스킵:
     * - 편집 가능 필드 (EditText) - 사용자 입력 영역
     * - 키보드/IME 뷰 - 키보드 UI 숫자/문자
     * - 키보드 앱 패키지
     *
     * @return true면 해당 노드와 자식 노드 전체 스킵
     */
    private fun shouldSkipEntireSubtree(node: AccessibilityNodeInfo): Boolean {
        // 1. 편집 가능 노드 스킵 (입력 필드 - 사용자가 입력 중인 텍스트)
        if (node.isEditable) {
            Log.d(TAG, "Skipping editable node subtree")
            return true
        }

        // 2. 키보드/IME 관련 뷰 클래스 (전체 서브트리 스킵)
        val className = node.className?.toString() ?: ""
        val keyboardClasses = setOf(
            "android.inputmethodservice.Keyboard",
            "android.inputmethodservice.KeyboardView"
        )
        if (keyboardClasses.any { className.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "Skipping keyboard class subtree: $className")
            return true
        }

        // 3. 키보드/IME 앱 패키지 (전체 서브트리 스킵)
        try {
            val windowPackage = node.packageName?.toString()
            if (windowPackage != null && KEYBOARD_PACKAGE_PREFIXES.any {
                windowPackage.startsWith(it)
            }) {
                Log.d(TAG, "Skipping keyboard package subtree: $windowPackage")
                return true
            }
        } catch (e: Exception) {
            // 패키지 확인 실패 시 무시
        }

        return false
    }

    /**
     * 현재 노드의 텍스트만 스킵하고 자식은 탐색해야 하는지 확인
     *
     * 다음 경우 현재 노드 텍스트만 스킵 (자식은 계속 탐색):
     * - EditText 등 입력 관련 뷰 클래스
     * - toolbar, status_bar 등 시스템 UI 컨테이너
     *
     * @return true면 현재 노드 텍스트만 스킵, 자식은 계속 탐색
     */
    private fun shouldSkipNodeTextOnly(node: AccessibilityNodeInfo): Boolean {
        // 1. 입력 관련 뷰 클래스 (텍스트만 스킵, 자식은 탐색)
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

        // 2. 시스템 UI 컨테이너 (텍스트만 스킵 - 자식에 메시지가 있을 수 있음)
        val resourceId = node.viewIdResourceName ?: ""
        // 더 엄격한 패턴만 적용 (title, header 등은 너무 광범위하여 제외)
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

                // isScam이 이미 threshold 체크를 포함하므로 중복 조건 제거
                if (analysis.isScam) {
                    showScamWarning(analysis, sourceApp)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing text for scam", e)
            }
        }
    }

    /**
     * 스캠 경고 오버레이 표시
     *
     * ScamAnalysis의 모든 정보를 OverlayService로 전달합니다:
     * - confidence: 신뢰도
     * - reasons: 위험 요소 목록
     * - warningMessage: LLM 생성 경고 메시지
     * - scamType: 스캠 유형
     * - suspiciousParts: 의심 문구
     */
    private fun showScamWarning(analysis: ScamAnalysis, sourceApp: String) {
        handler.post {
            try {
                Log.w(TAG, "SCAM DETECTED! Type: ${analysis.scamType}, Confidence: ${analysis.confidence}")
                Log.d(TAG, "Warning message: ${analysis.warningMessage}")
                Log.d(TAG, "Reasons: ${analysis.reasons}")

                // OverlayService 시작 (새 필드 포함)
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_CONFIDENCE, analysis.confidence)
                    putExtra(OverlayService.EXTRA_REASONS, analysis.reasons.joinToString(", "))
                    putExtra(OverlayService.EXTRA_SOURCE_APP, sourceApp)

                    // LLM 생성 데이터
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
