# OnGuard - 피싱/스캠 탐지 오버레이 앱

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0+-green.svg)](https://developer.android.com)
[![LLM](https://img.shields.io/badge/LLM-llama.cpp_+_Qwen_1.5B-orange.svg)](https://github.com/ggerganov/llama.cpp)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

플랫폼에 구애받지 않는 실시간 스캠 탐지 안드로이드 앱

**데이콘 경진대회 출품작** - 경찰청 후원, 데이터유니버스 주최

---

## 주요 기능

- **플랫폼 무관 모니터링**: 18개 이상의 메신저/거래 앱 지원
  - 메신저: 카카오톡, 텔레그램, 왓츠앱, 페이스북 메신저, 인스타그램, 라인, 디스코드 등
  - SMS/MMS: Google Messages, Samsung Messages, 기본 메시지 앱
  - 거래 플랫폼: 당근마켓
- **하이브리드 AI 탐지**: Rule-based + On-device LLM (llama.cpp + Qwen 1.5B) 결합
  - 1차 필터: 키워드 매칭 + URL 분석 (빠른 탐지)
  - 2차 분석: sLLM 문맥 분석 + 위험 이유 설명 생성
- **스캠 유형별 탐지**:
  - 투자 사기: 고수익 보장, 원금 보장, 코인/주식 리딩방
  - 중고거래 사기: 선입금 요구, 안전결제 우회, 타 플랫폼 유도
- **즉시 경고 오버레이**: 위험 감지 시 화면 상단에 상세 경고 표시
  - 위험도 퍼센트
  - AI 생성 경고 메시지
  - 위험 요소 목록
  - 의심 문구 하이라이트
- **프라이버시 우선**: 모든 분석은 온디바이스에서 수행 (서버 전송 없음)
- **사기 DB 조회**: 더치트 API, KISA 피싱사이트 DB 연동

---

## 차별점

| 기존 앱 | OnGuard |
|--------|-----------|
| 특정 앱에만 동작 | **모든 메신저 지원** |
| 서버로 데이터 전송 | **온디바이스 LLM 처리** |
| 단순 키워드 매칭 | **AI 문맥 분석 + 이유 설명** |
| 사후 신고 | **실시간 경고** |
| 느린 반응 속도 | **100ms 이하 지연 (Rule-based)** |

---

## AI 탐지 아키텍처

```
채팅 메시지 수신
      │
      ▼
┌─────────────────────────────┐
│  Rule-based 1차 필터        │
│  - KeywordMatcher (0.1ms)   │
│  - UrlAnalyzer              │
└─────────────────────────────┘
      │
      ├── 명확한 위험 (>70%) ──────► 즉시 경고
      │
      ▼ 애매한 경우 (30~70%)
┌─────────────────────────────┐
│  Qwen 1.5B LLM 분석 (llama.cpp) │
│  - 문맥 기반 탐지           │
│  - 위험 이유 설명 생성      │
│  - 스캠 유형 분류           │
└─────────────────────────────┘
      │
      ▼
┌─────────────────────────────┐
│  결과 결합 (가중 평균)      │
│  - Rule: 40%, LLM: 60%      │
└─────────────────────────────┘
      │
      ▼
    Overlay 경고 표시
```

---

## 지원 앱 목록

### 메신저 앱 (9개)
- 카카오톡 (com.kakao.talk)
- 텔레그램 (org.telegram.messenger)
- 왓츠앱 (com.whatsapp)
- 페이스북 메신저 (com.facebook.orca)
- 인스타그램 (com.instagram.android)
- 라인 (jp.naver.line.android)
- 위챗 (com.tencent.mm)
- 디스코드 (com.discord)
- 스냅챗 (com.snapchat.android)

### SMS/MMS 앱 (3개)
- Google Messages (com.google.android.apps.messaging)
- Samsung Messages (com.samsung.android.messaging)
- 기본 메시지 앱 (com.android.mms)

### 거래 플랫폼 (2개)
- 당근마켓 (kr.co.daangn)
- 네이버 (com.nhn.android.search)

### 기타 (4개)
- 바이버 (com.viber.voip)
- 킥 (kik.android)
- 스카이프 (com.skype.raider)

**총 18개 앱 지원** - 지속적으로 추가 예정

---

## 기술 스택

```
Language:       Kotlin 1.9+
Min SDK:        26 (Android 8.0)
Target SDK:     34 (Android 14)
Architecture:   MVVM + Clean Architecture
DI:             Hilt
Async:          Kotlin Coroutines + Flow
UI:             Jetpack Compose + XML (Overlay)
On-device LLM:  llama.cpp (java-llama.cpp) + Qwen 2.5 1.5B GGUF
ML:             TensorFlow Lite
Network:        Retrofit2 + OkHttp
Local DB:       Room
Build:          Gradle Kotlin DSL
```

---

## 시작하기

### 1. 환경 요구사항

- Android Studio Hedgehog (2023.1.1) 이상
- JDK 17
- Android SDK 34

### 2. 프로젝트 클론

```bash
git clone --recurse-submodules https://github.com/jhparktime/OnGuard.git
cd OnGuard
git checkout Ai  # AI 브랜치
```

**다른 PC에서 빌드할 때** (java-llama.cpp Android NDK 수정 적용):

서브모듈은 원격에 수정이 올라가지 않으므로, clone 후 **한 번만** 아래 패치를 적용하세요.

```bash
# macOS / Linux
./scripts/apply-java-llama-android-patch.sh

# Windows (Git Bash 또는 cmd)
scripts\apply-java-llama-android-patch.bat
```

이미 서브모듈을 받았다면: `git submodule update --init` 후 위 스크립트 실행.

### 3. API 키 설정

`local.properties.template`을 복사하여 `local.properties` 생성:

```properties
sdk.dir=/path/to/your/Android/sdk
THECHEAT_API_KEY=your_api_key_here
```

### 4. LLM 모델 다운로드 (선택사항)

LLM 기능을 사용하려면 Qwen GGUF 모델이 필요합니다:

1. [Hugging Face](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) 등에서 Qwen 2.5 1.5B GGUF(q4_k_m 권장) 다운로드
2. 파일명을 **`model.gguf`**로 변경
3. `app/src/main/assets/models/` 폴더에 복사

> **Note**: 모델 없이도 Rule-based 탐지는 정상 작동합니다.

### 5. 빌드 & 실행

```bash
# Android Studio에서 열기 권장
# 또는 CLI:
./gradlew assembleDebug
./gradlew installDebug
```

---

## 프로젝트 구조

```
app/src/main/java/com/onguard/
├── di/                     # Hilt DI modules
├── data/                   # Data Layer (Repository, API, DB)
│   ├── local/              # Room Database
│   ├── remote/             # Retrofit API
│   └── repository/         # Repository Implementations
├── domain/                 # Domain Layer (Models, UseCases)
│   ├── model/              # ScamAnalysis, ScamAlert, ScamType
│   ├── repository/         # Repository Interfaces
│   └── usecase/            # Business Logic
├── presentation/           # Presentation Layer (UI, ViewModel)
├── service/                # Android Services
│   ├── ScamDetectionAccessibilityService.kt  # 채팅 모니터링
│   └── OverlayService.kt                     # 경고 UI 표시
├── detector/               # Scam Detection Engine
│   ├── KeywordMatcher.kt   # Rule-based 키워드 매칭
│   ├── UrlAnalyzer.kt      # URL 분석
│   ├── LLMScamDetector.kt  # LLM 탐지기 (llama.cpp + Qwen)
│   └── HybridScamDetector.kt  # 하이브리드 탐지 통합
└── util/                   # Utilities
```

---

## 개발 로드맵

자세한 개발 계획은 [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) 참조

**현재 진행 상황**: AI 브랜치 개발 완료

- [x] 프로젝트 초기 설정
- [x] Clean Architecture 구조 생성
- [x] 기본 도메인 모델 정의
- [x] 접근성 서비스 구현 - 18개 앱 지원
- [x] Rule-based 탐지 엔진 (KeywordMatcher, UrlAnalyzer)
- [x] 더치트 API 연동
- [x] Room 데이터베이스 구현
- [x] Overlay 경고 시스템
- [x] **LLM 통합 (llama.cpp + Qwen 1.5B)**
  - [x] java-llama.cpp + Qwen 2.5 GGUF 연동
  - [x] 투자/중고거래 스캠 탐지 프롬프트
  - [x] AI 경고 메시지 + 이유 설명 생성
  - [x] HybridScamDetector 통합
  - [x] Overlay UI 개선

---

## 테스트

### 단위 테스트

```bash
./gradlew test
```

### 테스트 커버리지

```bash
./gradlew testDebugUnitTest jacocoTestReport
```

**목표 커버리지**: 전체 70%, detector 패키지 90%

### 통합 테스트

```bash
./gradlew connectedAndroidTest
```

---

## 보안 & 프라이버시

- **AccessibilityService 데이터는 절대 외부 전송 금지**
- **모든 AI 분석은 온디바이스에서 수행**
- **사용자 동의 없이 모니터링 시작 금지**
- **Google Play Prominent Disclosure 준수**
- **API 키 하드코딩 금지 (BuildConfig 사용)**

자세한 내용은 [CLAUDE.md](CLAUDE.md#-security-requirements) 참조

---

## 라이선스

이 프로젝트는 데이콘 경진대회 출품작입니다.

Qwen 모델 사용 시 해당 모델의 라이선스 준수 필요

---

## 개발자

- **Zaeewang** - Initial work
- **parkjaehyun** - AI/LLM Integration

---

## 감사의 말

- 경찰청 & 데이터유니버스 - 경진대회 주최
- 더치트 - 스캠 DB API 제공
- KISA - 피싱사이트 공공 DB 제공
- llama.cpp / Qwen - 온디바이스 LLM 인프라

---

## 문의

프로젝트 관련 문의사항은 Issues 탭을 이용해주세요.
