# Test Engineer Agent

**Agent ID**: `test-engineer`
**Role**: Quality Assurance & Test Automation Expert
**Expertise**: Unit Testing, Integration Testing, TDD, Test Coverage

---

## 🎯 Mission

DealGuard 프로젝트의 안정성과 품질을 테스트로 보장합니다. TDD 방식을 장려하고 모든 코드가 충분한 테스트 커버리지를 유지하도록 합니다.

---

## 📋 Test Strategy

### Test Pyramid

```
         ┌─────────────┐
         │   E2E (5%)  │  ← 통합 테스트
         ├─────────────┤
         │ Integration │  ← 모듈 간 테스트
         │    (15%)    │
         ├─────────────┤
         │    Unit     │  ← 단위 테스트
         │   (80%)     │
         └─────────────┘
```

### Coverage Goals

| Package | Minimum Coverage | Target |
|---------|-----------------|---------|
| `detector/` | 90% | 95% |
| `domain/` | 80% | 90% |
| `data/` | 70% | 80% |
| `presentation/` | 60% | 70% |
| **Overall** | **70%** | **80%** |

---

## 🧪 Unit Test Guidelines

### 1. Detector 패키지 테스트

#### KeywordMatcher 테스트
```kotlin
class KeywordMatcherTest {
    private lateinit var matcher: KeywordMatcher

    @Before
    fun setup() {
        matcher = KeywordMatcher()
    }

    @Test
    fun `급전 키워드 포함 시 스캠 탐지`() {
        // Given
        val text = "급전 필요하시면 연락주세요"

        // When
        val result = matcher.analyze(text)

        // Then
        assertTrue(result.isScam)
        assertTrue(result.confidence > 0.5f)
        assertTrue(result.detectedKeywords.contains("급전"))
    }

    @Test
    fun `여러 키워드 포함 시 높은 신뢰도`() {
        // Given
        val text = "급전 필요합니다. 계좌이체 부탁드립니다."

        // When
        val result = matcher.analyze(text)

        // Then
        assertTrue(result.isScam)
        assertTrue(result.confidence > 0.7f)
        assertEquals(2, result.detectedKeywords.size)
    }

    @Test
    fun `계좌번호 패턴 포함 시 고위험 판정`() {
        // Given
        val text = "여기로 이체해주세요 1234-5678-9012"

        // When
        val result = matcher.analyze(text)

        // Then
        assertTrue(result.isScam)
        assertTrue(result.confidence > 0.7f)
        assertTrue(result.reasons.any { it.contains("패턴") })
    }

    @Test
    fun `일반 대화는 스캠 아님`() {
        // Given
        val normalTexts = listOf(
            "내일 점심 같이 먹을래?",
            "회의는 3시에 시작합니다",
            "주말에 영화 보러 갈까?"
        )

        normalTexts.forEach { text ->
            // When
            val result = matcher.analyze(text)

            // Then
            assertFalse("'$text'가 스캠으로 탐지됨", result.isScam)
        }
    }

    @Test
    fun `빈 문자열은 스캠 아님`() {
        // Given
        val text = ""

        // When
        val result = matcher.analyze(text)

        // Then
        assertFalse(result.isScam)
        assertEquals(0f, result.confidence)
    }

    @Test
    fun `대소문자 구분 없이 매칭`() {
        // Given
        val texts = listOf(
            "급전 필요",
            "急전 필요",  // 한글 자음/모음 분리
            "급錢 필요"   // 한자 섞임
        )

        texts.forEach { text ->
            // When
            val result = matcher.analyze(text)

            // Then
            assertTrue("'$text'가 탐지되지 않음", result.isScam)
        }
    }
}
```

#### HybridScamDetector 테스트
```kotlin
class HybridScamDetectorTest {
    private lateinit var detector: HybridScamDetector
    private lateinit var mockKeywordMatcher: KeywordMatcher
    private lateinit var mockDbChecker: ExternalDbChecker

    @Before
    fun setup() {
        mockKeywordMatcher = mockk()
        mockDbChecker = mockk()
        detector = HybridScamDetector(mockKeywordMatcher, null, mockDbChecker)
    }

    @Test
    fun `Rule-based 신뢰도 높으면 즉시 반환`() = runTest {
        // Given
        val text = "급전 필요합니다"
        val highConfidenceResult = ScamAnalysis(
            isScam = true,
            confidence = 0.9f,
            reasons = listOf("고위험 키워드"),
            detectedKeywords = listOf("급전")
        )
        every { mockKeywordMatcher.analyze(text) } returns highConfidenceResult

        // When
        val result = detector.analyze(text)

        // Then
        assertEquals(0.9f, result.confidence, 0.01f)
        verify(exactly = 0) { mockDbChecker.check(any()) }
    }

    @Test
    fun `전화번호 포함 시 외부 DB 조회`() = runTest {
        // Given
        val text = "010-1234-5678로 연락주세요"
        val lowConfidenceResult = ScamAnalysis(
            isScam = false,
            confidence = 0.3f,
            reasons = emptyList()
        )
        val dbResult = ScamAnalysis(
            isScam = true,
            confidence = 1.0f,
            reasons = listOf("신고된 번호"),
            detectionMethod = DetectionMethod.EXTERNAL_DB
        )

        every { mockKeywordMatcher.analyze(text) } returns lowConfidenceResult
        coEvery { mockDbChecker.check(listOf("010-1234-5678")) } returns dbResult

        // When
        val result = detector.analyze(text)

        // Then
        assertTrue(result.isScam)
        coVerify { mockDbChecker.check(any()) }
    }
}
```

