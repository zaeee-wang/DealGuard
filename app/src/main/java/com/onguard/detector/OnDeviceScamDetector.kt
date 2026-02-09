package com.onguard.detector

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType
import com.onguard.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 온디바이스 Gemma(MediaPipe)를 사용하는 스캠 분석기.
 *
 * - [ScamLlmClient] 구현. 화이트리스트 매칭 시에만 DI에서 주입됨.
 * - .task 모델이 assets 또는 filesDir에 있을 때만 [isAvailable] true.
 */
@Singleton
class OnDeviceScamDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : ScamLlmClient {

    companion object {
        private const val TAG = "OnDeviceGemma"
        private const val ASSET_MODEL_PATH = "models/gemma.task"
        private const val MODEL_FILE_NAME = "gemma_llm.task"
        private const val MAX_TOKENS = 512
        private const val MAX_TOP_K = 40
    }

    private var llmInference: LlmInference? = null
    private var modelPathResolved: String? = null

    override fun isAvailable(): Boolean {
        if (modelPathResolved != null) {
            return File(modelPathResolved!!).exists()
        }
        return copyModelFromAssetsIfNeeded() != null
    }

    /**
     * assets의 .task 모델을 filesDir로 복사하고 경로 반환. 없으면 null.
     */
    private fun copyModelFromAssetsIfNeeded(): String? {
        val outFile = File(context.filesDir, MODEL_FILE_NAME)
        if (outFile.exists()) {
            modelPathResolved = outFile.absolutePath
            return modelPathResolved
        }
        return try {
            context.assets.open(ASSET_MODEL_PATH).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            modelPathResolved = outFile.absolutePath
            DebugLog.debugLog(TAG) { "step=model_copied path=$modelPathResolved" }
            modelPathResolved
        } catch (e: Exception) {
            DebugLog.debugLog(TAG) { "step=model_not_found asset=$ASSET_MODEL_PATH reason=${e.message}" }
            null
        }
    }

