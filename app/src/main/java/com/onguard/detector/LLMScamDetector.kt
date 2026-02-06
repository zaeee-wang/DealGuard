package com.onguard.detector

import com.onguard.BuildConfig
import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType
import com.onguard.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 외부 LLM(Google Gemini 1.5 Flash)을 사용한 스캠 분석기.
 *
 * 룰 기반 탐지 결과를 바탕으로 프롬프트를 구성하고,
 * Gemini API의 무료 티어 범위 내에서만 호출하여
 * 한국어 컨텍스트 설명/위험도/위험 패턴을 생성한다.
 *
 * - 네트워크/쿼터 이슈가 있으면 null을 반환하고 HybridScamDetector가 rule-only로 폴백한다.
 */
@Singleton
class LLMScamDetector @Inject constructor() : ScamLlmClient {

    companion object {
        private const val TAG = "OnGuardLLM"

        // Gemini 2.5 Flash REST 엔드포인트 (안정 버전, 무료 티어 지원)
        // 최신 모델: gemini-3-flash-preview (프리뷰), gemini-2.5-flash (안정)
        // 무료 티어에서는 gemini-2.5-flash 사용 권장
        private const val GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

        private var callsToday: Int = 0
        private var lastDate: LocalDate = LocalDate.now()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    // 같은 대화 조각에 대한 중복 호출 방지를 위한 간단한 캐시
    private var lastTextHash: Int? = null
    private var lastResult: ScamAnalysis? = null

    /**
     * LLM 사용 가능 여부를 반환한다.
     *
     * - ENABLE_LLM 플래그
     * - GEMINI_API_KEY 존재 여부
     * - 간단한 일일 호출 상한 (BuildConfig.GEMINI_MAX_CALLS_PER_DAY)
     */
    fun isAvailable(): Boolean {
        if (!BuildConfig.ENABLE_LLM) {
            DebugLog.warnLog(TAG) { "step=isAvailable false reason=ENABLE_LLM_disabled" }
            return false
        }
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            DebugLog.warnLog(TAG) { "step=isAvailable false reason=GEMINI_API_KEY_empty" }
            return false
        }

        // 날짜가 바뀌면 카운터 리셋
        val today = LocalDate.now()
        if (today != lastDate) {
            lastDate = today
            callsToday = 0
        }

        val maxCallsPerDay = BuildConfig.GEMINI_MAX_CALLS_PER_DAY.coerceAtLeast(0)
        val available = callsToday < maxCallsPerDay
        if (!available) {
            DebugLog.warnLog(TAG) {
                "step=isAvailable false reason=quota_exceeded callsToday=$callsToday maxCallsPerDay=$maxCallsPerDay"
            }
        } else {
            DebugLog.debugLog(TAG) {
                "step=isAvailable true callsToday=$callsToday maxCallsPerDay=$maxCallsPerDay"
            }
        }
        return available
    }

