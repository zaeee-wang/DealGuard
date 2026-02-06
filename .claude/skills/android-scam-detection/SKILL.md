# Android Scam Detection Skill

**Skill ID**: `android-scam-detection`
**Version**: 1.1.0
**Category**: Android Development, Security

---

## 📋 Overview

이 스킬은 OnGuard 프로젝트에 특화된 피싱/스캠 탐지 안드로이드 앱 개발을 위한 전문 지식을 제공합니다.

### 핵심 역량

1. **AccessibilityService 개발**
   - 텍스트 추출 및 모니터링
   - 이벤트 필터링 및 성능 최적화
   - Google Play 정책 준수

2. **스캠 탐지 엔진**
   - Rule-based 키워드 매칭
   - 정규식 패턴 분석
   - ML 분류기 통합 (TensorFlow Lite)

3. **오버레이 시스템**
   - WindowManager 기반 경고 UI
   - 권한 관리 (SYSTEM_ALERT_WINDOW)
   - Android 버전별 호환성

4. **프라이버시 & 보안**
   - 온디바이스 데이터 처리
   - API 키 안전한 관리
   - 데이터 암호화

---

## 🎯 주요 기능

### 1. AccessibilityService 구현

#### 이벤트 처리 패턴
```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent) {
    // 1단계: 패키지 필터링
    val packageName = event.packageName?.toString()
    if (packageName !in targetPackages) return

    // 2단계: 이벤트 타입 필터링
    when (event.eventType) {
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
            processEvent(event)
        }
        else -> return
    }
}

private fun processEvent(event: AccessibilityEvent) {
    val node = rootInActiveWindow ?: return
    try {
        val text = extractTextFromNode(node)
        // Debouncing 적용
        debouncer.submit {
            analyzeText(text)
        }
    } finally {
        node.recycle()  // 메모리 누수 방지 필수!
    }
}
```

#### 메모리 관리
- AccessibilityNodeInfo는 반드시 `recycle()` 호출
- WeakReference 사용으로 메모리 누수 방지
- LruCache로 최근 분석 결과 캐싱

---

### 2. 스캠 탐지 로직

#### 키워드 매칭
```kotlin
class KeywordMatcher {
    private val criticalKeywords = setOf(
        "급전", "계좌이체", "인증번호", "경찰청"
    )

    private val patterns = listOf(
        Regex("\\d{3,4}-\\d{3,4}-\\d{4}"),  // 계좌번호
        Regex("\\d{3}-\\d{4}-\\d{4}")       // 전화번호
    )

    fun analyze(text: String): ScamAnalysis {
        var confidence = 0f
        val reasons = mutableListOf<String>()

        // 키워드 매칭
        val foundKeywords = criticalKeywords.filter {
            text.contains(it, ignoreCase = true)
        }
        confidence += foundKeywords.size * 0.15f

        // 패턴 매칭
        if (patterns.any { it.containsMatchIn(text) }) {
            confidence += 0.3f
            reasons.add("민감한 정보 패턴 발견")
        }

        return ScamAnalysis(
            isScam = confidence > 0.5f,
            confidence = confidence.coerceIn(0f, 1f),
            reasons = reasons
        )
    }
}
```

#### 하이브리드 탐지
```kotlin
class HybridScamDetector(
    private val keywordMatcher: KeywordMatcher,
    private val mlClassifier: MlClassifier?,
    private val externalDbChecker: ExternalDbChecker
) {
    suspend fun analyze(text: String): ScamAnalysis {
        // 1. Rule-based (빠름)
        val ruleResult = keywordMatcher.analyze(text)
        if (ruleResult.confidence > 0.7f) {
            return ruleResult
        }

        // 2. 외부 DB 조회 (계좌/전화번호 추출 시)
        val numbers = extractNumbers(text)
        if (numbers.isNotEmpty()) {
            val dbResult = externalDbChecker.check(numbers)
            if (dbResult.isScam) return dbResult
        }

        // 3. ML 분류 (애매한 경우)
        if (ruleResult.confidence in 0.3f..0.7f) {
            val mlResult = mlClassifier?.classify(text)
            if (mlResult != null) {
                return combineResults(ruleResult, mlResult)
            }
        }

        return ruleResult
    }
}
```

---

### 3. 오버레이 경고 시스템

#### WindowManager 설정
```kotlin
private fun createOverlayParams(): WindowManager.LayoutParams {
    val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    return WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        y = 100
    }
}

fun showWarning(analysis: ScamAnalysis) {
    val warningView = createWarningView(analysis)
    windowManager.addView(warningView, createOverlayParams())

    // 10초 후 자동 제거
    handler.postDelayed({
        windowManager.removeView(warningView)
    }, 10000)
}
```

---

### 4. TensorFlow Lite 통합

