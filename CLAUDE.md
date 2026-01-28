# DealGuard - 피싱/스캠 탐지 오버레이 앱

플랫폼에 구애받지 않는 실시간 스캠 탐지 안드로이드 앱.
AccessibilityService 기반 텍스트 모니터링 + 온디바이스 AI + 외부 DB 연동.

---

## 🚨 CRITICAL RULES (절대 위반 금지)

1. **AccessibilityService 데이터는 절대 외부 전송 금지** - 모든 분석은 온디바이스에서 수행
2. **사용자 동의 없이 모니터링 시작 금지** - Prominent Disclosure 화면 필수
3. **main 브랜치 직접 커밋 금지** - feature/* 브랜치에서 작업 후 PR
4. **하드코딩된 API 키 금지** - BuildConfig 또는 local.properties 사용
5. **테스트 없이 PR 금지** - 최소 단위 테스트 커버리지 70% 유지

---

## 🎯 PROJECT CONTEXT

### 프로젝트 목표
데이콘 경진대회 출품작 - 피싱/스캠 예방 서비스 MVP 개발
경찰청 후원, 데이터유니버스 주최

### 핵심 기능
1. **플랫폼 무관 채팅 모니터링**: 카카오톡, 텔레그램, 왓츠앱 등 모든 메신저
2. **실시간 스캠 탐지**: Rule-based + On-device SLM 하이브리드
3. **오버레이 경고**: 위험 감지 시 즉시 경고 배너 표시
4. **사기 DB 조회**: 더치트 API, KISA 피싱사이트 DB 연동

### 기술 차별점
- 특정 앱에 종속되지 않음 (AccessibilityService 활용)
- 프라이버시 보호 (온디바이스 AI, 서버 전송 없음)
- 실시간 반응 (<100ms 지연)

---

## 🔧 TECH STACK

```
Language:       Kotlin 1.9+
Min SDK:        26 (Android 8.0)
Target SDK:     34 (Android 14)
Architecture:   MVVM + Clean Architecture
DI:             Hilt
Async:          Kotlin Coroutines + Flow
UI:             Jetpack Compose + XML (Overlay)
ML:             TensorFlow Lite (MobileBERT)
Network:        Retrofit2 + OkHttp
Local DB:       Room
Build:          Gradle Kotlin DSL
```

---

## 📁 PROJECT STRUCTURE

```
app/
├── src/main/
│   ├── java/com/dealguard/
│   │   ├── di/                     # Hilt modules
│   │   │   ├── AppModule.kt
│   │   │   ├── NetworkModule.kt
│   │   │   └── DatabaseModule.kt
│   │   │
│   │   ├── data/                   # Data Layer
│   │   │   ├── local/
│   │   │   │   ├── dao/            # Room DAOs
│   │   │   │   ├── entity/         # Room Entities
│   │   │   │   └── AppDatabase.kt
│   │   │   ├── remote/
│   │   │   │   ├── api/            # Retrofit interfaces
│   │   │   │   ├── dto/            # Data Transfer Objects
│   │   │   │   └── ThecheatApi.kt
│   │   │   └── repository/         # Repository implementations
│   │   │
│   │   ├── domain/                 # Domain Layer
│   │   │   ├── model/              # Domain models
│   │   │   ├── repository/         # Repository interfaces
│   │   │   └── usecase/            # Business logic
│   │   │
│   │   ├── presentation/           # Presentation Layer
│   │   │   ├── ui/
│   │   │   │   ├── main/           # Main Activity + Compose
│   │   │   │   ├── onboarding/     # Permission & consent flow
│   │   │   │   └── settings/       # App settings
│   │   │   └── viewmodel/          # ViewModels
│   │   │
│   │   ├── service/                # Android Services
│   │   │   ├── ScamDetectionAccessibilityService.kt
│   │   │   ├── OverlayService.kt
│   │   │   └── AppMonitoringService.kt
│   │   │
│   │   ├── detector/               # Detection Engine
│   │   │   ├── HybridScamDetector.kt
│   │   │   ├── KeywordMatcher.kt
│   │   │   ├── UrlAnalyzer.kt
│   │   │   └── MlClassifier.kt
│   │   │
│   │   └── util/                   # Utilities
│   │       ├── PermissionHelper.kt
│   │       ├── NotificationHelper.kt
│   │       └── TextPreprocessor.kt
│   │
│   ├── res/
│   │   ├── layout/                 # XML layouts (overlay용)
│   │   ├── xml/
│   │   │   └── accessibility_service_config.xml
│   │   └── values/
│   │
│   └── assets/
│       └── ml/
│           └── scam_detector.tflite
│
├── src/test/                       # Unit tests
├── src/androidTest/                # Instrumented tests
└── build.gradle.kts
```

---

## 🎨 CODE STYLE

### Kotlin Conventions
```kotlin
// ✅ DO: Use data class for models
data class ScamAnalysis(
    val isScam: Boolean,
    val confidence: Float,
    val reasons: List<String>
)

// ✅ DO: Use sealed class for UI states
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

// ✅ DO: Use Flow for reactive streams
fun observeScamAlerts(): Flow<ScamAlert>

// ❌ DON'T: Use LiveData (deprecated pattern)
// ❌ DON'T: Use callbacks for async operations
// ❌ DON'T: Use var when val is possible
```

### Naming Conventions
```
Classes:        PascalCase          (ScamDetector, OverlayService)
Functions:      camelCase           (analyzeText, showWarning)
Constants:      SCREAMING_SNAKE     (MAX_CONFIDENCE, API_TIMEOUT)
Packages:       lowercase           (com.dealguard.detector)
XML IDs:        snake_case          (warning_text, dismiss_button)
Resources:      type_description    (ic_warning, layout_overlay)
```

### File Organization
- 파일당 하나의 public class
- 관련 extension functions는 해당 클래스 파일에 배치
- 500줄 초과 시 분리 검토

---

## 🔐 SECURITY REQUIREMENTS

### AccessibilityService 보안
```kotlin
// ✅ MUST: 모니터링 대상 앱 명시적 제한
private val targetPackages = setOf(
    // 메신저 앱
    "com.kakao.talk",                     // 카카오톡
    "org.telegram.messenger",             // 텔레그램
    "com.whatsapp",                       // 왓츠앱
    "com.facebook.orca",                  // 페이스북 메신저
    "com.instagram.android",              // 인스타그램

    // SMS/MMS 앱
    "com.google.android.apps.messaging",  // Google Messages
    "com.samsung.android.messaging",      // Samsung Messages
    "com.android.mms",                    // 기본 메시지

    // 거래 플랫폼
    "kr.co.daangn",                       // 당근마켓

    // 기타 메신저
    "jp.naver.line.android",              // 라인
    "com.discord"                         // 디스코드
)

// ✅ MUST: 수집 데이터 최소화
// 텍스트만 추출, 이미지/미디어 무시

// ✅ MUST: 데이터 로컬 처리
// 서버 전송 절대 금지
```

### API 키 관리
```kotlin
// ❌ NEVER
const val API_KEY = "abc123"

// ✅ ALWAYS: local.properties
val apiKey = BuildConfig.THECHEAT_API_KEY
```

### Google Play 정책 준수
- [ ] Prominent Disclosure 화면 구현
- [ ] 명시적 사용자 동의 획득 (체크박스 + 버튼)
- [ ] 데이터 수집 목적 상세 설명
- [ ] 접근성 서비스 비활성화 방법 안내
- [ ] Play Console 권한 선언 양식 준비

---

## 📋 COMMON COMMANDS

### Build & Run
```bash
# Debug 빌드
./gradlew assembleDebug

# Release 빌드 (서명 필요)
./gradlew assembleRelease

# 연결된 기기에 설치
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug
```

### Testing
```bash
# Unit tests
./gradlew test

# Unit tests with coverage
./gradlew testDebugUnitTest jacocoTestReport

# Instrumented tests (기기 필요)
./gradlew connectedAndroidTest

# 특정 테스트 클래스 실행
./gradlew test --tests "com.dealguard.detector.HybridScamDetectorTest"
```

### Code Quality
```bash
# Lint check
./gradlew lint

# ktlint format
./gradlew ktlintFormat

# Detekt static analysis
./gradlew detekt
```

### Dependency Management
```bash
# 의존성 트리 확인
./gradlew app:dependencies

# 업데이트 가능한 의존성 확인
./gradlew dependencyUpdates
```

---

## 🧪 TESTING STRATEGY

### Unit Tests (필수)
```kotlin
// detector/ 패키지 테스트 커버리지 90% 이상 유지
class HybridScamDetectorTest {
    @Test
    fun `급전 키워드 포함 시 스캠 탐지`() {
        val result = detector.analyze("급전 필요하시면 연락주세요")
        assertTrue(result.isScam)
        assertTrue(result.confidence > 0.5f)
    }
    
    @Test
    fun `일반 대화는 스캠 아님`() {
        val result = detector.analyze("내일 점심 같이 먹을래?")
        assertFalse(result.isScam)
    }
}
```

### Test Naming Convention
```
`[상황]_[조건]_[예상결과]`
`급전키워드포함_단일키워드_스캠탐지됨`
```

### Mocking
- MockK 사용 (Mockito 대신)
- Repository → UseCase 테스트 시 mock
- API 호출은 항상 mock

---

## 🔄 GIT WORKFLOW

### Branch Naming
```
feature/[기능명]     새 기능 개발
bugfix/[버그명]      버그 수정
refactor/[대상]      리팩토링
test/[테스트대상]    테스트 추가
docs/[문서명]        문서 업데이트
```

### Commit Convention (Conventional Commits)
```
feat: 새 기능 추가
fix: 버그 수정
refactor: 코드 리팩토링 (기능 변경 없음)
test: 테스트 추가/수정
docs: 문서 수정
chore: 빌드/설정 변경
style: 코드 포맷팅 (세미콜론 등)

예시:
feat(detector): 온디바이스 ML 분류기 추가
fix(overlay): Android 14 오버레이 권한 이슈 수정
test(detector): HybridScamDetector 엣지케이스 테스트 추가
```

### PR Template
```markdown
## 변경 사항
- 

## 테스트
- [ ] Unit test 추가/수정
- [ ] Instrumented test 추가/수정
- [ ] 수동 테스트 완료

## 체크리스트
- [ ] lint 통과
- [ ] 테스트 통과
- [ ] 코드 리뷰 요청
```

---

## 🏗️ ARCHITECTURE PATTERNS

### MVVM + Clean Architecture
```
[View] ←→ [ViewModel] ←→ [UseCase] ←→ [Repository] ←→ [DataSource]
  ↓           ↓              ↓              ↓              ↓
Compose   StateFlow      비즈니스로직    인터페이스    Room/Retrofit
```

### Dependency Rule
- 안쪽 레이어는 바깥쪽 레이어를 모름
- domain → data 의존 금지 (interface로 역전)
- presentation → domain 만 의존

### State Management
```kotlin
// ViewModel
private val _uiState = MutableStateFlow<UiState<List<ScamAlert>>>(UiState.Loading)
val uiState: StateFlow<UiState<List<ScamAlert>>> = _uiState.asStateFlow()

// Compose
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

---

## 🛠️ IMPLEMENTATION NOTES

### AccessibilityService 구현 시 주의사항
```kotlin
// ✅ 이벤트 타입 필터링 필수
override fun onAccessibilityEvent(event: AccessibilityEvent) {
    // 패키지 체크 먼저
    if (event.packageName?.toString() !in targetPackages) return
    
    // 이벤트 타입 체크
    when (event.eventType) {
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
            // 처리
        }
        else -> return  // 다른 이벤트 무시
    }
}

// ✅ Node recycle 필수
val node = rootInActiveWindow ?: return
try {
    // 사용
} finally {
    node.recycle()
}
```

### Overlay 구현 시 주의사항
```kotlin
// Android 8.0+ TYPE_APPLICATION_OVERLAY 필수
val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
} else {
    @Suppress("DEPRECATION")
    WindowManager.LayoutParams.TYPE_PHONE
}

// FLAG 조합 주의
val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
```

### TensorFlow Lite 모델 로딩
```kotlin
// assets에서 로드
val model = FileUtil.loadMappedFile(context, "ml/scam_detector.tflite")

// NNAPI 하드웨어 가속 활용
val options = Interpreter.Options().apply {
    addDelegate(NnApiDelegate())
    setNumThreads(4)
}
```

---

## 📦 EXTERNAL APIs

### 더치트 API
```kotlin
// 테스트: https://apicenter.thecheat.co.kr
// 문서: https://apidocs.thecheat.co.kr

interface ThecheatApi {
    @POST("search")
    suspend fun checkScam(
        @Body request: ScamCheckRequest
    ): ScamCheckResponse
}

data class ScamCheckRequest(
    @SerializedName("keyword_type") val keywordType: String,  // "account" or "phone"
    @SerializedName("keyword") val keyword: String,
    @SerializedName("add_info") val addInfo: String? = null   // 금융기관 코드
)
```

### KISA 피싱사이트 DB
```
공공데이터포털: https://www.data.go.kr/data/15143094/fileData.do
형식: CSV/JSON
필드: DATE, URL
라이선스: 무료
```

---

## 🐛 KNOWN ISSUES & WORKAROUNDS

### Android 14+ 제한사항
- FGS 백그라운드 시작 제한 → `foregroundServiceType="specialUse"` 사용
- 접근성 서비스 제한 강화 → 명확한 목적 설명 필수

### 특정 앱 호환성
- 카카오톡: 일부 뷰에서 contentDescription 없음 → text 속성 우선
- 텔레그램: 암호화 채팅 메시지 접근 불가 → 일반 채팅만 지원 명시

### 메모리 관리
- ML 모델 메모리 사용량 ~300MB
- 장시간 실행 시 메모리 누수 주의 → WeakReference 활용

---

## 🚀 DEPLOYMENT CHECKLIST

### Release 전 확인
- [ ] ProGuard/R8 규칙 적용 (TFLite 모델 제외)
- [ ] 디버그 로그 제거
- [ ] API 키 프로덕션 환경으로 교체
- [ ] 버전 코드/이름 업데이트
- [ ] 변경 로그 작성

### Play Store 제출
- [ ] 접근성 서비스 권한 선언서 작성
- [ ] 기능 시연 비디오 준비 (30초~2분)
- [ ] 개인정보처리방침 URL 준비
- [ ] 스크린샷 준비 (휴대폰, 7인치 태블릿)

---

## 📚 REFERENCES

### 공식 문서
- [Android Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility/service)
- [TensorFlow Lite Android](https://www.tensorflow.org/lite/android)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)

### 유사 앱 분석
- 후후: 음성 분석 AI + 범죄자 음성 DB
- 시티즌코난: 악성앱 시그니처 매칭
- 후스콜: 크라우드소싱 + 하이브리드 AI

---

## 💡 TIPS FOR CLAUDE

### 이 프로젝트에서 Claude에게 요청할 때
1. **Plan Mode 먼저**: 복잡한 기능은 항상 계획 수립 후 구현
2. **테스트 우선**: TDD 방식으로 테스트 먼저 작성 요청
3. **점진적 구현**: 한 번에 하나의 기능만 구현
4. **검증 포함**: 구현 후 빌드/테스트 실행까지 요청

### 자주 사용하는 프롬프트 패턴
```
"[기능]을 구현해줘. 먼저 계획을 세우고, 테스트를 작성한 후, 구현해줘."

"HybridScamDetector에 [새기능]을 추가해줘. 
기존 패턴을 따르고, 단위 테스트도 추가해줘."

"[파일]을 리팩토링해줘. Clean Architecture 원칙을 따르고,
변경 전후 동작이 동일한지 테스트로 검증해줘."
```

---

*Last Updated: 2025-01-28*
*Author: Zaeewang*
*Project: 데이콘 피싱/스캠 예방 서비스 경진대회*