    /**
     * 룰 기반 분석 결과를 바탕으로 LLM에게 추가 설명/위험도 평가를 요청한다.
     *
     * @param originalText 전체 원문 채팅 메시지
     * @param recentContext 최근 대화 줄들을 합친 문자열
     * @param currentMessage 현재 메시지(마지막 줄)
     * @param ruleReasons 룰 기반 탐지 사유
     * @param detectedKeywords 탐지된 키워드
     */
    suspend fun analyze(
        originalText: String,
        recentContext: String,
        currentMessage: String,
        ruleReasons: List<String>,
        detectedKeywords: List<String>
    ): ScamAnalysis? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            DebugLog.warnLog(TAG) { "step=analyze skip reason=not_available_or_quota" }
            return@withContext null
        }

        val contextForHash = (recentContext.ifBlank { originalText })
        val hash = contextForHash.hashCode()
        if (hash == lastTextHash && lastResult != null) {
            DebugLog.debugLog(TAG) { "step=analyze cache_hit" }
            return@withContext lastResult
        }

        val masked = DebugLog.maskText(contextForHash, maxLen = 60)
        DebugLog.debugLog(TAG) {
            "step=analyze input length=${contextForHash.length} masked=\"$masked\" ruleReasons=${ruleReasons.size} keywords=${detectedKeywords.size}"
        }

        val prompt = buildPrompt(
            recentContextLines = recentContext.lines().filter { it.isNotBlank() },
            currentMessage = currentMessage,
            ruleReasons = ruleReasons,
            detectedKeywords = detectedKeywords
        )

        try {
            val responseText = callGemini(prompt) ?: return@withContext null
            val analysis = parseGeminiResponse(responseText, originalText, recentContext)

            DebugLog.debugLog(TAG) {
                "step=analyze done hasResult=${analysis != null}"
            }
            if (analysis != null) {
                lastTextHash = hash
                lastResult = analysis
            }
            analysis
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error calling Gemini LLM", e)
            null
        }
    }

    /**
     * ScamLlmClient 인터페이스 구현.
     *
     * - 현재는 내부 analyze(...) 구현으로 위임한다.
     * - 향후 서버 프록시/다른 LLM Provider 로 교체 시 이 레이어만 대체하면 된다.
     */
    override suspend fun analyze(request: ScamLlmRequest): ScamAnalysis? {
        return analyze(
            originalText = request.originalText,
            recentContext = request.recentContext,
            currentMessage = request.currentMessage,
            ruleReasons = request.ruleReasons,
            detectedKeywords = request.detectedKeywords
        )
    }

    /**
     * Gemini에 전달할 한국어 프롬프트를 구성한다.
     *
     * - 최근 대화 줄들과 현재 메시지를 분리해 보여주고,
     * - 룰 기반 탐지 이유/키워드 정보를 함께 전달한다.
     */
    private fun buildPrompt(
        recentContextLines: List<String>,
        currentMessage: String,
        ruleReasons: List<String>,
        detectedKeywords: List<String>
    ): String {
        val reasonsText = if (ruleReasons.isEmpty()) {
            "없음"
        } else {
            ruleReasons.joinToString("; ")
        }

        val keywordsText = if (detectedKeywords.isEmpty()) {
            "없음"
        } else {
            detectedKeywords.joinToString(", ")
        }

        val recentBlock = if (recentContextLines.isEmpty()) {
            "- (최근 대화 없음)"
        } else {
            recentContextLines.joinToString("\n") { "- $it" }
        }

        return """
            시스템: 당신은 피싱/사기 메시지를 분석하는 한국어 보안 전문가 "OnGuard"입니다.
            
            [최근 대화]
            $recentBlock

            [현재 메시지]
            $currentMessage

            추가 정보:
            - 룰 기반 탐지 이유: $reasonsText
            - 탐지된 키워드: $keywordsText

            요청:
            1. 전체 대화 흐름과 현재 메시지를 모두 보고, 이 대화가 사기/피싱일 가능성이 어느 정도인지 "높음/중간/낮음" 중 하나로 판단하세요.
            2. 왜 그렇게 판단했는지 한국어로 2~3문장 정도로 설명하세요. 이전 대화에서 어떤 내용이 있었는지도 함께 언급하세요.
            3. 특히 위험한 표현이나 패턴이 있으면 따옴표로 인용해서 알려주세요.

            출력 형식:
            [위험도: 높음/중간/낮음]
            설명: ...
            위험 패턴: "표현1", "표현2"
        """.trimIndent()
    }

    /**
     * Gemini 1.5 Flash REST API 호출.
     *
     * - contents[0].parts[0].text 에 전체 프롬프트 전달
     * - candidates[0].content.parts[..].text 를 모두 이어붙여 반환
     */
    private fun callGemini(prompt: String): String? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            DebugLog.warnLog(TAG) { "step=call skip reason=empty_api_key" }
            return null
        }

        // 간단한 일일 호출 카운터 증가
        callsToday += 1
        val maxCallsPerDay = BuildConfig.GEMINI_MAX_CALLS_PER_DAY.coerceAtLeast(0)
        DebugLog.debugLog(TAG) {
            "step=call_quota_counter callsToday=$callsToday maxCallsPerDay=$maxCallsPerDay"
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", prompt)
                        )
                    )
                )
            )
        }

        val request = Request.Builder()
            .url("$GEMINI_BASE_URL?key=$apiKey")
            .post(bodyJson.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "no error body"
                DebugLog.warnLog(TAG) {
                    "step=call error code=${response.code} message=${response.message} body=$errorBody"
                }
                return null
            }

            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val first = candidates.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partObj = parts.optJSONObject(i)
                val text = partObj?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    sb.append(text)
                    if (!text.endsWith("\n")) sb.append("\n")
                }
            }

            return sb.toString().trim()
        }
    }

    /**
     * Gemini 응답 텍스트를 [ScamAnalysis]로 변환한다.
     *
     * 예상 형식:
     * [위험도: 높음/중간/낮음]
     * 설명: ...
     * 위험 패턴: "표현1", "표현2"
     */
    private fun parseGeminiResponse(
        response: String,
        originalText: String,
        recentContext: String
    ): ScamAnalysis? {
        val lines = response.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val riskLine = lines.firstOrNull { it.startsWith("[위험도") } ?: lines.first()
        val descLine = lines.firstOrNull { it.startsWith("설명:") } ?: lines.getOrNull(1)
        val patternLine = lines.firstOrNull { it.startsWith("위험 패턴:") }

        val riskRegex = Regex("""\[위험도:\s*(높음|중간|낮음)]""")
        val match = riskRegex.find(riskLine)
        val risk = match?.groupValues?.getOrNull(1)?.trim() ?: "중간"

        val confidence = when (risk) {
            "높음" -> 0.85f
            "중간" -> 0.6f
            "낮음" -> 0.3f
            else -> 0.6f
        }.coerceIn(0f, 1f)

        val isScam = risk != "낮음"

        val description = descLine
            ?.removePrefix("설명:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                // 설명이 없으면 최근 대화의 마지막 몇 줄을 마스킹해 기본 설명으로 사용
                val recentLines = recentContext.lines().map { it.trim() }.filter { it.isNotBlank() }
                val fallbackContext = if (recentLines.isNotEmpty()) {
                    recentLines.takeLast(3).joinToString(" / ")
                } else {
                    originalText
                }
                DebugLog.maskText(fallbackContext, maxLen = 80)
            }

        val suspiciousParts: List<String> = patternLine
            ?.removePrefix("위험 패턴:")
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val reasons = listOf("LLM 평가: 위험도=$risk, 설명: $description")

        val scamType = inferScamType("${recentContext.ifBlank { originalText }} $description")

        val analysis = ScamAnalysis(
            isScam = isScam,
            confidence = confidence,
            reasons = reasons,
            detectedKeywords = emptyList(),
            detectionMethod = DetectionMethod.LLM,
            scamType = scamType,
            warningMessage = description,
            suspiciousParts = suspiciousParts
        )

        DebugLog.debugLog(TAG) {
            "step=parse risk=$risk isScam=$isScam confidence=$confidence scamType=$scamType suspiciousParts=${suspiciousParts.size}"
        }

        return analysis
    }

    /**
     * LLM 응답/원문을 기반으로 스캠 유형을 추론한다.
     */
    private fun inferScamType(text: String): ScamType {
        return when {
            text.contains("투자") || text.contains("수익") ||
                text.contains("코인") || text.contains("주식") -> ScamType.INVESTMENT

            text.contains("입금") || text.contains("선결제") ||
                text.contains("거래") || text.contains("택배") -> ScamType.USED_TRADE

            text.contains("URL") || text.contains("링크") ||
                text.contains("피싱") -> ScamType.PHISHING

            text.contains("사칭") || text.contains("기관") -> ScamType.IMPERSONATION

            text.contains("대출") -> ScamType.LOAN

            text.contains("정상") -> ScamType.SAFE

            else -> ScamType.UNKNOWN
        }
    }
}
