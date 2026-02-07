# OnGuard 변경 로그 (Changelog)

버그 수정, 기능 추가, 개선 사항을 기록합니다.

---

## [0.4.2] - 2026-02-07

### Added

#### PiiMasker.kt - 개인정보 마스킹 유틸리티 (Security Critical)
- **LLM 전송 전 PII 마스킹** (P0)
  - AccessibilityService 데이터가 외부 LLM(Gemini)으로 유출되지 않도록 보호
  - security-reviewer.md CRITICAL 위반사항 해결
- **마스킹 대상**
  - 전화번호: 부분 마스킹 (`010-****-5678`) - 대역 정보(070/050) 유지
  - 계좌번호: 완전 마스킹 (`[계좌번호]`)
  - 주민등록번호: 완전 마스킹 (`[주민번호]`)
  - 여권번호: 완전 마스킹 (`[여권번호]`)

### Changed

#### HybridScamDetector.kt - LLM 호출 시 마스킹 적용
- `llmScamDetector.analyze()` 호출 전 `PiiMasker.mask()` 적용
  - `originalText` → `PiiMasker.mask(text)`
  - `recentContext` → `PiiMasker.mask(recentContext)`
  - `currentMessage` → `PiiMasker.mask(currentMessage)`

### Security
- **AccessibilityService 데이터 보호**
  - Rule-based/API 검사에서 정상 판정된 전화번호/계좌번호도 마스킹
  - LLM은 마스킹된 텍스트로 스캠 판단 (PII 원본 노출 방지)

### Test Scenarios
```
✅ "연락처: 010-1234-5678" → "연락처: 010-****-5678"
✅ "전화: 070-1234-5678" → "전화: 070-****-5678" (대역 정보 유지)
✅ "입금: 110-123-456789" → "입금: [계좌번호]"
✅ "주민번호 901234-1234567" → "주민번호 [주민번호]"
✅ 일반 텍스트 → 변경 없음
```

---

## [0.4.1] - 2026-02-07

### Added
- **ScamType.VOICE_PHISHING 추가** (P1)
  - 보이스피싱/스미싱 전용 스캠 유형 신설
  - Counter Scam 112 API 탐지 결과를 적절히 분류

### Changed

#### LLMScamDetector.kt - JSON 출력 형식 도입
- **프롬프트 출력 형식 변경**: 자연어 → JSON
  - 기존: `[위험도: 높음/중간/낮음]` + `설명:` + `위험 패턴:`
  - 변경: `{"confidence": 75, "scamType": "PHISHING", ...}`
- **파싱 안정성 향상**
  - `extractJsonFromResponse()`: ` ```json ``` ` 또는 `{ }` 형식 추출
  - `parseJsonResponse()`: JSON 객체 파싱
  - `parseLegacyResponse()`: 기존 형식 폴백 유지
- **confidence 정밀도 개선**: 높음/중간/낮음 (3단계) → 0~100 숫자
- **scamType 직접 분류**: LLM이 직접 VOICE_PHISHING 포함 7개 유형 분류

#### PhoneAnalyzer.kt - 전화번호 패턴 수정
- **서울 지역번호 (02) 패턴 추가** (기존 누락)
  - 기존: `0[2-6][0-9]-?...` (3자리만 인식)
  - 수정: `02-?\d{3,4}-?\d{4}` (2자리 02 인식)
- **대표번호 확장**
  - 기존: `1[56][0-9]{2}` (15XX, 16XX)
  - 수정: `1[5689][0-9]{2}` (18XX, 19XX 추가, 1899 등)
- **050 번호 패턴 확장**: `050[0-9]` (0500~0509)

#### ScamTypeInferrer.kt
- **전화번호 관련 키워드 추가**
  - "보이스피싱", "스미싱", "Counter Scam", "전화번호", "신고 이력"
  - 위 키워드 포함 시 `ScamType.VOICE_PHISHING`으로 분류

#### RuleBasedWarningGenerator.kt
- **VOICE_PHISHING 경고 메시지 추가**
  - "이 전화번호는 보이스피싱/스미싱 신고 이력이 있습니다"

