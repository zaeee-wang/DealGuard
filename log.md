# OnGuard 변경 로그 (Changelog)

버그 수정, 기능 추가, 개선 사항을 기록합니다.

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
*Last Updated: 2026-01-30*
