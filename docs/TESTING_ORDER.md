# OnGuard 테스트 순서 가이드

테스트는 아래 순서대로 진행하면 됩니다.

---

## 1. 환경 준비

1. **프로젝트 가져오기**
   ```bash
   git clone -b Ai --recurse-submodules https://github.com/jhparktime/DealGuard.git OnGuard
   cd OnGuard
   ```
   - `--recurse-submodules`: 서브모듈(java-llama.cpp)을 clone 시 함께 받기 (이걸 쓰면 ① 서브모듈 초기화 생략 가능)
   - 브랜치가 Backend면 `-b Backend` 로 바꾸기. 이미 클론했다면 `git pull origin Ai` 후 `git submodule update --init`

2. **빌드·테스트 자동화 (클론 직후)**  
   **③ local.properties**만 준비한 뒤, 아래 한 번만 실행하면 됩니다. 서브모듈 초기화와 NDK 패치는 **첫 빌드/테스트 시 Gradle이 자동으로** 실행합니다.
   ```bash
   # local.properties 없으면: local.properties.template 복사 후 sdk.dir 설정
   ./gradlew build
   # 또는 단위 테스트만
   ./gradlew test
   ```
   - 첫 실행 시: `ensureJavaLlamaSubmodule` → `applyJavaLlamaPatch` 가 자동 실행된 뒤 빌드/테스트가 진행됩니다.
   - 수동으로 하고 싶으면 아래 ①·②를 먼저 실행해도 됩니다.

3. **clone 이후 할 일** (수동으로 할 때)

   | 순서 | 할 일 | 필수 |
   |------|--------|------|
   | ① | **서브모듈 초기화** — `git submodule update --init` (java-llama.cpp). *`./gradlew build` / `./gradlew test` 시 자동 실행됨* | ✅ (자동) |
   | ② | **Android NDK 패치 적용** — `./scripts/apply-java-llama-android-patch.sh` (mac/Linux) 또는 `scripts\apply-java-llama-android-patch.bat` (Windows). *`./gradlew build` / `./gradlew test` 시 자동 실행됨* | ✅ (자동) |
   | ③ | **local.properties 생성** — `local.properties.template`을 복사해 `local.properties` 만들고, `sdk.dir`(Android SDK 경로)와 `THECHEAT_API_KEY`(선택) 입력 | ✅ (sdk.dir 필수) |
   | ④ | **(선택) LLM 모델** — Qwen GGUF를 `app/src/main/assets/models/qwen2.5-1.5b-instruct-q4_k_m.gguf` 로 넣으면 LLM 탐지 사용, 없으면 Rule-based만 동작 | 선택 |

   **③ Windows에서 local.properties 만드는 방법**

   1. **파일 복사**
      - 프로젝트 **루트**(OnGuard 폴더, `build.gradle.kts`가 있는 곳)로 이동
      - `local.properties.template` 파일을 **복사**한 뒤 이름을 **`local.properties`** 로 변경  
      - 탐색기: `local.properties.template` 우클릭 → 복사 → 붙여넣기 → 새 파일 이름을 `local.properties`로 변경  
      - 또는 CMD: `copy local.properties.template local.properties`
   2. **sdk.dir 수정 (필수)**
      - `local.properties`를 메모장/VS Code 등으로 열기
      - `sdk.dir=/path/to/your/Android/sdk` 줄을 **본인 PC의 Android SDK 경로**로 바꾸기  
      - **Windows는 반드시 슬래시(`/`)만 사용** (백슬래시 쓰면 "경로 잘못됨" 나올 수 있음):
        - ✅ `sdk.dir=C:/Users/사용자이름/AppData/Local/Android/Sdk`
        - ✅ 탐색기에서 복사한 `C:\Users\...` 경로는 **모든 `\` 를 `/` 로 바꿔서** 넣기
      - 백슬래시를 꼭 쓰려면 **`\` 하나당 `\\` 두 개**로 적기:
        - 예: `sdk.dir=C:\\Users\\사용자이름\\AppData\\Local\\Android\\Sdk`
      - SDK 경로 모를 때: Android Studio → **File → Settings** → **Languages & Frameworks → Android SDK** → 상단 **Android SDK location** 에 나온 경로 복사한 뒤, `\` 를 `/` 로 바꿔서 넣기
   3. **THECHEAT_API_KEY (선택)**
      - 더치트 API를 쓸 때만: `THECHEAT_API_KEY=your_api_key_here` 를 발급받은 키로 변경
      - 안 쓰면 그대로 두어도 됨
   4. **저장 후** 프로젝트 루트에 `local.properties` 파일이 있으면 됨 (Git에는 올리지 않음)

   **서브모듈 오류 나올 때** (app/java-llama.cpp 비어 있음, "not a git repository" 등):
   1. 프로젝트 **루트**(OnGuard 폴더)에서 CMD 또는 PowerShell:
      ```bash
      git submodule update --init
      ```
   2. **"not our ref" / "did not contain ... Direct fetching of that commit failed"** 가 나오면:  
      예전에 부모 저장소가 가리키던 서브모듈 커밋이 원격에 없을 때입니다. **최신 부모 저장소**를 받은 뒤 서브모듈을 다시 맞추면 됩니다.
      ```bash
      git pull origin Ai
      git submodule sync
      git submodule update --init
      ```
      (Backend 브랜치면 `git pull origin Backend` 로 바꾸기.)
   3. 그래도 실패하면: `app/java-llama.cpp` 폴더를 **삭제**한 뒤 다시:
      ```bash
      git submodule update --init
      ```
   4. 처음 clone 할 때 `--recurse-submodules` 옵션을 쓰면 서브모듈을 한 번에 받아서 이 오류를 줄일 수 있음.

   ```bash
   # ① 서브모듈 (clone 시 --recurse-submodules 안 썼을 때만; 또는 그냥 ./gradlew build 실행해도 자동 처리)
   git submodule update --init

   # ② 패치 (mac/Linux) — 또는 ./gradlew build 시 자동 적용
   ./scripts/apply-java-llama-android-patch.sh
   # Windows: scripts\apply-java-llama-android-patch.bat
   ```

4. **Android Studio에서 열기**
   - **File → Open** → **OnGuard** 폴더(루트) 선택 (`app` 폴더만 열지 말 것)

5. **Gradle 동기화**
   - **File → Sync Project with Gradle Files** (또는 코끼리 아이콘)
   - "Gradle build finished" 나올 때까지 대기

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
| **서브모듈 오류** (app/java-llama.cpp 비어 있음 / not a git repository) | 프로젝트 **루트**에서 `git submodule update --init` 실행. 실패 시: `app/java-llama.cpp` 폴더 삭제 후 다시 `git submodule update --init`. clone 시 `--recurse-submodules` 옵션 쓰면 서브모듈을 함께 받음. |
| **"not our ref" / "Direct fetching of that commit failed"** (서브모듈 커밋을 원격에서 못 찾을 때) | 프로젝트 **루트**에서 `git pull origin Ai` → `git submodule sync` → `git submodule update --init`. (Backend 브랜치면 `git pull origin Backend`.) |
| **경로가 잘못되었다고 나옴** (local.properties) | Windows: `sdk.dir`에 **백슬래시(`\`) 대신 슬래시(`/`)만** 사용. 예: `sdk.dir=C:/Users/이름/AppData/Local/Android/Sdk` |
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