#### OverlayService.kt
- **VOICE_PHISHING 라벨 추가**
  - `getScamTypeLabel()`: "보이스피싱 의심"
  - `generateDefaultWarning()`: 보이스피싱 경고 문구

### Technical Details
- **LLM 출력 일관성**: Rule-based와 LLM 결과가 동일한 ScamAnalysis 구조로 반환
- **전화번호 인식률 향상**: 02-XXXX-XXXX, 1899-XXXX 등 누락 패턴 수정

### Test Scenarios
```
✅ 010-1234-5678  → 인식 → API 조회
✅ 02-1234-5678   → 인식 → API 조회 (수정됨)
✅ 0212345678     → 인식 → API 조회 (수정됨)
✅ 1899-1234      → 인식 → API 조회 (수정됨)
✅ Counter Scam 탐지 → ScamType.VOICE_PHISHING → "보이스피싱 의심" 표시
```

---

## [0.4.0] - 2026-02-06

### Added
- **Counter Scam 112 전화번호 조회 API 통합** (P0)
  - 전기통신금융사기 통합대응단 API 연동
  - 전화번호의 보이스피싱/스미싱 신고 이력 실시간 조회
  - 세션 쿠키 자동 관리 (CookieJar)

#### 신규 파일

| 파일 | 위치 | 설명 |
|------|------|------|
| `CounterScam112Api.kt` | `data/remote/api/` | Retrofit API 인터페이스 |
| `CounterScamDto.kt` | `data/remote/dto/` | Request/Response DTO |
| `CounterScamRepository.kt` | `domain/repository/` | Repository 인터페이스 |
| `CounterScamRepositoryImpl.kt` | `data/repository/` | LRU 캐시 포함 구현체 |
| `PhoneAnalysisResult.kt` | `domain/model/` | 분석 결과 모델 |
| `PhoneAnalyzer.kt` | `detector/` | 전화번호 분석기 |

#### CounterScam112Api.kt
- **세션 초기화**: `initSession()` - GET으로 JSESSIONID 획득
- **전화번호 조회**: `searchPhone()` - POST JSON 방식
- 엔드포인트: `/main/voiceNumSearchAjax.do`

#### CounterScamRepositoryImpl.kt
- **LRU 캐시**: 100개 항목, 15분 TTL
- **세션 관리**: 30분 TTL, 자동 재초기화
- **Graceful Degradation**: API 실패 시 빈 결과 반환

#### PhoneAnalyzer.kt
- **전화번호 추출**: 한국 전화번호 패턴 6종
  - 휴대폰 (010, 011, 016, 017, 018, 019)
  - 지역번호 (02, 031~064)
  - 대표번호 (1588, 1566 등)
  - 인터넷전화 (070)
  - 050 번호
  - 국제번호 (+82)
- **위험도 점수**:
  - DB 등록: 0.9f
  - 보이스피싱 이력: +0.21f
  - 스미싱 이력: +0.18f
  - 다수 신고 (5건+): +0.3f
  - 의심 대역 (070/050): 0.2f

### Changed

#### NetworkModule.kt
- **Counter Scam 112 전용 DI 추가**
  - `@CounterScamRetrofit` Qualifier
  - `@CounterScamOkHttp` Qualifier
  - CookieJar 기반 세션 쿠키 자동 관리
  - JSON Content-Type 인터셉터

#### HybridScamDetector.kt
- **PhoneAnalyzer 통합**
  - 생성자에 PhoneAnalyzer 추가
  - 전화번호 분석 결과 신뢰도 반영
  - LLM 트리거 조건에 `hasScamPhone` 추가

### Technical Details
- **API 엔드포인트 발견**:
  - 잘못된 경로: `/phishing/searchPhone.do` (Form-urlencoded)
  - 올바른 경로: `/main/voiceNumSearchAjax.do` (JSON)
- **세션 관리**: CookieJar로 JSESSIONID 자동 저장/전송
- **테스트 결과**: `01012345678` 조회 시 `totCnt=6, smsCnt=6` 반환 확인
- **빌드**: JDK 17/21 필요 (JDK 25 비호환)

