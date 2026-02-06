# OnGuard 개발 로드맵

데이콘 피싱/스캠 예방 서비스 경진대회 출품을 위한 단계별 개발 계획

---

## 📅 개발 일정 개요

**총 개발 기간**: 4주 (28일)
**목표**: MVP 완성 → 테스트 → 제출

---

## Week 1: 기반 인프라 구축 (Day 1-7)

### Day 1: 프로젝트 초기 설정 ✅
- [x] Gradle 빌드 시스템 구축
- [x] Clean Architecture 디렉토리 구조 생성
- [x] Hilt DI 설정
- [x] AndroidManifest 및 권한 설정
- [x] 기본 도메인 모델 정의

**검증**: `./gradlew build` 성공

---

### Day 2: 접근성 서비스 구현
**목표**: AccessibilityService로 메신저 앱 텍스트 추출

**Task**:
```
feature/accessibility-service 브랜치 생성
```

1. **ScamDetectionAccessibilityService 완성**
   - `onAccessibilityEvent()` 이벤트 핸들링
   - 대상 앱 패키지 필터링 (카카오톡, 텔레그램, 왓츠앱, 메신저)
   - 텍스트 추출 로직 구현
   - WeakReference로 메모리 누수 방지

2. **PermissionHelper 유틸 작성**
   - 접근성 서비스 활성화 여부 체크
   - 설정 화면으로 이동 Intent

3. **테스트**
   - 카카오톡에서 메시지 수신 시 로그 출력 확인
   - 대상 외 앱에서는 동작하지 않는지 확인

**Commit**: `feat(service): implement accessibility service for text extraction`

**검증**: Logcat에서 추출된 텍스트 확인

---

### Day 3: Rule-based 스캠 탐지 엔진
**목표**: 키워드 매칭으로 1차 필터링

**Task**:
```
feature/rule-based-detector 브랜치 생성
```

1. **KeywordMatcher 고도화**
   - 스캠 키워드 DB 확장 (100개 이상)
   - 정규식 패턴 추가 (계좌번호, 전화번호, URL)
   - 키워드 가중치 시스템 (critical, high, medium)

2. **UrlAnalyzer 구현**
   - URL 추출 로직
   - KISA 피싱사이트 DB 연동 준비 (CSV 파싱)

3. **HybridScamDetector 통합**
   - KeywordMatcher + UrlAnalyzer 결합
   - 신뢰도 점수 계산 알고리즘

4. **단위 테스트 작성**
   ```kotlin
   class KeywordMatcherTest {
       @Test fun `급전 키워드 포함 시 스캠 탐지`()
       @Test fun `계좌번호 패턴 포함 시 고위험 판정`()
       @Test fun `일반 대화는 스캠 아님`()
   }
   ```

**Commit**: `feat(detector): add rule-based scam detection with keyword matching`

**검증**: 테스트 커버리지 90% 이상, `./gradlew test` 통과

---

### Day 4: 더치트 API 연동
**목표**: 외부 DB로 계좌/전화번호 검증

**Task**:
```
feature/thecheat-api 브랜치 생성
```

1. **ThecheatRepository 구현**
   - `checkPhoneNumber(phone: String)`
   - `checkAccountNumber(account: String, bankCode: String?)`
   - Retrofit 에러 핸들링
   - 타임아웃 처리 (3초)

2. **ScamCheckUseCase 작성**
   - Repository 호출
   - 결과 캐싱 (Room)
   - 오프라인 대응

3. **통합 테스트**
   - MockK로 API 응답 mock
   - 성공/실패/타임아웃 시나리오

**Commit**: `feat(api): integrate Thecheat API for scam verification`

**검증**: Postman으로 실제 API 호출 테스트

---

### Day 5: 로컬 DB 구축
**목표**: Room으로 스캠 알림 이력 저장

**Task**:
```
feature/local-database 브랜치 생성
```

1. **ScamAlertEntity 및 DAO 완성**
   - CRUD 메서드 구현
   - Flow로 실시간 업데이트

2. **ScamAlertRepository 구현**
   - 알림 저장/조회/삭제
   - 7일 이상 오래된 알림 자동 삭제

3. **DatabaseModule DI 연결**

4. **Migration 전략 수립**
   - `fallbackToDestructiveMigration()` (MVP용)

**Commit**: `feat(data): implement Room database for scam alerts`

**검증**: Database Inspector로 데이터 확인

---

### Day 6-7: Onboarding & 권한 요청 UI
**목표**: Prominent Disclosure 준수

