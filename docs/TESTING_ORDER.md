# OnGuard 테스트 순서 가이드

테스트는 아래 순서대로 진행하면 됩니다.

---

## 1. 환경 준비

1. **프로젝트 가져오기**
   ```bash
   git clone -b Backend https://github.com/jhparktime/DealGuard.git
   cd DealGuard
   ```
   (이미 클론했다면 `git pull origin Backend`)

2. **Android Studio에서 열기**
   - **File → Open** → `OnGuard` 폴더 선택

3. **Gradle 동기화**
   - **File → Sync Project with Gradle Files** (또는 코끼리 아이콘)
   - "Gradle build finished" 나올 때까지 대기

4. **(선택) LLM 모델**
   - `app/src/main/assets/models/README.md` 참고
   - `gemma3-270m-it-q4_0-web.task` 넣으면 LLM 탐지 사용, 없으면 Rule-based만 동작

---

## 2. 빌드 & 설치

1. **실기기 또는 에뮬레이터 연결**
   - 실기기: USB 디버깅 ON, 개발자 옵션 활성화
   - 에뮬레이터: AVD Manager에서 생성 (API 26 이상, RAM 4GB 권장)

2. **앱 빌드 및 실행**
   - 상단 **Run** 버튼(▶) 또는 **Run → Run 'app'**
   - 또는 터미널: `./gradlew installDebug` (Gradle 사용 가능할 때)

3. **앱이 기기에서 실행되는지 확인**

---

## 3. 권한 설정 (필수)

1. **접근성 서비스**
   - **설정 → 접근성 → 설치된 서비스**
   - **OnGuard** 선택 → **서비스 사용** ON → **허용**

2. **오버레이 권한**
   - **설정 → 앱 → OnGuard**
   - **다른 앱 위에 표시** (또는 **특별한 접근**) → **허용** ON

3. **알림 권한 (Android 13+)**
   - 첫 실행 시 뜨면 **허용**

4. **OnGuard 앱으로 돌아가기**
   - 메인 화면에서 "Protection Active" (초록 점) 나오면 준비 완료

---

## 4. 단위 테스트 (로컬)

1. **Android Studio**
   - 오른쪽 **Gradle** 패널 → **OnGuard → app → Tasks → verification → test** 더블클릭

2. **터미널 (Gradle 사용 가능할 때)**
   ```bash
   ./gradlew test
   ```

3. **결과**
   - `app/build/reports/tests/testDebugUnitTest/index.html` 에서 리포트 확인

---

## 5. 실제 스캠 탐지 테스트

1. **지원 앱 중 하나 실행**
   - 예: 카카오톡, 당근마켓, SMS 등

2. **테스트 메시지 받기/보내기**
   - 다른 기기나 PC에서 아래처럼 메시지 전송:

   **높은 위험 (빨간 배너 예시)**
   ```
   급전 필요하시면 연락주세요.
   계좌번호 123-456-789012로 입금해주세요.
   인증번호 알려주시면 바로 처리해드립니다.
   ```

   **중간 위험 (주황 배너 예시)**
   ```
   당첨되셨습니다. 환급금 받으려면 아래 링크 클릭하세요.
   http://bit.ly/fake-link
   ```

   **당근/거래 예시**
   ```
   택배비 먼저 입금해주시면 바로 보내드릴게요.
   계좌번호: 1234-5678-9012
   ```

3. **확인할 것**
   - 화면 **상단**에 경고 배너가 뜨는지
   - 위험도에 따라 색이 빨강/주황/노랑으로 바뀌는지
   - 15초 후 자동으로 사라지거나 **무시** 버튼으로 닫히는지
   - OnGuard 앱 메인 화면 **Recent Alerts**에 알림이 쌓이는지

---

## 6. Logcat으로 동작 확인

1. **Android Studio 하단 Logcat** 열기

2. **필터**
   - Tag: `ScamDetectionService` 또는 `OverlayService` 또는 `KeywordMatcher`

3. **정상일 때 예시 로그**
   ```
   I/ScamDetectionService: Accessibility Service Connected
   D/ScamDetectionService: Extracted text (150 chars): ...
   D/ScamDetectionService: Analysis result - isScam: true, confidence: 0.85
   W/ScamDetectionService: SCAM DETECTED! ...
   I/OverlayService: Showing overlay: confidence=0.85, sourceApp=com.kakao.talk
   D/OverlayService: Overlay view added successfully
   ```

---

## 7. 문제가 있을 때

| 현상 | 확인 순서 |
|------|-----------|
| **오버레이 안 뜸** | 오버레이 권한 ON인지, Logcat에 OverlayService 에러 있는지 |
| **텍스트를 안 잡음** | 접근성 서비스 ON인지, 대상 앱이 지원 목록인지, Logcat에 `onAccessibilityEvent` 로그 있는지 |
| **스캠인데 안 잡힘** | 메시지가 20자 이상인지, 키워드(급전/계좌번호/입금 등) 포함인지, confidence 0.5 이상인지 |
| **빌드/IDE 오류** | **File → Sync Project with Gradle Files** → **File → Invalidate Caches → Invalidate and Restart** |

---

## 8. ADB로 빠르게 확인 (선택)

```bash
# 앱 로그만 보기
adb logcat | grep -E "ScamDetection|Overlay|KeywordMatcher"

# 접근성 서비스 활성 여부
adb shell settings get secure enabled_accessibility_services

# 오버레이 권한 여부
adb shell appops get com.onguard SYSTEM_ALERT_WINDOW
```

---

**요약 순서:** 1 환경 준비 → 2 빌드/설치 → 3 권한 설정 → 4 단위 테스트 → 5 실제 메시지로 스캠 탐지 → 6 Logcat 확인 → 7 문제 시 체크리스트 참고

자세한 시나리오·체크리스트는 [TESTING_GUIDE.md](TESTING_GUIDE.md) 참고.