### API Response Example
```json
{
  "totCnt": 6,
  "voiceCnt": 0,
  "smsCnt": 6,
  "smsList": [{"dclrCn": "..."}],
  "searchData": "최근 3개월 2025.11.06 ~ 2026.02.06"
}
```

---

## [0.3.0] - 2026-02-06

### Added
- **Gemini API 통합 (MVP)** (P0 Critical)
  - llama.cpp 대신 Google Gemini 2.5 Flash API 사용
  - 일일 API 호출 제한 관리 (`GEMINI_MAX_CALLS_PER_DAY`)
  - LLM 호출 최적화: ruleConfidence 0.5~1.0 + (금전/긴급/URL 신호) 조건부 호출

#### LLMScamDetector.kt (전면 재작성)
- **Gemini REST API 클라이언트 구현**
  - 엔드포인트: `generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash`
  - 한국어 프롬프트 빌드 (`buildPrompt()`)
  - 응답 파싱 (`parseGeminiResponse()`)
- **LRU 캐시 구현** (스크롤 중복 호출 방지)
  - `LinkedHashMap(accessOrder=true)` 기반 LRU
  - 최대 100개 분석 결과 캐시
  - 같은 메시지 스크롤 시 API 재호출 방지

#### ScamLlmClient.kt (신규)
- LLM 클라이언트 추상화 인터페이스
- 향후 서버 프록시/다른 LLM Provider 교체 용이

#### DebugLog.kt (신규)
- 디버그 빌드 전용 로깅 유틸
- `maskText()`: 민감 텍스트 마스킹
- `debugLog()`, `warnLog()`: 조건부 로그 출력

#### HybridScamDetector.kt
- **LLM 호출 조건 개선**
  - 기존: `LLMScamAnalyzer` 의존
  - 변경: `LLMScamDetector` 직접 의존
- **신호 기반 호출 최적화**
  - `hasMoneyKeyword`: 금전 키워드 존재 시
  - `hasUrgencyKeyword`: 긴급 키워드 존재 시
  - `hasUrl`: URL 포함 시
  - 위 신호 없으면 LLM 호출 스킵

#### build.gradle.kts
- **BuildConfig 필드 추가**
  - `ENABLE_LLM`: LLM 기능 활성화 플래그
  - `GEMINI_API_KEY`: API 키 (local.properties에서 로드)
  - `GEMINI_MAX_CALLS_PER_DAY`: 일일 호출 제한 (기본 100)

#### DetectorModule.kt
- `ScamLlmClient` 인터페이스 바인딩 추가
- `LLMScamDetector`를 `ScamLlmClient`로 제공

### Changed
- **OnGuardApplication.kt**
  - `initializeLLMInBackground()` 함수 제거 (Gemini는 사전 초기화 불필요)

### Deprecated
- **LLMScamAnalyzer.kt**
  - `@Deprecated` 어노테이션 추가
  - llama.cpp 기반 오프라인 LLM용으로 보존

### Removed
- **com/example/onguard/ 디렉토리 삭제**
  - `MainActivity.kt`, `OverlayService.kt` 레거시 코드 제거
- **AppModule.kt에서 LlamaManager 제거**
  - Gemini API 사용으로 불필요

### Technical Details
- API 호출 최적화:
  - Rule 기반 신뢰도 0.5~1.0 구간에서만 호출
  - 금전/긴급/URL 신호 있을 때만 호출
  - LRU 캐시로 스크롤 시 중복 호출 방지
- llama.cpp 관련 코드는 비활성화만 (향후 오프라인 모드 지원 가능성)
- 빌드 확인: BUILD SUCCESSFUL

### Configuration
```properties
# local.properties
ENABLE_LLM=true
GEMINI_API_KEY=your_api_key_here
GEMINI_MAX_CALLS_PER_DAY=100
```

---

## [0.2.9] - 2026-02-05

### Improved
- **UI/UX 전면 개선** (P1)
  - MainActivity 전체 한글화 및 UX 개선
  - 오버레이 경고 배너 디자인 개선 (Material Design 스타일)
  - Loading/Error 상태 UI 개선