**Task**:
```
feature/onboarding-ui 브랜치 생성
```

1. **OnboardingScreen (Compose)**
   - 서비스 소개 (ViewPager 스타일)
   - 데이터 수집 범위 명시
   - 프라이버시 정책 동의

2. **PermissionRequestScreen**
   - 접근성 서비스 권한 요청
   - 오버레이 권한 요청 (SYSTEM_ALERT_WINDOW)
   - 알림 권한 요청 (Android 13+)

3. **ConsentFlow**
   - 체크박스 필수 선택
   - "동의하고 시작하기" 버튼
   - SharedPreferences에 동의 여부 저장

**Commit**: `feat(ui): add onboarding flow with consent management`

**검증**:
- 첫 실행 시 Onboarding 표시
- 모든 권한 허용 후 메인 화면 진입

---

## Week 2: 핵심 기능 구현 (Day 8-14)

### Day 8-9: 오버레이 경고 시스템
**목표**: 스캠 감지 시 화면 상단 경고 배너 표시

**Task**:
```
feature/overlay-warning 브랜치 생성
```

1. **OverlayService 완성**
   - WindowManager로 View 추가
   - FLAG 설정 (NOT_FOCUSABLE, LAYOUT_IN_SCREEN)
   - Android 8.0+ TYPE_APPLICATION_OVERLAY

2. **WarningBannerView (XML)**
   - 경고 아이콘 + 메시지
   - "상세보기" / "무시" 버튼
   - 자동 사라짐 (10초)

3. **알림 우선순위**
   - 신뢰도 90% 이상: 빨간색 배너
   - 신뢰도 70-90%: 주황색 배너
   - 신뢰도 50-70%: 노란색 배너

4. **사용자 액션 처리**
   - "상세보기": DetailActivity 실행
   - "무시": DB에 isDismissed=true 저장

**Commit**: `feat(overlay): implement scam warning overlay system`

**검증**:
- 테스트 메시지로 오버레이 트리거
- 여러 앱에서 정상 동작 확인

---

### Day 10-11: TensorFlow Lite 통합 (선택)
**목표**: 온디바이스 ML로 정교한 분류

**Task**:
```
feature/ml-classifier 브랜치 생성
```

1. **사전 학습 모델 준비**
   - Kaggle/HuggingFace에서 한국어 피싱 탐지 모델 검색
   - 또는 직접 학습 (BERT 기반, 피싱 문자 데이터셋)
   - .tflite 변환

2. **MlClassifier 구현**
   - TFLite Interpreter 초기화
   - 텍스트 전처리 (토크나이징)
   - 추론 실행 (<100ms)
   - NNAPI 하드웨어 가속 활용

3. **HybridScamDetector 연동**
   - Rule-based 결과가 애매할 때 ML 호출
   - 두 결과 합산하여 최종 신뢰도 계산

**Commit**: `feat(ml): add TensorFlow Lite on-device classifier`

**검증**:
- 모델 크기 < 50MB
- 추론 시간 < 100ms
- 테스트 케이스 정확도 > 85%

**Note**: 시간 부족 시 Skip 가능 (Rule-based만으로도 MVP 가능)

---

### Day 12: 메인 화면 구현
**목표**: 알림 이력, 통계, 설정

**Task**:
```
feature/main-ui 브랜치 생성
```

1. **MainScreen (Compose)**
   - Tab 구조: 홈 / 이력 / 설정
   - 홈: 오늘의 스캠 탐지 횟수, 최근 알림
   - 이력: LazyColumn으로 전체 알림 리스트
   - 설정: 서비스 on/off, 민감도 조절

2. **MainViewModel**
   - Flow로 DB 데이터 구독
   - StateFlow로 UI 상태 관리

3. **AlertDetailScreen**
   - 알림 상세 정보
   - 탐지 근거 표시 (키워드, 패턴)
   - 신고하기 버튼 (경찰청 사이버수사국 연계)

**Commit**: `feat(ui): implement main screen with alert history`

**검증**:
- 알림 리스트 스크롤 성능 (1000개 항목)
- 다크모드 대응

---

### Day 13: 통합 테스트
**목표**: End-to-End 시나리오 검증

**Task**:

1. **시나리오 테스트**
   ```
   1. 앱 설치 → Onboarding → 권한 허용
   2. 카카오톡 실행 → 피싱 메시지 수신
   3. 오버레이 경고 표시
   4. 상세보기 클릭 → 알림 저장 확인
   5. 메인 화면 → 이력 탭에서 조회
   ```

