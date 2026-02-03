package com.onguard.detector

import android.content.Context
import android.util.Log
import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 기반 스캠 탐지기.
 *
 * MediaPipe LLM Inference API와 Gemma 3 270M(4bit 양자화) 모델을 사용하여
 * 채팅 메시지를 분석하고, 스캠 여부·신뢰도·경고 메시지·의심 문구를 생성한다.
 * 모델 파일이 없거나 초기화 실패 시 [analyze]는 null을 반환하며, Rule-based만 사용된다.
 *
 * @param context [ApplicationContext] 앱 컨텍스트 (assets/filesDir 접근용)
 */
@Singleton
class LLMScamDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LLMScamDetector"
        /** assets 내 모델 상대 경로 (MediaPipe .task 형식) */
        private const val MODEL_PATH = "models/gemma3-270m-it-q4_0-web.task"
        private const val MAX_TOKENS = 256
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
    }

    /**
     * Rule 기반·URL 분석 결과 등, LLM에 전달할 추가 컨텍스트.
     *
     * DB(KISA 피싱 URL 등)를 이미 활용한 UrlAnalyzer/KeywordMatcher의 결과를
     * LLM 프롬프트에 함께 담기 위해 사용한다.
     */
    data class LlmContext(
        /** 규칙 기반 신뢰도 (0.0~1.0) */
        val ruleConfidence: Float? = null,
        /** 규칙 기반 탐지 사유 목록 */
        val ruleReasons: List<String> = emptyList(),
        /** 규칙 기반에서 탐지된 키워드 목록 */
        val detectedKeywords: List<String> = emptyList(),
        /** 텍스트에서 추출된 전체 URL 목록 */
        val urls: List<String> = emptyList(),
        /** UrlAnalyzer 기준으로 위험하다고 간주된 URL 목록 */
        val suspiciousUrls: List<String> = emptyList(),
        /** URL 관련 위험 사유 (KISA DB 매치, 무료 도메인 등) */
        val urlReasons: List<String> = emptyList()
    )

    private var llmInference: LlmInference? = null
    private val gson = Gson()
    private var isInitialized = false

    /**
     * LLM 모델을 초기화한다.
     *
     * assets에서 [MODEL_PATH]로 모델을 복사한 뒤 MediaPipe로 로드한다.
     * 이미 초기화된 경우 즉시 true를 반환한다.
     *
     * @return 성공 시 true, 모델 없음/예외 시 false
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        try {
            val modelFile = File(context.filesDir, MODEL_PATH)

            // assets에서 모델 파일 확인
            if (!modelFile.exists()) {
                // assets에서 복사 시도
                val assetPath = MODEL_PATH
                try {
                    context.assets.open(assetPath).use { input ->
                        modelFile.parentFile?.mkdirs()
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Model copied from assets to: ${modelFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Model file not found in assets: $assetPath")
                    Log.w(TAG, "LLM detection will be disabled. Please add Gemma model to assets/models/")
                    return@withContext false
                }
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setTemperature(TEMPERATURE)
                .setTopK(TOP_K)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Log.d(TAG, "LLM initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LLM", e)
            false
        }
    }

    /**
     * LLM 모델 사용 가능 여부를 반환한다.
     *
     * @return 초기화 완료 및 인스턴스 존재 시 true
     */
    fun isAvailable(): Boolean = isInitialized && llmInference != null

    /**
     * 주어진 텍스트를 LLM으로 분석하여 스캠 여부와 상세 결과를 반환한다.
     *
     * @param text 분석할 채팅 메시지
     * @param context Rule/URL 기반 1차 분석 결과 등 추가 컨텍스트 (선택)
     * @return [ScamAnalysis] 분석 결과. 모델 미사용/빈 응답/파싱 실패 시 null
     */
    suspend fun analyze(text: String, context: LlmContext? = null): ScamAnalysis? = withContext(Dispatchers.Default) {
        if (!isAvailable()) {
            Log.w(TAG, "LLM not available, skipping analysis")
            return@withContext null
        }

        try {
            val prompt = buildPrompt(text, context)
            val response = llmInference?.generateResponse(prompt)

            if (response.isNullOrBlank()) {
                Log.w(TAG, "Empty response from LLM")
                return@withContext null
            }

            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error during LLM analysis", e)
            null
        }
    }

    /**
     * 스캠 탐지용 시스템 프롬프트와 사용자 메시지를 조합한 프롬프트를 생성한다.
     *
     * @param text 분석 대상 채팅 메시지
     * @param context Rule 기반·URL 분석 등 추가 컨텍스트
     * @return JSON만 출력하도록 지시한 프롬프트 문자열
     */
    private fun buildPrompt(text: String, context: LlmContext?): String {
        val contextBlock = buildString {
            if (context == null) return@buildString

            if (context.ruleConfidence != null || context.ruleReasons.isNotEmpty() || context.detectedKeywords.isNotEmpty()) {
                appendLine("[Rule-based 1차 분석 요약]")
                context.ruleConfidence?.let {
                    appendLine("- rule_confidence: $it")
                }
                if (context.detectedKeywords.isNotEmpty()) {
                    appendLine("- detected_keywords: ${context.detectedKeywords.joinToString()}")
                }
                if (context.ruleReasons.isNotEmpty()) {
                    appendLine("- rule_reasons:")
                    context.ruleReasons.forEach { reason ->
                        appendLine("  - $reason")
                    }
                }
                appendLine()
            }

            if (context.urls.isNotEmpty() || context.suspiciousUrls.isNotEmpty() || context.urlReasons.isNotEmpty()) {
                appendLine("[URL/DB 기반 분석 요약]")
                if (context.urls.isNotEmpty()) {
                    appendLine("- urls: ${context.urls.joinToString()}")
                }
                if (context.suspiciousUrls.isNotEmpty()) {
                    appendLine("- suspicious_urls: ${context.suspiciousUrls.joinToString()}")
                }
                if (context.urlReasons.isNotEmpty()) {
                    appendLine("- url_reasons:")
                    context.urlReasons.forEach { reason ->
                        appendLine("  - $reason")
                    }
                }
            }
        }.trimEnd()

        return """
당신은 사기 탐지 전문가입니다. 다음 메시지를 분석하고 JSON 형식으로만 응답하세요.

[배경 정보]
- 먼저 규칙 기반 분석기와 URL 분석기가 1차로 메시지를 평가했습니다.
- 아래 [Rule-based 1차 분석 요약]과 [URL/DB 기반 분석 요약]을 참고하여 최종 판단을 내려주세요.
- KISA 피싱사이트 DB 등록 URL 등은 매우 강한 스캠 신호입니다.

[Rule-based 1차 분석 요약과 URL/DB 기반 분석 요약은 없을 수도 있습니다]
${if (contextBlock.isNotBlank()) contextBlock else "(제공된 사전 분석 정보 없음)"}

[탐지 대상]
1. 투자 사기: 고수익 보장, 원금 보장, 긴급 투자 권유, 비공개 정보 제공, 코인/주식 리딩방
2. 중고거래 사기: 선입금 요구, 안전결제 우회, 급매 압박, 타 플랫폼 유도, 직거래 회피, 허위 매물

[메시지]
$text

[응답 형식 - JSON만 출력하세요]
{
  "isScam": true 또는 false,
  "confidence": 0.0부터 1.0 사이 숫자,
  "scamType": "투자사기" 또는 "중고거래사기" 또는 "피싱" 또는 "정상",
  "warningMessage": "사용자에게 보여줄 경고 메시지 (한국어, 2문장 이내)",
  "reasons": ["위험 요소 1", "위험 요소 2"],
  "suspiciousParts": ["의심되는 문구 인용"]
}
""".trimIndent()
    }

    /**
     * LLM 응답 문자열에서 JSON 블록을 추출해 [ScamAnalysis]로 파싱한다.
     *
     * @param response LLM 원시 응답 (앞뒤 일반 텍스트 허용)
     * @return 파싱 성공 시 [ScamAnalysis], 실패 시 null
     */
    private fun parseResponse(response: String): ScamAnalysis? {
        return try {
            // JSON 부분만 추출 (LLM이 추가 텍스트를 생성할 수 있음)
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')

            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                Log.w(TAG, "No valid JSON found in response: $response")
                return null
            }

            val jsonString = response.substring(jsonStart, jsonEnd + 1)
            val llmResult = gson.fromJson(jsonString, LLMResponse::class.java)

            ScamAnalysis(
                isScam = llmResult.isScam,
                confidence = llmResult.confidence.coerceIn(0f, 1f),
                reasons = llmResult.reasons,
                detectedKeywords = emptyList(),
                detectionMethod = DetectionMethod.LLM,
                scamType = parseScamType(llmResult.scamType),
                warningMessage = llmResult.warningMessage,
                suspiciousParts = llmResult.suspiciousParts
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response: $response", e)
            null
        }
    }

    /**
     * LLM이 반환한 스캠 유형 문자열을 [ScamType] enum으로 변환한다.
     *
     * @param typeString "투자사기", "중고거래사기", "정상" 등 한글 문자열
     * @return 대응되는 [ScamType]
     */
    private fun parseScamType(typeString: String): ScamType {
        return when {
            typeString.contains("투자") -> ScamType.INVESTMENT
            typeString.contains("중고") || typeString.contains("거래") -> ScamType.USED_TRADE
            typeString.contains("피싱") -> ScamType.PHISHING
            typeString.contains("사칭") -> ScamType.IMPERSONATION
            typeString.contains("로맨스") -> ScamType.ROMANCE
            typeString.contains("대출") -> ScamType.LOAN
            typeString.contains("정상") -> ScamType.SAFE
            else -> ScamType.UNKNOWN
        }
    }

    /**
     * LLM 인스턴스를 해제하고 리소스를 반환한다.
     *
     * 앱 종료 또는 탐지기 교체 시 호출 권장.
     */
    fun close() {
        llmInference?.close()
        llmInference = null
        isInitialized = false
    }

    /**
     * LLM 응답 JSON 파싱용 내부 DTO.
     *
     * @property isScam 스캠 여부
     * @property confidence 신뢰도 0~1
     * @property scamType 한글 유형 문자열
     * @property warningMessage 사용자용 경고 문구
     * @property reasons 위험 요소 목록
     * @property suspiciousParts 의심 문구 인용 목록
     */
    private data class LLMResponse(
        @SerializedName("isScam")
        val isScam: Boolean = false,

        @SerializedName("confidence")
        val confidence: Float = 0f,

        @SerializedName("scamType")
        val scamType: String = "정상",

        @SerializedName("warningMessage")
        val warningMessage: String = "",

        @SerializedName("reasons")
        val reasons: List<String> = emptyList(),

        @SerializedName("suspiciousParts")
        val suspiciousParts: List<String> = emptyList()
    )
}