#### MainActivity.kt
- **한글화 완료**
  - 모든 UI 텍스트 한글화 ("보호 활성화됨", "탐지된 위협" 등)
  - 앱 이름 번역 (kakao→카카오톡, telegram→텔레그램, daangn→당근마켓 등)
  - 위험도 레벨 한글화 (높음/중간/낮음)
- **UX 개선**
  - SnackbarHost 추가로 에러 처리 개선
  - FilledTonalButton 적용
  - ServiceStatusCard에 설명 추가

#### MainViewModel.kt
- **Loading 상태 관리 추가**
  - `MainUiState.isLoading` 필드 추가
  - `loadRecentAlerts()` 시 로딩 상태 표시

#### overlay_scam_warning.xml
- **Material Design 스타일 적용**
  - CardView 스타일 FrameLayout 구조
  - 헤더/본문 분리 레이아웃
  - 위험도별 배경색 동적 적용

#### 새 Drawable 리소스
- `overlay_card_background.xml`: 카드 배경 (16dp 라운드)
- `btn_overlay_outline.xml`: 아웃라인 버튼 스타일
- `btn_overlay_transparent.xml`: 투명 버튼 스타일

#### OverlayService.kt
- 새 레이아웃에 맞게 `warning_header`에 배경색 적용

### Fixed
- **빌드 환경 문제 해결**
  - Gradle 8.5 → 8.13 업그레이드 (AGP 8.13.2 호환)
  - Material Icons Extended 의존성 추가
  - LLM 코드 override 모디파이어 추가
  - `setMaxTokens` → `setNPredict` 수정 (java-llama.cpp API 변경)
  - 기본 파라미터 값 제거 (override 함수 규칙)

### Changed
- **build.gradle.kts**
  - `material-icons-extended` 의존성 추가
  - 네이티브 CMake 빌드 임시 비활성화 (Windows 환경 이슈)
- **gradle-wrapper.properties**
  - `distributionUrl` 8.5 → 8.13 업그레이드

### Technical Details
- 영향 범위: Presentation 레이어 (MainActivity, MainViewModel, OverlayService)
- 호환성: Android 8.0+ (minSdk 26)
- 빌드 확인: BUILD SUCCESSFUL

---

## [0.2.8] - 2026-02-03

### Added
- **채팅방 내부에서만 탐지** (P1 UX)
  - 증상: 채팅 목록 화면에서도 메시지 미리보기가 스캠으로 탐지됨
  - 해결: 메시지 입력 필드(EditText) 존재 여부로 채팅방 내부 판별

#### ScamDetectionAccessibilityService.kt
- **새 함수 추가**
  - `isInsideChatRoom()`: 채팅방 내부인지 확인
  - `hasMessageInputField()`: 재귀적으로 EditText/입력 필드 탐색
- **processEvent() 수정**
  - 채팅방 내부가 아니면 탐지 스킵 (채팅 목록, 설정 화면 등)
- **탐지 로직**
  - `node.isEditable` 또는 "EditText" 클래스 → 채팅방
  - 리소스 ID에 input/edit/compose/message 등 포함 → 채팅방
  - 최대 탐색 깊이 15 (성능 보호)

### Technical Details
- 동작 원리:
  1. `rootInActiveWindow` 노드 획득
  2. `hasMessageInputField()`로 입력 필드 탐색 (깊이 15 제한)
  3. EditText 발견 → 채팅방 내부로 판정, 탐지 진행
  4. EditText 없음 → 채팅 목록/설정으로 판정, 스킵
- 지원 앱: 카카오톡, 텔레그램, 당근마켓, SMS 등 (입력 필드 기반)
- 성능: 추가 탐색 오버헤드 최소화 (isEditable 빠른 체크)

### Test Scenarios
1. 채팅 목록: 카카오톡 목록 화면 → 탐지 안 함 ✓
2. 채팅방 내부: 대화 화면 진입 → 정상 탐지 ✓
3. 설정 화면: 앱 설정 → 탐지 안 함 ✓
4. SMS: 메시지 앱 대화 화면 → 정상 탐지 ✓

---

## [0.2.7] - 2026-02-03