2. **성능 테스트**
   - 메모리 사용량 (<300MB)
   - CPU 사용률 (<5% 대기 시)
   - 배터리 소모량 측정

3. **호환성 테스트**
   - Android 8.0, 10, 12, 13, 14 각각 테스트
   - 삼성, LG, 구글 폰 테스트

**Commit**: `test: add end-to-end integration tests`

**검증**: 모든 시나리오 통과

---

### Day 14: Week 2 마무리 & 리팩토링
**목표**: 코드 품질 향상

**Task**:

1. **Lint & Detekt 실행**
   ```bash
   ./gradlew lint
   ./gradlew detekt
   ```

2. **ktlint 포맷팅**
   ```bash
   ./gradlew ktlintFormat
   ```

3. **테스트 커버리지 확인**
   ```bash
   ./gradlew testDebugUnitTest jacocoTestReport
   ```
   - 목표: 전체 70% 이상
   - detector 패키지: 90% 이상

4. **문서화**
   - 주요 클래스 KDoc 작성
   - API 사용 예시 주석

**PR 생성**: Week 2 기능 → main 병합

---

## Week 3: 고급 기능 & 최적화 (Day 15-21)

### Day 15-16: KISA 피싱사이트 DB 연동
**목표**: 정부 공공 DB로 URL 검증

**Task**:
```
feature/kisa-phishing-db 브랜치 생성
```

1. **CSV 다운로드 자동화**
   - WorkManager로 주 1회 갱신
   - 공공데이터포털 API 호출

2. **PhishingUrlDatabase (Room)**
   - Entity: `PhishingUrlEntity(url, dateAdded)`
   - DAO: `isPhishingUrl(url: String): Boolean`

3. **UrlAnalyzer 고도화**
   - 단축 URL 확장 (bit.ly, goo.gl)
   - 도메인 추출
   - KISA DB 조회

**Commit**: `feat(data): integrate KISA phishing site database`

**검증**: 알려진 피싱 URL 탐지율 100%

---

### Day 17: 백그라운드 최적화
**목표**: 배터리 효율성 개선

**Task**:
```
feature/background-optimization 브랜치 생성
```

1. **Debouncing 적용**
   - 텍스트 변경 이벤트 100ms 딜레이
   - 중복 분석 방지

2. **캐싱 전략**
   - 최근 분석 결과 메모리 캐시 (LruCache)
   - 동일 텍스트 재분석 방지

3. **배터리 최적화**
   - Doze 모드 대응
   - 백그라운드 제한 준수

**Commit**: `perf: optimize background processing for battery efficiency`

**검증**: Battery Historian으로 소비량 측정

---

### Day 18-19: 보안 강화
**목표**: Google Play 정책 완벽 준수

**Task**:
```
feature/security-enhancements 브랜치 생성
```

1. **데이터 암호화**
   - Room DB 암호화 (SQLCipher)
   - SharedPreferences 암호화 (EncryptedSharedPreferences)

2. **네트워크 보안**
   - Certificate Pinning
   - ProGuard 난독화 규칙

3. **접근성 서비스 투명성**
   - 데이터 수집 로그 (사용자 조회 가능)
   - 언제든 서비스 비활성화 안내

**Commit**: `security: enhance data encryption and network security`

**검증**: 보안 취약점 스캔 (MobSF)

---

### Day 20: 신고 기능
**목표**: 탐지된 스캠을 경찰청에 신고

**Task**:
```
feature/report-scam 브랜치 생성
```

1. **ReportScreen (Compose)**
   - 스캠 내용 자동 입력
   - 추가 정보 입력 (발신자 번호 등)
   - 경찰청 사이버안전국 연계

2. **ReportUseCase**
   - 데이터 익명화
   - 신고 이력 저장

**Commit**: `feat(report): add scam report to police agency`

**검증**: 테스트 신고 전송 성공

---

### Day 21: UI/UX 개선
**목표**: 사용자 경험 향상

**Task**:

1. **애니메이션 추가**
   - 오버레이 슬라이드 인 효과
   - 리스트 아이템 Fade-in

2. **접근성 개선**
   - TalkBack 대응
   - 고대비 모드

3. **다국어 지원 (선택)**
   - 영어 번역

**Commit**: `ui: improve UX with animations and accessibility`

**검증**: 접근성 스캐너 통과

---

## Week 4: 출시 준비 (Day 22-28)

### Day 22-23: QA & 버그 수정
**목표**: 안정성 확보

**Task**:

1. **Monkey Testing**
   ```bash
   adb shell monkey -p com.scamguard -v 10000
   ```