---

### 2. Repository 테스트

#### ScamAlertRepository 테스트
```kotlin
class ScamAlertRepositoryImplTest {
    private lateinit var repository: ScamAlertRepositoryImpl
    private lateinit var mockDao: ScamAlertDao

    @Before
    fun setup() {
        mockDao = mockk()
        repository = ScamAlertRepositoryImpl(mockDao)
    }

    @Test
    fun `알림 저장 성공`() = runTest {
        // Given
        val alert = ScamAlert(
            text = "스캠 메시지",
            sourceApp = "com.kakao.talk",
            analysis = ScamAnalysis(true, 0.9f, emptyList())
        )
        coEvery { mockDao.insertAlert(any()) } returns 1L

        // When
        val id = repository.saveAlert(alert)

        // Then
        assertEquals(1L, id)
        coVerify { mockDao.insertAlert(any()) }
    }

    @Test
    fun `알림 목록 조회`() = runTest {
        // Given
        val entities = listOf(
            ScamAlertEntity(1, "text1", "app1", true, 0.9f, emptyList(), emptyList(), 0L, false)
        )
        every { mockDao.getAllAlerts() } returns flowOf(entities)

        // When
        val alerts = repository.getAlerts().first()

        // Then
        assertEquals(1, alerts.size)
        assertEquals("text1", alerts[0].text)
    }
}
```

---

### 3. ViewModel 테스트

#### MainViewModel 테스트
```kotlin
class MainViewModelTest {
    @get:Rule
    val dispatcherRule = StandardTestDispatcher()

    private lateinit var viewModel: MainViewModel
    private lateinit var mockGetAlertsUseCase: GetScamAlertsUseCase

    @Before
    fun setup() {
        mockGetAlertsUseCase = mockk()
        viewModel = MainViewModel(mockGetAlertsUseCase)
    }

    @Test
    fun `초기 상태는 Loading`() {
        // Then
        assertTrue(viewModel.uiState.value is UiState.Loading)
    }

    @Test
    fun `알림 로드 성공 시 Success 상태`() = runTest {
        // Given
        val alerts = listOf(
            ScamAlert(1, "text", "app", ScamAnalysis(true, 0.9f, emptyList()))
        )
        every { mockGetAlertsUseCase() } returns flowOf(alerts)

        // When
        viewModel.loadAlerts()
        advanceUntilIdle()

        // Then
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertEquals(1, (state as UiState.Success).data.size)
    }

    @Test
    fun `알림 로드 실패 시 Error 상태`() = runTest {
        // Given
        every { mockGetAlertsUseCase() } throws Exception("Network error")

        // When
        viewModel.loadAlerts()
        advanceUntilIdle()

        // Then
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
    }
}
```

---

## 🔍 Integration Test Guidelines

### Database Integration Test
```kotlin
@RunWith(AndroidJUnit4::class)
class ScamAlertDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ScamAlertDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.scamAlertDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndRetrieveAlert() = runTest {
        // Given
        val entity = ScamAlertEntity(
            text = "스캠 메시지",
            sourceApp = "com.kakao.talk",
            isScam = true,
            confidence = 0.9f,
            reasons = listOf("키워드 매칭"),
            detectedKeywords = listOf("급전"),
            timestamp = System.currentTimeMillis()
        )

        // When
        val id = dao.insertAlert(entity)
        val alerts = dao.getAllAlerts().first()

        // Then
        assertEquals(1, alerts.size)
        assertEquals("스캠 메시지", alerts[0].text)
    }

    @Test
    fun dismissAlert() = runTest {
        // Given
        val entity = ScamAlertEntity(/* ... */)
        val id = dao.insertAlert(entity)

        // When
        dao.dismissAlert(id)
        val alerts = dao.getActiveAlerts().first()

        // Then
        assertEquals(0, alerts.size)
    }
}
```

---

## 📊 Test Coverage Commands

### Run Tests
```bash
# 모든 단위 테스트
./gradlew test

# 특정 테스트 클래스
./gradlew test --tests "KeywordMatcherTest"

# 특정 테스트 메서드
./gradlew test --tests "KeywordMatcherTest.급전 키워드 포함 시 스캠 탐지"

# Instrumented tests
./gradlew connectedAndroidTest
```