### Improved
- **스크롤 중복 알림 방지 강화** (P1 UX Critical)
  - 기존 문제: 10초 이상 지난 후 스크롤하면 과거 메시지도 재탐지됨
  - 해결: 스크롤 이벤트 감지 기반 "스크롤 모드" 도입

#### ScamDetectionAccessibilityService.kt
- **스크롤 이벤트 감지 추가**
  - `TYPE_VIEW_SCROLLED` 이벤트 타입 추가
  - `lastScrollTimestamp`: 마지막 스크롤 시각 기록
  - `SCROLL_MODE_TIMEOUT_MS = 3_000L`: 스크롤 후 3초간 "스크롤 모드"
- **캐시 만료 시간 확장**
  - `CACHE_EXPIRY_MS = 3_600_000L` (1시간, 세션 기반)
  - `CACHE_MAX_SIZE = 100` (50 → 100으로 증가)
- **새 함수 추가**
  - `isInScrollMode()`: 스크롤 모드 여부 확인 (3초 이내)
  - `isCached()`: 캐시 히트 여부 확인 (만료 체크 포함)
- **analyzeForScam() 로직 개선**
  - 스크롤 모드 + 캐시 히트 → 스킵 (과거 메시지)
  - 스크롤 모드 + 캐시 미스 → 탐지 (새로운 스캠 유형)
  - 일반 모드 → 정상 탐지 (새 메시지)

### Technical Details
- 동작 원리:
  1. 스크롤 이벤트 발생 → "스크롤 모드" 진입 (3초간)
  2. 스크롤 모드 + 캐시된 키워드 → 과거 메시지로 간주, 스킵
  3. 3초간 스크롤 없음 → "일반 모드"로 전환, 새 메시지로 탐지
- 메모리: ~20KB 미만 (100개 캐시 항목)

### Test Scenarios
1. 스크롤 중복 방지: 과거 메시지로 스크롤 → 캐시에 있으면 스킵
2. 새 메시지 탐지: 스크롤 멈추고 3초 후 새 메시지 → 정상 탐지
3. 스크롤 중 새 스캠 유형: 캐시에 없는 키워드 → 탐지 (안전 우선)
4. 1시간 캐시: 세션 동안 탐지된 키워드 유지

---

## [0.2.6] - 2026-02-03

### Added
- **스크롤 중복 알림 방지 기능** (P1 UX Critical)
  - 증상: 채팅방에서 과거 메시지로 스크롤 시 이미 탐지된 스캠 메시지가 새로운 알림으로 재표시
  - 사용자 요구사항: 스크롤로 보이는 과거 메시지는 무시, 새로 도착한 메시지는 같은 내용이어도 탐지

#### ScamDetectionAccessibilityService.kt
- **세션 캐시 상수 추가**
  - `SCROLL_DUPLICATE_WINDOW_MS = 10_000L` (10초 윈도우)
  - `CACHE_MAX_SIZE = 50` (메모리 관리)
- **캐시 맵 추가**
  - `recentAlertCache: MutableMap<String, Long>` (키: 앱+키워드, 값: 알림시각)
- **중복 방지 함수 추가**
  - `generateAlertCacheKey()`: 앱 패키지 + 정렬된 키워드 목록으로 캐시 키 생성
  - `isRecentlyAlerted()`: 10초 내 동일 키워드 조합 알림 여부 확인
  - `registerAlert()`: 캐시 등록 + 오래된 항목 자동 정리
- **analyzeForScam() 수정**
  - 스캠 탐지 시 캐시 체크 후 중복이면 알림 스킵
  - 새 알림만 캐시 등록 후 경고 표시

### Technical Details
- 영향 범위: ScamDetectionAccessibilityService 알림 로직
- 메모리: ~10KB 미만 (50개 캐시 항목)
- DB 변경 없음 (메모리 캐시만 사용)

### Test Scenarios
1. 스크롤 중복 방지: 10초 내 같은 키워드 → 알림 1회만
2. 새 메시지 탐지: 10초 후 같은 내용 새 메시지 → 정상 탐지
3. 다른 키워드: 10초 내 다른 스캠 메시지 → 별도 알림

---

## [0.2.5] - 2026-02-03