#### 모델 로딩
```kotlin
class MlClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null

    init {
        try {
            val model = FileUtil.loadMappedFile(
                context,
                "ml/scam_detector.tflite"
            )

            val options = Interpreter.Options().apply {
                // NNAPI 하드웨어 가속
                addDelegate(NnApiDelegate())
                setNumThreads(4)
            }

            interpreter = Interpreter(model, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ML model", e)
        }
    }

    fun classify(text: String): ScamAnalysis? {
        val interpreter = this.interpreter ?: return null

        // 토크나이징 및 전처리
        val inputArray = preprocessText(text)
        val outputArray = Array(1) { FloatArray(2) }

        // 추론 실행
        interpreter.run(inputArray, outputArray)

        val confidence = outputArray[0][1]  // 스캠일 확률

        return ScamAnalysis(
            isScam = confidence > 0.5f,
            confidence = confidence,
            reasons = listOf("ML 분류기 분석 결과"),
            detectionMethod = DetectionMethod.ML_CLASSIFIER
        )
    }
}
```

---

## 🔒 보안 체크리스트

### Google Play 정책 준수

#### Prominent Disclosure
- [ ] 앱 첫 실행 시 데이터 수집 범위 명시
- [ ] 명시적 사용자 동의 획득 (체크박스 필수)
- [ ] 접근성 서비스 비활성화 방법 안내
- [ ] 개인정보처리방침 링크 제공

#### 데이터 보안
```kotlin
// ❌ 절대 금지
fun sendToServer(text: String) {
    api.uploadText(text)  // AccessibilityService 데이터 서버 전송 금지!
}

// ✅ 올바른 방법
fun analyzeLocally(text: String): ScamAnalysis {
    return detector.analyze(text)  // 온디바이스 분석만
}
```

#### API 키 관리
```kotlin
// ❌ 하드코딩 금지
const val API_KEY = "abc123"

// ✅ BuildConfig 사용
val apiKey = BuildConfig.THECHEAT_API_KEY

// ✅ local.properties
THECHEAT_API_KEY=your_api_key_here
```

---

## 🧪 테스트 전략

### 단위 테스트 (필수)
```kotlin
class KeywordMatcherTest {
    private lateinit var matcher: KeywordMatcher

    @Before
    fun setup() {
        matcher = KeywordMatcher()
    }

    @Test
    fun `급전 키워드 포함 시 스캠 탐지`() {
        val result = matcher.analyze("급전 필요하시면 연락주세요")
        assertTrue(result.isScam)
        assertTrue(result.confidence > 0.5f)
    }

    @Test
    fun `일반 대화는 스캠 아님`() {
        val result = matcher.analyze("내일 점심 같이 먹을래?")
        assertFalse(result.isScam)
    }

    @Test
    fun `계좌번호 패턴 포함 시 고위험 판정`() {
        val result = matcher.analyze("여기로 이체해주세요 1234-5678-9012")
        assertTrue(result.isScam)
        assertTrue(result.confidence > 0.7f)
    }
}
```

### 통합 테스트
```kotlin
@RunWith(AndroidJUnit4::class)
class ScamDetectionIntegrationTest {
    @Test
    fun endToEndScamDetection() {
        // 1. 텍스트 추출 시뮬레이션
        val text = "긴급! 계좌이체 필요 1234-5678-9012"

        // 2. 탐지 엔진 실행
        val analysis = detector.analyze(text)

        // 3. 검증
        assertTrue(analysis.isScam)
        assertTrue(analysis.detectedKeywords.isNotEmpty())
    }
}
```

---

## 📊 성능 최적화

### 메모리 최적화
- ML 모델 크기: < 50MB
- 메모리 사용량: < 300MB
- 캐시 크기: 최대 100개 항목

### 응답 속도
- 텍스트 추출: < 50ms
- Rule-based 탐지: < 30ms
- ML 추론: < 100ms
- 오버레이 표시: < 50ms

### 배터리 효율
- Debouncing: 100ms
- 백그라운드 CPU 사용률: < 5%
- Doze 모드 대응

---

## 🛠️ 일반적인 이슈 해결

### 접근성 서비스가 동작하지 않음
```kotlin
// 해결: targetPackages 설정 확인
<accessibility-service
    android:packageNames="com.kakao.talk,org.telegram.messenger"
    ...
/>
```

### 오버레이 권한 거부
```kotlin
// 해결: 권한 체크 후 설정 화면으로 이동
if (!Settings.canDrawOverlays(context)) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    startActivity(intent)
}
```

### 특정 앱에서 텍스트 추출 실패
```kotlin
// 해결: contentDescription 우선, text 속성 폴백
fun extractText(node: AccessibilityNodeInfo): String {
    return node.contentDescription?.toString()
        ?: node.text?.toString()
        ?: ""
}
```

---

## 📚 참고 자료

- [Android Accessibility Guide](https://developer.android.com/guide/topics/ui/accessibility/service)
- [TensorFlow Lite Android](https://www.tensorflow.org/lite/android)
- [Google Play Accessibility Policies](https://support.google.com/googleplay/android-developer/answer/10964491)

---

*Last Updated: 2026-02-05*
*Skill Maintainer: OnGuard Team*
*Project: OnGuard - 피싱/스캠 탐지 앱*