    private fun getOrCreateLlmInference(): LlmInference? {
        if (llmInference != null) return llmInference
        val path = copyModelFromAssetsIfNeeded() ?: return null
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(MAX_TOP_K)
                .build()
            LlmInference.createFromOptions(context, options).also {
                llmInference = it
            }
        } catch (e: Exception) {
            DebugLog.warnLog(TAG) { "step=llm_init_failed reason=${e.message}" }
            null
        }
    }

    override suspend fun analyze(request: ScamLlmRequest): ScamAnalysis? = withContext(Dispatchers.IO) {
        val inference = getOrCreateLlmInference() ?: return@withContext null
        
        val prompt = if (request.explanationOnlyMode) {
            buildPromptExplanationOnly(
                recentContextLines = request.recentContext.lines().filter { it.isNotBlank() },
                currentMessage = request.currentMessage,
                ruleReasons = request.ruleReasons,
                detectedKeywords = request.detectedKeywords,
                ruleConfidence = request.ruleConfidence,
                ruleScamType = request.ruleScamType
            )
        } else {
            buildPrompt(
                recentContextLines = request.recentContext.lines().filter { it.isNotBlank() },
                currentMessage = request.currentMessage,
                ruleReasons = request.ruleReasons,
                detectedKeywords = request.detectedKeywords
            )
        }
        
        try {
            val responseText = inference.generateResponse(prompt)
            parseResponse(responseText, request.originalText, request.recentContext)
        } catch (e: Exception) {
            DebugLog.warnLog(TAG) { "step=analyze_failed reason=${e.message}" }
            null
        }
    }

    private fun buildPrompt(
        recentContextLines: List<String>,
        currentMessage: String,
        ruleReasons: List<String>,
        detectedKeywords: List<String>
    ): String {
        val reasonsText = ruleReasons.joinToString("; ").ifEmpty { "없음" }
        val keywordsText = detectedKeywords.joinToString(", ").ifEmpty { "없음" }
        val recentBlock = if (recentContextLines.isEmpty()) "- (최근 대화 없음)"
        else recentContextLines.joinToString("\n") { "- $it" }
        return """
            시스템: 당신은 스캠(사기) 위험을 판단하는 전문가입니다. **친구·가족·지인 간의 일상 대화**와 **실제 사기 메시지**를 정확히 구분해야 합니다.
            
            # 핵심 원칙
            1. **맥락이 일상 대화라면 매우 낮은 점수 (10-25)를 주어 알림을 방지하세요**
               - 친구: "돈 좀 빌려줄래?" → confidence: 10-20 (정상 대화)
               - 가족: "급하게 필요한데 입금 좀" → confidence: 15-25 (일상 부탁)
               
            2. **명확한 사기 신호가 여러 개 있을 때만 높은 점수 (70+)**
               - 낯선 번호 + 긴급 + 금전 + URL → confidence: 75-90
               - DB 등록 전화번호/계좌 → confidence: 85-95
               
            3. **애매하면 중간 (30-50)으로 주되, 경고 문구에서 불확실성 명시**

            [최근 대화]
            $recentBlock

            [현재 메시지]
            $currentMessage

            추가 정보:
            - 룰 기반 탐지 이유: $reasonsText
            - 탐지된 키워드: $keywordsText

            # Confidence 점수 가이드 (0~100 정수)
            
            **10-25 (정상/매우 낮음)**: 일상 대화 맥락
            - 친구/가족 간 돈 빌리는 대화
            - 일상적 거래 약속 ("입금했어", "계좌 알려줘")
            - 금전 관련 단어가 있어도 **대화 흐름이 자연스러움**
            - 예: "돈 좀 빌려줄래?", "급하게 필요해", "계좌번호 알려줘"
            
            **30-50 (주의/애매)**: 불확실한 상황
            - 맥락이 부족해 판단 어려움
            - 금전 요구 + 다소 이상한 표현
            - 최근 대화 없이 갑자기 금전 요구
            
            **60-75 (위험)**: 사기 가능성 높음
            - 긴급성 + 금전 + 의심 URL/전화번호
            - 기관 사칭 표현
            - 투자/대출 유도 + 고수익 보장
            
            **80-95 (매우 위험)**: 명백한 사기
            - Counter Scam 112 DB 등록 번호
            - KISA 악성 URL
            - 경찰청 사기계좌 DB 등록
            - 긴급 + 금전 + 인증정보 요구

            # 출력 형식
            JSON만 출력하세요. 다른 텍스트 포함 금지.
            
            ```json
            {
              "confidence": 15,
              "scamType": "UNKNOWN",
              "warningMessage": "친구 간 대화로 보입니다. 금전 관련 단어가 있지만 일상적인 맥락입니다.",
              "reasons": ["금전 키워드 감지"],
              "suspiciousParts": []
            }
            ```
            
            **warningMessage 작성 규칙:**
            - confidence 10-25: "일상 대화로 보입니다" 또는 "정상 대화 가능성 높습니다"
            - confidence 30-50: "주의가 필요할 수 있습니다" + 이유
            - confidence 60+: "사기 가능성이 있습니다" + 구체적 위험 요소 + 행동 권고
            - 2-3문장, 한국어, 사용자 친화적
            
            **scamType 선택:**
            - INVESTMENT: 투자/코인/주식 사기
            - USED_TRADE: 중고거래/택배 사기
            - PHISHING: 피싱 링크/URL
            - VOICE_PHISHING: 보이스피싱/스미싱 (전화번호 기반)
            - IMPERSONATION: 기관/지인 사칭
            - LOAN: 대출 사기
            - UNKNOWN: 일반 또는 불명확
        """.trimIndent()
    }

    private fun buildPromptExplanationOnly(
        recentContextLines: List<String>,
        currentMessage: String,
        ruleReasons: List<String>,
        detectedKeywords: List<String>,
        ruleConfidence: Float,
        ruleScamType: String
    ): String {
        val reasonsText = ruleReasons.joinToString("; ").ifEmpty { "없음" }
        val keywordsText = detectedKeywords.joinToString(", ").ifEmpty { "없음" }
        val recentBlock = if (recentContextLines.isEmpty()) "- (최근 대화 없음)"
        else recentContextLines.joinToString("\n") { "- $it" }
        val ruleConfidencePercent = (ruleConfidence * 100).toInt()

        return """
            시스템: 당신은 사기 가능성을 사용자에게 알려주는 도우미입니다.
            
            # 중요: 탐지 모드 - RULE_ONLY (설명 전용)
            
            **이번 요청은 점수와 사기 여부가 이미 결정된 상태입니다.**
            
            - **최종 위험도**: $ruleConfidencePercent (0-100)
            - **사기 유형**: $ruleScamType
            - **룰 기반 탐지 이유**: $reasonsText
            
            당신은 이 정보를 바탕으로 **사용자에게 보여줄 경고 문구(warningMessage)만 생성**하세요.
            - confidence와 scamType을 바꾸지 마세요 (응답에는 포함하되, 제공된 값을 그대로 사용).
            - 주요 목표: 자연스럽고 이해하기 쉬운 한국어 경고 문장 작성.
            
            [최근 대화]
            $recentBlock

            [현재 메시지]
            $currentMessage

            추가 정보:
            - 룰 기반 탐지 이유: $reasonsText
            - 탐지된 키워드: $keywordsText

            # 출력 형식
            JSON만 출력하세요. 다른 텍스트 포함 금지.
            
            ```json
            {
              "confidence": $ruleConfidencePercent,
              "scamType": "$ruleScamType",
              "warningMessage": "(여기에 자연스러운 경고 문구 작성)",
              "reasons": ["(룰 탐지 이유를 사용자 친화적으로 설명)", "..."],
              "suspiciousParts": ["(의심스러운 부분)"]
            }
            ```
            
            **warningMessage 작성 규칙:**
            - confidence 60 미만: "주의가 필요할 수 있습니다" + 간단한 이유
            - confidence 60-79: "사기 가능성이 있습니다" + 구체적 위험 요소
            - confidence 80+: "높은 사기 위험이 감지되었습니다" + 명확한 경고 + 행동 권고
            - 2-3문장, 한국어, 사용자 친화적, 전문 용어 지양
            
            **예시 (confidence: 85, scamType: VOICE_PHISHING):**
            ```json
            {
              "confidence": 85,
              "scamType": "VOICE_PHISHING",
              "warningMessage": "보이스피싱 위험이 높습니다. 전화번호가 사기 데이터베이스에 등록되어 있으며, 긴급 송금을 요구하고 있습니다. 절대 입금하지 마시고 112에 신고하세요.",
              "reasons": ["Counter Scam 112 등록 번호", "긴급 송금 요구", "의심스러운 계좌번호"],
              "suspiciousParts": ["010-xxxx-1234", "지금 당장 입금"]
            }
            ```
        """.trimIndent()
    }

    private fun parseResponse(
        response: String,
        originalText: String,
        recentContext: String
    ): ScamAnalysis? {
        val jsonString = extractJsonFromResponse(response) ?: return null
        return try {
            val json = JSONObject(jsonString)
            parseJsonToScamAnalysis(json, originalText, recentContext)
        } catch (e: Exception) {
            DebugLog.warnLog(TAG) { "step=parse_failed reason=${e.message}" }
            null
        }
    }

    private fun extractJsonFromResponse(response: String): String? {
        val codeBlockRegex = Regex("""```json\s*\n?([\s\S]*?)\n?```""", RegexOption.IGNORE_CASE)
        codeBlockRegex.find(response)?.let { return it.groupValues[1].trim() }
        val jsonRegex = Regex("""\{[\s\S]*\}""")
        jsonRegex.find(response)?.let { return it.value.trim() }
        return null
    }

    private fun parseJsonToScamAnalysis(
        json: JSONObject,
        originalText: String,
        recentContext: String
    ): ScamAnalysis {
        val confidenceInt = json.optInt("confidence", 50)
        val confidence = (confidenceInt / 100f).coerceIn(0f, 1f)
        val scamTypeStr = json.optString("scamType", "UNKNOWN")
        val scamType = try {
            ScamType.valueOf(scamTypeStr)
        } catch (e: Exception) {
            inferScamType("${recentContext.ifBlank { originalText }}")
        }
        val warningMessage = json.optString("warningMessage", "").ifBlank {
            defaultWarning(scamType, confidence)
        }
        val reasons = mutableListOf<String>()
        json.optJSONArray("reasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.takeIf { it.isNotBlank() }?.let { reasons.add(it) }
            }
        }
        if (reasons.isEmpty()) reasons.add("온디바이스 LLM 분석 결과")
        val suspiciousParts = mutableListOf<String>()
        json.optJSONArray("suspiciousParts")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.takeIf { it.isNotBlank() }?.let { suspiciousParts.add(it) }
            }
        }
        return ScamAnalysis(
            isScam = confidence >= 0.5f,
            confidence = confidence,
            reasons = reasons,
            detectedKeywords = emptyList(),
            detectionMethod = DetectionMethod.LLM,
            scamType = scamType,
            warningMessage = warningMessage,
            suspiciousParts = suspiciousParts
        )
    }

    private fun defaultWarning(scamType: ScamType, confidence: Float): String {
        val percent = (confidence * 100).toInt()
        return when (scamType) {
            ScamType.INVESTMENT -> "투자 사기가 의심됩니다 (위험도 $percent%)."
            ScamType.USED_TRADE -> "중고거래 사기가 의심됩니다 (위험도 $percent%)."
            ScamType.PHISHING -> "피싱 링크가 포함되어 있습니다 (위험도 $percent%)."
            ScamType.VOICE_PHISHING -> "보이스피싱/스미싱 의심 (위험도 $percent%)."
            ScamType.IMPERSONATION -> "사칭 사기가 의심됩니다 (위험도 $percent%)."
            ScamType.LOAN -> "대출 사기가 의심됩니다 (위험도 $percent%)."
            else -> "사기 의심 메시지입니다 (위험도 $percent%)."
        }
    }

    private fun inferScamType(text: String): ScamType = when {
        text.contains("투자") || text.contains("수익") || text.contains("코인") || text.contains("주식") -> ScamType.INVESTMENT
        text.contains("입금") || text.contains("선결제") || text.contains("거래") || text.contains("택배") -> ScamType.USED_TRADE
        text.contains("URL") || text.contains("링크") || text.contains("피싱") -> ScamType.PHISHING
        text.contains("사칭") || text.contains("기관") -> ScamType.IMPERSONATION
        text.contains("대출") -> ScamType.LOAN
        else -> ScamType.UNKNOWN
    }
}