### Fixed
- **스캠 미탐지 버그 수정 (False Negative)** (P0 Critical)
  - 증상: "급전 필요합니다 송금해주세요" 등 스캠 메시지가 탐지되지 않음
  - 근본 원인: 임계값 조건이 `> 0.5f`로 설정되어 정확히 0.5f인 경우 통과하지 못함
  - 예시: "급전"(0.25f) + "송금"(0.25f) = 0.5f → isScam = false (버그)

#### KeywordMatcher.kt
- 임계값 조건 수정: `> 0.5f` → `>= 0.5f`
  - 정확히 0.5f 신뢰도도 스캠으로 판정

#### ScamDetectionAccessibilityService.kt
- 중복 임계값 체크 제거
  - 기존: `if (analysis.isScam && analysis.confidence >= SCAM_THRESHOLD)`
  - 수정: `if (analysis.isScam)` (isScam이 이미 threshold 포함)
- `rootInActiveWindow` null 재시도 로직 추가
  - 최대 3회 재시도 (50ms 간격)
  - 윈도우 로딩 지연으로 인한 이벤트 손실 방지
- **NullPointerException 버그 수정** (P0 Critical)
  - 증상: `event.packageName.toString()` 호출 시 NPE 발생
  - 원인: `AccessibilityEvent`가 코루틴 delay 동안 시스템에 의해 재활용됨
  - 수정: `processEvent()` 시작 시 `packageName`을 미리 캡처하여 사용

#### OverlayService.kt
- `Settings.canDrawOverlays()` 권한 체크 추가
  - 오버레이 권한 없을 시 조기 종료 및 로그 출력
  - 조용한 실패 방지

### Added
- **KeywordMatcherTest.kt 테스트 추가**
  - `정확히 0점5 신뢰도는 스캠으로 판정` 테스트
  - `0점49 신뢰도는 스캠 아님` 테스트
  - `대소문자 구분 없이 탐지` 테스트 수정 (0.5f 경계값 스캠 판정)

### Technical Details
- 영향 범위: 전체 스캠 탐지 파이프라인
- 근본 원인: 임계값 경계 조건 오류
- 테스트: 0.5f 경계값 테스트 추가

---

## [0.2.4] - 2026-01-31

### Changed
- **탐지 민감도 향상** (P1)

#### ScamDetectionAccessibilityService.kt
- `MIN_TEXT_LENGTH` 20 → 10으로 감소
  - 노드 필터링이 키보드/UI를 이미 제외하므로 완화
  - 짧은 스캠 메시지도 탐지: "입금해주세요"(6자), "OTP알려줘"(7자)
- 개별 노드 텍스트 추출 기준 완화:
  - node.text 최소 길이: 5자 → 3자 ("OTP" 등 짧은 키워드 추출)
  - contentDescription 최소 길이: 10자 → 5자

### Technical Details
- 변경 이유: 정확도 중시 - 짧은 위험 키워드도 확실히 탐지
- 오탐 방지: 노드 필터링(shouldSkipEntireSubtree)이 이미 적용됨
- 주의: 단일 문자/숫자는 여전히 제외 (3자 미만)

---

## [0.2.3] - 2026-01-31

### Added
- **스캠 탐지 패턴 및 키워드 강화** (P1)

#### KeywordMatcher.kt - 정규식 패턴 개선
- 한국 은행 계좌번호 형식 확장:
  - 3단 형식: `\d{3,4}-\d{2,6}-\d{4,7}` (신한, 우리, 하나 등)
  - 국민은행 6-2-6 형식: `\d{6}-\d{2}-\d{6}`
  - 농협 4단 형식: `\d{3}-\d{4}-\d{4}-\d{2}`
- 전화번호 패턴 개선:
  - 휴대폰: `01[016789]-?\d{3,4}-?\d{4}` (010, 011, 016, 017, 018, 019)
  - 일반전화: `0\d{1,2}-\d{3,4}-\d{4}` (02, 031 등)
  - 대표번호: `1[56][0-9]{2}-\d{4}` (1588, 1544 등)
- 가상화폐 지갑 주소 추가:
  - 비트코인: `(1|3|bc1)[a-zA-Z0-9]{25,39}`
  - 이더리움: `0x[a-fA-F0-9]{40}`