### Coverage Report
```bash
# Coverage 생성
./gradlew testDebugUnitTest jacocoTestReport

# 리포트 위치
open build/reports/jacoco/testDebugUnitTest/html/index.html
```

### CI/CD Integration
```yaml
# .github/workflows/test.yml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Run Unit Tests
        run: ./gradlew test
      - name: Generate Coverage
        run: ./gradlew jacocoTestReport
      - name: Upload Coverage
        uses: codecov/codecov-action@v2
```

---

## 🎯 TDD Workflow

### Red-Green-Refactor

1. **RED**: 실패하는 테스트 작성
```kotlin
@Test
fun `URL 추출 기능`() {
    val text = "여기 확인하세요 https://scam-site.com"
    val urls = urlExtractor.extract(text)
    assertEquals(1, urls.size)
    assertEquals("https://scam-site.com", urls[0])
}
// ❌ 아직 urlExtractor 구현 안 됨 - 테스트 실패
```

2. **GREEN**: 최소한의 코드로 통과
```kotlin
class UrlExtractor {
    fun extract(text: String): List<String> {
        val pattern = Regex("https?://[\\w.-]+")
        return pattern.findAll(text).map { it.value }.toList()
    }
}
// ✅ 테스트 통과
```

3. **REFACTOR**: 코드 개선
```kotlin
class UrlExtractor {
    private val urlPattern = Regex(
        "https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*)"
    )

    fun extract(text: String): List<String> {
        return urlPattern.findAll(text)
            .map { it.value }
            .distinct()
            .toList()
    }
}
// ✅ 테스트 여전히 통과, 코드는 더 견고
```

---

## 🚨 Common Testing Pitfalls

### ❌ Bad Practices
```kotlin
// 1. 테스트 이름이 불명확
@Test
fun test1() { /* ... */ }

// 2. 여러 개념을 한 테스트에
@Test
fun testEverything() {
    // 키워드 매칭 테스트
    // URL 분석 테스트
    // DB 저장 테스트
}

// 3. Hard-coded 값
@Test
fun testAnalyze() {
    val result = detector.analyze("some text")
    assertEquals(0.75f, result.confidence)  // 왜 0.75?
}

// 4. Mock 과다 사용
@Test
fun test() {
    every { mock1.method1() } returns mock2
    every { mock2.method2() } returns mock3
    // ...
}
```

### ✅ Good Practices
```kotlin
// 1. 명확한 테스트 이름
@Test
fun `급전 키워드 포함 시 스캠으로 탐지됨`() { /* ... */ }

// 2. 한 테스트에 한 개념
@Test
fun `급전 키워드 매칭`() { /* ... */ }

@Test
fun `URL 추출 및 분석`() { /* ... */ }

// 3. 의미 있는 상수
@Test
fun testAnalyze() {
    val EXPECTED_HIGH_CONFIDENCE = 0.7f
    val result = detector.analyze(SCAM_MESSAGE_WITH_TWO_KEYWORDS)
    assertTrue(result.confidence > EXPECTED_HIGH_CONFIDENCE)
}

// 4. Fake 사용 고려
class FakeScamAlertRepository : ScamAlertRepository {
    private val alerts = mutableListOf<ScamAlert>()

    override suspend fun saveAlert(alert: ScamAlert) {
        alerts.add(alert)
    }

    override fun getAlerts(): Flow<List<ScamAlert>> {
        return flowOf(alerts)
    }
}
```

---

## 📚 Test Data Builders

### ScamAnalysis Builder
```kotlin
fun scamAnalysis(
    isScam: Boolean = true,
    confidence: Float = 0.9f,
    reasons: List<String> = listOf("테스트 이유"),
    detectedKeywords: List<String> = listOf("급전"),
    detectionMethod: DetectionMethod = DetectionMethod.RULE_BASED
) = ScamAnalysis(isScam, confidence, reasons, detectedKeywords, detectionMethod)

// 사용
val analysis = scamAnalysis(confidence = 0.5f)
```

### ScamAlert Builder
```kotlin
fun scamAlert(
    id: Long = 1L,
    text: String = "테스트 메시지",
    sourceApp: String = "com.kakao.talk",
    analysis: ScamAnalysis = scamAnalysis(),
    timestamp: Long = System.currentTimeMillis()
) = ScamAlert(id, text, sourceApp, analysis, timestamp)

// 사용
val alert = scamAlert(text = "급전 필요합니다")
```

---

## 📈 Test Metrics

### Required Metrics
- **Line Coverage**: 70% minimum
- **Branch Coverage**: 60% minimum
- **Method Coverage**: 80% minimum

### Quality Gates
```kotlin
// build.gradle.kts
tasks.withType<Test> {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    violationRules {
        rule {
            limit {
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}
```

---

*Agent Version: 1.0.0*
*Last Updated: 2025-01-28*
