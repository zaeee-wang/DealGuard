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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

        /**
         * Thread-safe 일일 호출 카운터.
         * AtomicInteger로 동시 접근 시에도 안전하게 증가/조회 가능.
         */
        private val callsToday = AtomicInteger(0)

        /**
         * Thread-safe 날짜 저장.
         * 날짜가 바뀌면 callsToday를 리셋하기 위해 compareAndSet 사용.
         */
        private val lastDate = AtomicReference(LocalDate.now())
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    /**
     * 같은 대화 조각에 대한 중복 호출 방지를 위한 간단한 캐시.
     * @Volatile로 스레드 간 가시성 보장. 최악의 경우에도 추가 API 호출만 발생.
     */
    @Volatile
    private var lastTextHash: Int? = null

    @Volatile
    private var lastResult: ScamAnalysis? = null

    /**
     * LLM 사용 가능 여부를 반환한다.
     *
     * - ENABLE_LLM 플래그
     * - GEMINI_API_KEY 존재 여부
     * - 간단한 일일 호출 상한 (BuildConfig.GEMINI_MAX_CALLS_PER_DAY)
     */
    override fun isAvailable(): Boolean {
        if (!BuildConfig.ENABLE_LLM) {
            DebugLog.warnLog(TAG) { "step=isAvailable false reason=ENABLE_LLM_disabled" }
            return false
        }
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            DebugLog.warnLog(TAG) { "step=isAvailable false reason=GEMINI_API_KEY_empty" }
            return false
        }

        // 날짜가 바뀌면 카운터 리셋 (thread-safe)
        val today = LocalDate.now()
        val currentLastDate = lastDate.get()
        if (today != currentLastDate) {
            // compareAndSet으로 한 스레드만 리셋 성공
            if (lastDate.compareAndSet(currentLastDate, today)) {
                callsToday.set(0)
            }
        }

        val maxCallsPerDay = BuildConfig.GEMINI_MAX_CALLS_PER_DAY.coerceAtLeast(0)
        val currentCalls = callsToday.get()
        val available = currentCalls < maxCallsPerDay
        if (!available) {
            DebugLog.warnLog(TAG) {
                "step=isAvailable false reason=quota_exceeded callsToday=$currentCalls maxCallsPerDay=$maxCallsPerDay"
            }
        } else {
            DebugLog.debugLog(TAG) {
                "step=isAvailable true callsToday=$currentCalls maxCallsPerDay=$maxCallsPerDay"
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
     * - JSON 형식으로 출력하여 파싱 안정성을 높인다.
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
            시스템: 당신은 사용자에게 스캠(사기) 위험을 알리는 도우미입니다. 메시지와 룰 기반 탐지 정보를 바탕으로 사기 가능 여부를 판단하고, 사용자가 위험을 쉽게 인식할 수 있도록 간단하고 명확하게 안내합니다.

            [최근 대화]
            $recentBlock

            [현재 메시지]
            $currentMessage

            추가 정보:
            - 룰 기반 탐지 이유: $reasonsText
            - 탐지된 키워드: $keywordsText

            요청:
            아래 JSON 형식으로 응답하세요. warningMessage는 반드시 2~3문장으로, 왜 이 메시지를 스캠으로 의심했는지(예: 금전 요구, 긴급성, 의심 URL, 사칭 등)를 사용자에게 쉽게 설명하세요. 다른 텍스트는 포함하지 말고 JSON만 출력하세요.

            JSON 필드 설명:
            - confidence: 사기 가능성 (0~100 정수, 50 이상이면 사기 의심)
            - scamType: 사기 유형 (INVESTMENT/USED_TRADE/PHISHING/VOICE_PHISHING/IMPERSONATION/LOAN/UNKNOWN 중 하나)
              * VOICE_PHISHING: 보이스피싱/스미싱 (전화번호 기반 사기)
            - warningMessage: 사용자에게 보여줄 경고 문구. 2~3문장, 한국어. 왜 스캠 가능성을 봤는지 간단히 설명한 뒤, 행동 권고(의심 링크 클릭 금지 등)를 한 문장으로.
            - reasons: 탐지 이유 목록 (짧고 명확하게, 예: "긴급 송금 유도", "피싱 URL 포함")
            - suspiciousParts: 원문에서 의심되는 표현 인용 (최대 3개)

            출력 형식:
            ```json
            {
              "confidence": 75,
              "scamType": "PHISHING",
              "warningMessage": "금전 요구와 긴급성 표현이 함께 있어 사기 가능성이 있습니다. 의심스러운 링크가 포함된 메시지입니다. 알 수 없는 링크는 클릭하지 마세요.",
              "reasons": ["피싱 URL 감지", "긴급 유도 표현"],
              "suspiciousParts": ["지금 바로 클릭", "bit.ly/xxx"]
            }
            ```
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

        // Thread-safe 일일 호출 카운터 증가
        val currentCount = callsToday.incrementAndGet()
        val maxCallsPerDay = BuildConfig.GEMINI_MAX_CALLS_PER_DAY.coerceAtLeast(0)
        DebugLog.debugLog(TAG) {
            "step=call_quota_counter callsToday=$currentCount maxCallsPerDay=$maxCallsPerDay"
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
     * 예상 형식 (JSON):
     * ```json
     * {
     *   "confidence": 75,
     *   "scamType": "PHISHING",
     *   "warningMessage": "...",
     *   "reasons": ["...", "..."],
     *   "suspiciousParts": ["...", "..."]
     * }
     * ```
     *
     * JSON 파싱 실패 시 기존 자연어 파싱으로 폴백한다.
     */
    private fun parseGeminiResponse(
        response: String,
        originalText: String,
        recentContext: String
    ): ScamAnalysis? {
        // JSON 블록 추출 (```json ... ``` 또는 { ... })
        val jsonString = extractJsonFromResponse(response)

        if (jsonString != null) {
            try {
                val json = JSONObject(jsonString)
                return parseJsonResponse(json, originalText, recentContext)
            } catch (e: Exception) {
                DebugLog.warnLog(TAG) { "step=parse_json failed error=${e.message}" }
            }
        }

        // JSON 파싱 실패 시 기존 자연어 파싱으로 폴백
        return parseLegacyResponse(response, originalText, recentContext)
    }

    /**
     * 응답 텍스트에서 JSON 블록을 추출한다.
     */
    private fun extractJsonFromResponse(response: String): String? {
        // 1. ```json ... ``` 형식 추출
        val codeBlockRegex = Regex("""```json\s*\n?([\s\S]*?)\n?```""", RegexOption.IGNORE_CASE)
        codeBlockRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }

        // 2. { ... } 형식 직접 추출
        val jsonRegex = Regex("""\{[\s\S]*\}""")
        jsonRegex.find(response)?.let {
            return it.value.trim()
        }

        return null
    }

    /**
     * JSON 객체를 [ScamAnalysis]로 변환한다.
     */
    private fun parseJsonResponse(
        json: JSONObject,
        originalText: String,
        recentContext: String
    ): ScamAnalysis {
        // confidence: 0~100 → 0.0~1.0
        val confidenceInt = json.optInt("confidence", 50)
        val confidence = (confidenceInt / 100f).coerceIn(0f, 1f)

        // scamType
        val scamTypeStr = json.optString("scamType", "UNKNOWN")
        val scamType = try {
            ScamType.valueOf(scamTypeStr)
        } catch (e: Exception) {
            inferScamType("${recentContext.ifBlank { originalText }}")
        }

        // warningMessage
        val warningMessage = json.optString("warningMessage", "").ifBlank {
            generateDefaultWarning(scamType, confidence)
        }

        // reasons
        val reasonsArray = json.optJSONArray("reasons")
        val reasons = mutableListOf<String>()
        if (reasonsArray != null) {
            for (i in 0 until reasonsArray.length()) {
                reasonsArray.optString(i)?.takeIf { it.isNotBlank() }?.let {
                    reasons.add(it)
                }
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("LLM 분석 결과")
        }

        // suspiciousParts
        val suspiciousArray = json.optJSONArray("suspiciousParts")
        val suspiciousParts = mutableListOf<String>()
        if (suspiciousArray != null) {
            for (i in 0 until suspiciousArray.length()) {
                suspiciousArray.optString(i)?.takeIf { it.isNotBlank() }?.let {
                    suspiciousParts.add(it)
                }
            }
        }

        val isScam = confidence >= 0.5f

        DebugLog.debugLog(TAG) {
            "step=parse_json confidence=$confidence scamType=$scamType isScam=$isScam " +
                    "reasons=${reasons.size} suspiciousParts=${suspiciousParts.size}"
        }

        return ScamAnalysis(
            isScam = isScam,
            confidence = confidence,
            reasons = reasons,
            detectedKeywords = emptyList(),
            detectionMethod = DetectionMethod.LLM,
            scamType = scamType,
            warningMessage = warningMessage,
            suspiciousParts = suspiciousParts
        )
    }

    /**
     * 기존 자연어 형식 응답을 파싱한다 (폴백용).
     *
     * 예상 형식:
     * [위험도: 높음/중간/낮음]
     * 설명: ...
     * 위험 패턴: "표현1", "표현2"
     */
    private fun parseLegacyResponse(
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

        val reasons = listOf("LLM 평가: 위험도=$risk")

        val scamType = inferScamType("${recentContext.ifBlank { originalText }} $description")

        DebugLog.debugLog(TAG) {
            "step=parse_legacy risk=$risk isScam=$isScam confidence=$confidence scamType=$scamType"
        }

        return ScamAnalysis(
            isScam = isScam,
            confidence = confidence,
            reasons = reasons,
            detectedKeywords = emptyList(),
            detectionMethod = DetectionMethod.LLM,
            scamType = scamType,
            warningMessage = description,
            suspiciousParts = suspiciousParts
        )
    }

    /**
     * 스캠 유형에 따른 기본 경고 메시지를 생성한다.
     */
    private fun generateDefaultWarning(scamType: ScamType, confidence: Float): String {
        val percent = (confidence * 100).toInt()
        return when (scamType) {
            ScamType.INVESTMENT -> "투자 사기가 의심됩니다 (위험도 $percent%). 고수익 보장 투자는 대부분 사기입니다."
            ScamType.USED_TRADE -> "중고거래 사기가 의심됩니다 (위험도 $percent%). 선입금 요구 시 직거래하세요."
            ScamType.PHISHING -> "피싱 링크가 포함되어 있습니다 (위험도 $percent%). 의심스러운 링크를 클릭하지 마세요."
            ScamType.VOICE_PHISHING -> "이 전화번호는 보이스피싱/스미싱 신고 이력이 있습니다 (위험도 $percent%). 금전 요구에 응하지 마세요."
            ScamType.IMPERSONATION -> "사칭 사기가 의심됩니다 (위험도 $percent%). 공식 채널을 통해 확인하세요."
            ScamType.LOAN -> "대출 사기가 의심됩니다 (위험도 $percent%). 선수수료 요구는 불법입니다."
            else -> "사기 의심 메시지입니다 (위험도 $percent%). 주의하세요."
        }
    }

    /**
     * LLM 응답/원문을 기반으로 스캠 유형을 추론한다.
     */
    private fun inferScamType(text: String): ScamType {
        return when {
            // 보이스피싱/스미싱 (전화번호 기반) - 가장 먼저 체크
            text.contains("보이스피싱") || text.contains("스미싱") ||
                text.contains("전화번호") || text.contains("신고 이력") -> ScamType.VOICE_PHISHING

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