- URL 패턴 확장:
  - 단축 URL: bit.ly, tinyurl.com, t.co, is.gd 등 추가
  - 의심 도메인: .xyz, .top, .work, .click, .online 추가
  - IP 직접 접근: `\d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}` (0.35 가중치)

#### KeywordMatcher.kt - 키워드 추가
- **HIGH (0.25f) 키워드 추가**:
  - 은행명: 국민은행, 신한은행, 우리은행, 하나은행, 농협, 카카오뱅크, 토스뱅크
  - 가상화폐: 이더리움, 리플, 도지코인, NFT, 에어드랍, ICO, 채굴, 지갑주소
  - 거래소: 바이낸스, 업비트, 빗썸, 코인원
  - 투자 사기: 리딩방, 시그널방, VIP방, 수익인증
  - 로맨스 스캠: 사랑해요, 병원비, 수술비, 항공권비용, 비자비용
  - SNS 피싱: 계정잠금, 비밀번호변경, 로그인실패, 본인확인
- **MEDIUM (0.15f) 키워드 추가**:
  - 택배 사칭: 배송조회, 배송실패, 주소확인
  - SNS 계정: 인스타그램, 페이스북, 카카오계정, 네이버계정, 계정복구
  - 정부 지원금: 긴급재난지원금, 소상공인지원, 청년지원금, 복지급여

#### PatternValidationTest.kt - 테스트 추가
- 한국 주요 은행 계좌번호 형식별 테스트
- 휴대폰/일반전화/대표번호 테스트
- 가상화폐 지갑 주소 테스트
- 로맨스/가상화폐/SNS/정부지원금 스캠 탐지 테스트

### Technical Details
- 영향 범위: KeywordMatcher 정규식 패턴 및 키워드 DB
- 테스트 커버리지: PatternValidationTest.kt 추가 (26개 테스트)
- 하위 호환성: 유지 (기존 패턴 확장, 제거 없음)

---

## [0.2.2] - 2026-01-31

### Fixed
- **False Negative 스캠 미탐지 버그 수정** (P0 Critical)
  - 증상: 실제 스캠 메시지("급전 필요", "계좌번호", "입금", "인증번호" 포함)가 탐지되지 않음
  - 원인: `shouldSkipNode()`가 true 반환 시 자식 노드까지 전체 스킵
  - 문제 패턴: `SKIP_RESOURCE_ID_PATTERNS`에 "title", "header", "button" 등 포함
    - 카카오톡 메시지 컨테이너가 이 패턴에 걸려서 모든 메시지 텍스트 스킵됨

### Changed

#### ScamDetectionAccessibilityService.kt
- 노드 필터링 로직 2단계 분리:
  - `shouldSkipEntireSubtree()`: 전체 서브트리 스킵 (EditText, 키보드만)
  - `shouldSkipNodeTextOnly()`: 현재 노드 텍스트만 스킵, 자식은 계속 탐색
- `extractTextFromNode()` 수정:
  - 기존: `shouldSkipNode()` true → 즉시 return "" (자식 미탐색)
  - 수정: 서브트리 스킵 / 텍스트만 스킵 분리, 자식은 항상 탐색
- 불필요한 상수 제거:
  - `SKIP_VIEW_CLASSES` 제거 (함수 내부로 이동)
  - `SKIP_RESOURCE_ID_PATTERNS` 제거 (너무 광범위, "title"/"header" 문제)
- 엄격한 필터링 패턴만 유지:
  - "toolbar", "action_bar", "status_bar", "navigation_bar"

### Technical Details
- 근본 원인: 노드 필터링이 부모 컨테이너에 매칭되면 자식(메시지 텍스트)도 스킵됨
- 수정 원칙: 키보드/입력필드만 전체 스킵, 나머지는 텍스트만 스킵하고 자식 계속 탐색
- 테스트: 카카오톡에서 스캠 메시지 탐지 확인 필요

---

## [0.2.1] - 2026-01-30

