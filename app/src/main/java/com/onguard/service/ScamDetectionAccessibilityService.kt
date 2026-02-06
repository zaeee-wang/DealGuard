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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import javax.inject.Inject

import android.util.Log
import java.util.ArrayList

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

    /** 탐지 설정 저장소 (DataStore) */
    @Inject
    lateinit var detectionSettingsStore: DetectionSettingsDataStore

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

    // 스크롤 중복 알림 방지용 세션 캐시
    // Key: "앱패키지:정렬된키워드목록", Value: 마지막 알림 시각(ms)
    private val recentAlertCache = mutableMapOf<String, Long>()

    // 스크롤 모드 감지용 변수
    // - 스크롤 이벤트 발생 시 타임스탬프 기록
    // - 일정 시간 내에는 "스크롤 모드"로 간주하여 캐시된 알림 스킵
    @Volatile
    private var lastScrollTimestamp: Long = 0L

    companion object {
        private const val TAG = "OnGuardService"

        // 필터링할 뷰 클래스 목록 (주로 키보드나 입력 관련 UI)
        private val SKIP_VIEW_CLASSES = setOf(
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "android.inputmethodservice.InputMethodService",
            "SoftKeyboard",
            "Keyboard",
            "Ime"
        )

        // 필터링할 리소스 ID 패턴 (입력창, 검색바, 패스워드 등)
        private val SKIP_RESOURCE_ID_PATTERNS = setOf(
            "input", "edit", "search", "password", "id_input", "query"
        )

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

        // ========== 스크롤 중복 알림 방지 설정 ==========

        // 스크롤 모드 타임아웃 (3초)
        // - 스크롤 이벤트 후 3초간 "스크롤 모드" 유지
        // - 스크롤 모드에서는 캐시에 있는 키워드 조합만 스킵
        // - 3초 후 스크롤 없으면 "새 메시지 모드"로 전환
        private const val SCROLL_MODE_TIMEOUT_MS = 3_000L

        // 세션 기반 캐시 유지 시간 (1시간)
        // - 서비스 실행 중 탐지된 키워드 조합을 1시간 동안 캐시
        // - 스크롤 모드에서 캐시된 키워드 발견 시 스킵 (과거 메시지)
        // - 1시간 이후 같은 내용은 새 메시지로 간주
        private const val CACHE_EXPIRY_MS = 3_600_000L  // 1시간

        // 캐시 최대 크기 (메모리 관리)
        // - 100개 초과 시 가장 오래된 항목 제거
        // - 메모리 사용량: ~100 * (문자열 키 + Long) ≈ 20KB 미만
        private const val CACHE_MAX_SIZE = 100

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
        DebugLog.infoLog(TAG) { "step=service_connected" }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED  // 스크롤 감지용
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        serviceInfo = info

        // 탐지 설정 변경 관찰 → 캐시 업데이트
        serviceScope.launch {
            detectionSettingsStore.settingsFlow.collect { settings ->
                cachedSettings = settings
                DebugLog.infoLog(TAG) {
                    "step=settings_updated enabled=${settings.isDetectionEnabled} " +
                    "pauseUntil=${settings.pauseUntilTimestamp} " +
                    "disabledApps=${settings.disabledApps.size}"
                }
            }
        }
    }

    // 탐지 설정 캐시 (빠른 동기 접근용)
    // - Flow에서 최신 값을 가져와 캐시
    // - onAccessibilityEvent에서 동기적으로 빠르게 체크
    @Volatile
    private var cachedSettings: DetectionSettings = DetectionSettings()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 패키지 체크
        val packageName = event.packageName?.toString()
        if (packageName !in targetPackages) return

        // 탐지 설정 체크 (캐시된 값 사용 - 동기적 빠른 체크)
        // - 전역 탐지 비활성화 또는 일시 중지 상태이면 스킵
        // - 앱별 비활성화 설정 체크
        if (!cachedSettings.isActiveForApp(packageName ?: "")) {
            DebugLog.debugLog(TAG) {
                "step=on_event skip reason=detection_disabled app=$packageName"
            }
            return
        }

        // 이벤트 타입 체크
        when (event.eventType) {
            // 스크롤 이벤트 감지 → 스크롤 모드 진입
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollTimestamp = System.currentTimeMillis()
                Log.d(TAG, "Scroll detected - entering scroll mode")
            }

            // 컨텐츠 변경 이벤트 → 텍스트 분석
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

    private fun processEvent(event: AccessibilityEvent) {
        // Debouncing: 이전 작업 취소
        debounceJob?.cancel()

        // event 객체는 나중에 재활용될 수 있으므로, 필요한 값은 먼저 복사해 둔다.
        val sourceApp = event.packageName?.toString() ?: return

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
                // 채팅방 내부인지 확인 (목록 화면이면 스킵)
                if (!isInsideChatRoom(node)) {
                    Log.d(TAG, "Not inside chat room (no input field) - skipping")
                    return@launch
                }

                val extractedText = extractTextFromNode(node)

                // 최소 길이 체크
                if (extractedText.length < MIN_TEXT_LENGTH) {
                    DebugLog.debugLog(TAG) {
                        "step=process_event skip reason=too_short length=${extractedText.length}"
                    }
                    return@launch
                }

                // 중복 텍스트 체크 (캐시)
                val lastText = lastProcessedText?.get()
                if (extractedText == lastText) {
                    DebugLog.debugLog(TAG) { "step=process_event skip reason=duplicate_text" }
                    return@launch
                }

                // 새로운 텍스트 저장 (WeakReference)
                lastProcessedText = WeakReference(extractedText)

                DebugLog.debugLog(TAG) {
                    val masked = DebugLog.maskText(extractedText, maxLen = 50)
                    "step=text_extracted length=${extractedText.length} masked=\"$masked\""
                }

                // 스캠 분석 (event 대신 사전에 복사한 sourceApp 사용)
                analyzeForScam(extractedText, sourceApp)

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error processing event", e)
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
            DebugLog.debugLog(TAG) { "step=skip_node reason=editable" }
            return true
        }

        // 2. 키보드/IME 관련 뷰 클래스 (전체 서브트리 스킵)
        val className = node.className?.toString() ?: ""
        if (SKIP_VIEW_CLASSES.any { className.contains(it, ignoreCase = true) }) {
            DebugLog.debugLog(TAG) { "step=skip_node reason=class_match class=$className" }
            return true
        }

        // 3. 리소스 ID 필터링
        val resourceId = node.viewIdResourceName ?: ""
        if (resourceId.isNotEmpty() && SKIP_RESOURCE_ID_PATTERNS.any {
            resourceId.contains(it, ignoreCase = true)
        }) {
            DebugLog.debugLog(TAG) { "step=skip_node reason=resource_id resourceId=$resourceId" }
            return true
        }

        // 4. 키보드/IME 윈도우 체크
        try {
            val windowPackage = node.packageName?.toString()
            if (windowPackage != null && KEYBOARD_PACKAGE_PREFIXES.any {
                windowPackage.startsWith(it)
            }) {
                DebugLog.debugLog(TAG) { "step=skip_node reason=keyboard_package package=$windowPackage" }
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

    // ========== 채팅방 내부 감지 (목록 화면 제외) ==========

    /**
     * 현재 화면이 채팅방 내부인지 확인
     *
     * 채팅방 내부에는 메시지 입력 필드(EditText)가 존재하지만,
     * 채팅 목록 화면에는 입력 필드가 없음. 이를 활용하여 구분.
     *
     * @param rootNode 루트 노드
     * @return true if inside a chat room (has input field)
     */
    private fun isInsideChatRoom(rootNode: AccessibilityNodeInfo): Boolean {
        return hasMessageInputField(rootNode)
    }

    /**
     * 메시지 입력 필드 존재 여부 확인 (재귀 탐색)
     *
     * EditText, 편집 가능 노드, 또는 입력 관련 리소스 ID가 있으면 true
     *
     * 당근마켓 등 커스텀 UI 사용 앱을 위한 추가 패턴 포함
     */
    private fun hasMessageInputField(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        // 최대 탐색 깊이 제한 (성능)
        if (depth > 15) return false

        // 1. 편집 가능한 노드 발견 → 채팅방 내부
        if (node.isEditable) {
            Log.d(TAG, "Found editable node - inside chat room")
            return true
        }

        // 2. 입력 관련 클래스 확인 (Compose 포함)
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

    // ========== 스크롤 중복 알림 방지 함수 ==========

    /**
     * 현재 스크롤 모드인지 확인
     *
     * 스크롤 이벤트 발생 후 SCROLL_MODE_TIMEOUT_MS(3초) 이내이면 스크롤 모드
     * - 스크롤 모드: 캐시에 있는 키워드 조합은 스킵 (과거 메시지)
     * - 일반 모드: 새 메시지로 간주하여 정상 탐지
     *
     * @return true if in scroll mode
     */
    private fun isInScrollMode(): Boolean {
        val elapsed = System.currentTimeMillis() - lastScrollTimestamp
        return elapsed < SCROLL_MODE_TIMEOUT_MS
    }

    /**
     * 캐시 키 생성: 앱 + 탐지된 키워드 조합
     *
     * 전체 텍스트 대신 키워드만 사용하는 이유:
     * - 스크롤 시 화면에 보이는 텍스트가 달라져도 같은 스캠 메시지면 같은 키워드 탐지
     * - "급전 필요" 메시지가 다른 컨텍스트에서 보여도 동일 키로 인식
     *
     * @param detectedKeywords 탐지된 키워드 목록
     * @param sourceApp 출처 앱 패키지명
     * @return "앱패키지:키워드1,키워드2,..." 형식의 캐시 키
     */
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

        // 만료된 캐시는 삭제 후 false 반환
        if (elapsed > CACHE_EXPIRY_MS) {
            recentAlertCache.remove(cacheKey)
            return false
        }

        return true
    }

    /**
     * 캐시에 알림 등록 (오래된 항목 정리 포함)
     *
     * @param cacheKey 캐시 키
     */
    private fun registerAlert(cacheKey: String) {
        val now = System.currentTimeMillis()

        // 오래된 캐시 정리 (CACHE_EXPIRY_MS 이상 지난 항목)
        recentAlertCache.entries.removeIf { (_, time) ->
            now - time > CACHE_EXPIRY_MS
        }

        // 캐시 크기 제한 (가장 오래된 항목 제거)
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
                DebugLog.warnLog(TAG) {
                    "step=show_warning type=${analysis.scamType} confidence=${analysis.confidence} sourceApp=$sourceApp"
                }

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
        DebugLog.infoLog(TAG) { "step=service_destroyed" }
    }
}
