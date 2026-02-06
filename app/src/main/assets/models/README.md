# LLM 모델 (llama.cpp + Qwen GGUF)

## 필요한 파일

| 파일명 | 용도 |
|--------|------|
| `qwen2.5-1.5b-instruct-q4_k_m.gguf` | Qwen 2.5 (1.5B) GGUF 모델 - 온디바이스 스캠 탐지 |

**중요:** 앱은 **llama.cpp** (java-llama.cpp)로 GGUF 모델을 로드합니다.  
이 폴더에 `qwen2.5-1.5b-instruct-q4_k_m.gguf` 파일을 그대로 두면 됩니다.

## 다운로드 방법

1. [Hugging Face](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) 등에서 Qwen 2.5 1.5B GGUF(q4_k_m 권장) 다운로드
2. 이 폴더 (`app/src/main/assets/models/`)에 **`qwen2.5-1.5b-instruct-q4_k_m.gguf`** 이름 그대로 복사

### 파일 배치 확인

```
app/src/main/assets/models/
├── README.md                           # 이 파일
└── qwen2.5-1.5b-instruct-q4_k_m.gguf  # Qwen GGUF 모델
```

## 모델 없이 실행하기

**모델 파일이 없어도 앱은 정상 작동합니다.**

- LLM 기능은 자동으로 비활성화됨
- Rule-based 탐지만 사용됨 (KeywordMatcher + UrlAnalyzer)

## 참고

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [java-llama.cpp](https://github.com/kherud/java-llama.cpp)