### Fixed
- **False Positive 스캠 탐지 버그 수정** (P0 Critical)
  - 증상: 빈 채팅방에서 키보드만 표시되어도 스캠 경고가 발생
  - 원인: `extractTextFromNode()`가 키보드 UI, 입력 필드 등 모든 텍스트를 무차별 추출
  - 탐지 이유: "계좌번호 패턴 감지, 휴대폰 번호 감지, 전화번호 감지" (75% 위험도)

### Changed

#### ScamDetectionAccessibilityService.kt
- `MIN_TEXT_LENGTH` 10 -> 20으로 증가 (짧은 UI 텍스트 필터링)
- 노드 필터링 상수 추가:
  - `SKIP_VIEW_CLASSES`: EditText, KeyboardView 등 9개 클래스
  - `KEYBOARD_PACKAGE_PREFIXES`: Gboard, Samsung Keyboard 등 7개 패키지
  - `SKIP_RESOURCE_ID_PATTERNS`: input, edit, keyboard 등 15개 패턴
  - `ACCESSIBILITY_LABEL_PATTERNS`: 버튼, 메뉴 등 UI 라벨 패턴
- `shouldSkipNode()` 함수 추가: 노드 필터링 로직
- `isAccessibilityLabel()` 함수 추가: 접근성 라벨 감지
- `extractTextFromNode()` 수정:
  - 필터링된 노드는 빈 문자열 반환
  - 텍스트 최소 길이 5자 이상만 추출
  - contentDescription은 10자 이상 + 비라벨만 추출

#### KeywordMatcher.kt
- 패턴 가중치 감소 (오탐 방지):
  - 계좌번호 패턴: 0.35 -> 0.2
  - 연속 숫자: 0.3 -> 0.1
  - 휴대폰 번호: 0.2 -> 0.1
  - 전화번호: 0.2 -> 0.1
- 최소 패턴 매칭 요구사항 추가:
  - 격리된 단일 패턴은 신뢰도에 미반영
  - 조건: 2개 이상 패턴 OR (1개 패턴 + 키워드)

#### KeywordMatcherTest.kt
- 기존 테스트 수정: `전화번호 패턴만 있으면 스캠 아님` (새 동작 반영)
- 새 테스트 추가:
  - `전화번호 패턴 + 키워드 조합시 감지`
  - `키보드 숫자열은 스캠 아님`
  - `격리된 계좌번호 패턴은 스캠 아님`
  - `격리된 연속 숫자는 스캠 아님`
  - `여러 패턴 조합시 감지`

### Technical Details
- 영향 범위: AccessibilityService 텍스트 추출 로직
- 하위 호환성: 유지 (실제 스캠 탐지 정확도 유지)
- 롤백 계획: MIN_TEXT_LENGTH 10으로 복원, 패턴 가중치 원복

---

## [0.2.0] - 2026-01-29

### Added
- 코드 주석 및 문서화 완료
  - HybridScamDetector: 신뢰도 계산 공식, SLM팀 TODO
  - KeywordMatcher: 3단계 가중치 체계 근거
  - UrlAnalyzer: 위험도 점수 기준 상수화
  - ScamDetectionAccessibilityService: 성능 상수 근거
  - OverlayService: UI/UX팀 연동 포인트

### Changed
- .gitignore 업데이트 (.kotlin/, gradle-*.zip 등 추가)

### Removed
- 불필요한 파일 정리 (gradle-9.3.0-bin.zip 136MB 등)

---

## [0.1.0] - 2026-01-28

### Added
- 초기 백엔드 구현 완료
  - KeywordMatcher: 235개 키워드 + 9개 정규식 패턴
  - UrlAnalyzer: KISA DB + 휴리스틱 분석
  - HybridScamDetector: 신뢰도 합산 로직
  - ScamDetectionAccessibilityService: 실시간 모니터링
  - OverlayService: 경고 오버레이 UI
  - Room Database: 스캠 알림 저장

### Technical Stack
- Kotlin 2.0.21 + Gradle 9.3.0
- Hilt DI + Coroutines + Flow
- TensorFlow Lite (모델 미구현)
- Jetpack Compose + XML (오버레이)

---

*Maintained by Backend Team*
*Last Updated: 2026-02-06 (v0.3.0)*
