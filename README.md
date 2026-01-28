# DealGuard - 피싱/스캠 탐지 오버레이 앱

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0+-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

플랫폼에 구애받지 않는 실시간 스캠 탐지 안드로이드 앱

**데이콘 경진대회 출품작** - 경찰청 후원, 데이터유니버스 주최

---

## 📱 주요 기능

- ✅ **플랫폼 무관 모니터링**: 18개 이상의 메신저/거래 앱 지원
  - 메신저: 카카오톡, 텔레그램, 왓츠앱, 페이스북 메신저, 인스타그램, 라인, 디스코드 등
  - SMS/MMS: Google Messages, Samsung Messages, 기본 메시지 앱
  - 거래 플랫폼: 당근마켓
- 🛡️ **실시간 스캠 탐지**: Rule-based + On-device AI 하이브리드 분석
- 🚨 **즉시 경고 오버레이**: 위험 감지 시 화면 상단 배너 표시
- 🔐 **프라이버시 우선**: 모든 분석은 온디바이스에서 수행 (서버 전송 없음)
- 📊 **사기 DB 조회**: 더치트 API, KISA 피싱사이트 DB 연동

---

## 🎯 차별점

| 기존 앱 | DealGuard |
|--------|-----------|
| 특정 앱에만 동작 | **모든 메신저 지원** |
| 서버로 데이터 전송 | **온디바이스 처리** |
| 사후 신고 | **실시간 경고** |
| 느린 반응 속도 | **100ms 이하 지연** |

---

## 📱 지원 앱 목록

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

## 🔧 기술 스택

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

## 🚀 시작하기

### 1. 환경 요구사항

- Android Studio Hedgehog (2023.1.1) 이상
- JDK 17
- Android SDK 34

### 2. 프로젝트 클론

```bash
git clone https://github.com/your-username/DealGuard.git
cd DealGuard
```

### 3. API 키 설정

`local.properties.template`을 복사하여 `local.properties` 생성:

```properties
sdk.dir=/path/to/your/Android/sdk
THECHEAT_API_KEY=your_api_key_here
```

### 4. 빌드 & 실행

```bash
# Debug 빌드
./gradlew assembleDebug

# 기기에 설치
./gradlew installDebug

# 테스트 실행
./gradlew test
```

---

## 📋 개발 로드맵

자세한 개발 계획은 [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) 참조

**현재 진행 상황**: Week 1 Day 2 ✅

- [x] 프로젝트 초기 설정
- [x] Clean Architecture 구조 생성
- [x] 기본 도메인 모델 정의
- [x] 접근성 서비스 구현 (Day 2) - 18개 앱 지원
- [ ] Rule-based 탐지 엔진 (Day 3)

---

## 🏗️ 프로젝트 구조

```
app/src/main/java/com/dealguard/
├── di/                     # Hilt DI modules
├── data/                   # Data Layer (Repository, API, DB)
├── domain/                 # Domain Layer (Models, UseCases)
├── presentation/           # Presentation Layer (UI, ViewModel)
├── service/                # Android Services (Accessibility, Overlay)
├── detector/               # Scam Detection Engine
└── util/                   # Utilities
```

---

## 🧪 테스트

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

## 🔐 보안 & 프라이버시

- ✅ **AccessibilityService 데이터는 절대 외부 전송 금지**
- ✅ **사용자 동의 없이 모니터링 시작 금지**
- ✅ **Google Play Prominent Disclosure 준수**
- ✅ **API 키 하드코딩 금지 (BuildConfig 사용)**

자세한 내용은 [CLAUDE.md](CLAUDE.md#-security-requirements) 참조

---

## 📄 라이선스

이 프로젝트는 데이콘 경진대회 출품작입니다.

---

## 👤 개발자

- **Zaeewang** - Initial work

---

## 🙏 감사의 말

- 경찰청 & 데이터유니버스 - 경진대회 주최
- 더치트 - 스캠 DB API 제공
- KISA - 피싱사이트 공공 DB 제공

---

## 📞 문의

프로젝트 관련 문의사항은 Issues 탭을 이용해주세요.
