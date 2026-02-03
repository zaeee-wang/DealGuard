# Gemma 3 모델 다운로드 안내

## 필요한 파일

| 파일명 | 크기 | 용도 |
|--------|------|------|
| `gemma3-270m-it-q8.task` | 304MB | Android 모바일용 LLM 스캠 탐지 |

**중요:** `-web.task` 접미사가 있는 파일은 웹용이므로 Android에서는 사용할 수 없습니다.  
모바일용은 `-web` 접미사가 **없는** `.task` 파일을 사용해야 합니다.

## 다운로드 방법

### Hugging Face에서 다운로드 (권장)

1. [Hugging Face Gemma 3 270M](https://huggingface.co/litert-community/gemma-3-270m-it) 접속
2. 로그인 후 라이선스 동의
3. **`gemma3-270m-it-q8.task`** 파일 다운로드 (모바일용, `-web` 접미사 없음)
4. 이 폴더 (`app/src/main/assets/models/`)에 복사

### 파일 배치 확인

```
app/src/main/assets/models/
├── README.md                          # 이 파일
└── gemma3-270m-it-q8.task            # Gemma 모델 (모바일용, 직접 다운로드)
```

## 저사양 기기 (S10e, 8GB RAM 등)

앱은 **largeHeap** 요청, LLM 초기화 지연, **MAX_TOKENS/TOP_K 축소**로 저사양 기기에서도 LLM 로드를 시도합니다.  
그래도 메모리 부족으로 크래시되면 LLM은 비활성화되고 Rule-based 탐지만 사용됩니다 (앱은 정상 동작).

## 모델 없이 실행하기

**모델 파일이 없어도 앱은 정상 작동합니다!**

- LLM 기능은 자동으로 비활성화됨
- Rule-based 탐지만 사용됨 (KeywordMatcher + UrlAnalyzer)
- 앱 시작 시 로그에 경고만 표시됨

## 왜 Git에 포함되지 않나요?

| 이유 | 설명 |
|------|------|
| 파일 크기 | 304MB - GitHub 100MB 제한 초과 |
| Clone 속도 | 저장소 clone 시간 대폭 증가 |
| 라이선스 | Google Gemma 약관에 따라 개별 동의 필요 |

## 참고 링크

- [Google Gemma License](https://ai.google.dev/gemma/terms)
- [MediaPipe LLM Inference](https://developers.google.com/mediapipe/solutions/genai/llm_inference)