2. **메모리 누수 검사**
   - LeakCanary 연동
   - Profile 분석

3. **크래시 수정**
   - Firebase Crashlytics (선택)

**Commit**: `fix: resolve critical bugs from QA`

---

### Day 24: Release 빌드
**목표**: 서명된 APK 생성

**Task**:

1. **Keystore 생성**
   ```bash
   keytool -genkey -v -keystore scamguard.jks -keyalg RSA -keysize 2048 -validity 10000 -alias scamguard
   ```

2. **ProGuard 최종 점검**
   - TFLite 모델 제외
   - 테스트 APK 크기 < 100MB

3. **버전 관리**
   - versionCode = 1
   - versionName = "1.0.0"

**생성**: `app-release.aab`

---

### Day 25-26: Play Store 제출 준비
**목표**: 스토어 심사 통과

**Task**:

1. **스토어 리스팅 작성**
   - 앱 이름: ScamGuard - 스캠 탐지 보호
   - 설명: 500자 (한글/영문)
   - 스크린샷: 휴대폰 8장, 태블릿 8장

2. **기능 시연 비디오**
   - 30초~2분
   - 화면 녹화 + 자막

3. **개인정보처리방침**
   - GitHub Pages 호스팅
   - 접근성 데이터 수집 명시

4. **접근성 서비스 권한 선언서**
   - Google Form 작성
   - 사용 목적 상세 설명

**산출물**:
- 선언서 PDF
- 시연 비디오 (YouTube Unlisted)
- 개인정보처리방침 URL

---

### Day 27: 데이콘 제출
**목표**: 경진대회 제출 완료

**Task**:

1. **제출 자료 준비**
   - APK/AAB 파일
   - 소스 코드 (GitHub 링크)
   - 발표 자료 (PPT)
   - 시연 비디오

2. **README 최종 업데이트**
   - 프로젝트 소개
   - 설치 방법
   - 기능 설명
   - 스크린샷

3. **데이콘 플랫폼 제출**

---

### Day 28: 예비일
**목표**: 버퍼

**Task**:
- 마감 전 최종 점검
- 긴급 버그 수정
- 발표 연습

---

## 🎯 핵심 마일스톤

| Week | Milestone | 완료 조건 |
|------|-----------|----------|
| 1 | 기반 인프라 | 빌드 성공 + 접근성 서비스 동작 |
| 2 | MVP 완성 | 스캠 탐지 + 오버레이 경고 동작 |
| 3 | 고급 기능 | KISA DB 연동 + 최적화 |
| 4 | 출시 준비 | Play Store 제출 가능 상태 |

---

## ⚠️ 리스크 관리

### 고위험 리스크
1. **접근성 서비스 거부**:
   - 완화: Onboarding에서 명확한 설명
   - 대안: 클립보드 모니터링 (권한 더 낮음)

2. **Play Store 거부**:
   - 완화: Prominent Disclosure 철저히 준수
   - 대안: APK 직접 배포

3. **ML 모델 성능 부족**:
   - 완화: Rule-based만으로도 MVP 가능
   - 대안: 서버 API 호출 (프라이버시 이슈 있음)

### 중위험 리스크
1. **특정 앱 비호환성**:
   - 완화: 주요 앱 우선 대응
   - 대안: 커뮤니티 피드백으로 점진적 추가

2. **배터리 소모 클레임**:
   - 완화: 철저한 최적화
   - 대안: 배터리 세이버 모드 제공

---

## 📝 일일 체크리스트 템플릿

매일 아침 확인:
- [ ] 오늘의 목표 명확히 정의
- [ ] 브랜치 생성 (feature/*)
- [ ] 테스트 작성 계획

매일 저녁 확인:
- [ ] 커밋 메시지 Conventional Commits 준수
- [ ] 테스트 통과 (./gradlew test)
- [ ] Lint 통과 (./gradlew lint)
- [ ] PR 생성 또는 WIP 푸시
- [ ] 내일 할 일 정리

---

## 🚀 성공 기준

### 기술적 성공
- [ ] 테스트 커버리지 70% 이상
- [ ] 빌드 경고 0개
- [ ] 크래시율 < 1%
- [ ] 탐지 정확도 > 80%

### 비즈니스 성공
- [ ] 데이콘 제출 완료
- [ ] Play Store 심사 통과
- [ ] 사용자 10명 이상 테스트
- [ ] 긍정 피드백 > 70%

---

**Last Updated**: Day 1
**Next Review**: Day 7 (Week 1 회고)
