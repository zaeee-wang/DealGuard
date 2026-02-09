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
     * 설명 전용 모드: Rule-only 결과를 기반으로 warningMessage만 생성한다.
     *
     * - 점수/유형/판정은 모두 무시하고, 사용자에게 보여줄 자연스러운 경고 문구만 생성.
     * - Rule이 이미 확신을 가진 경우(강한 신호) 사용.
     */
    private suspend fun analyzeExplanationOnly(
        originalText: String,
        recentContext: String,
        currentMessage: String,
        ruleReasons: List<String>,
        detectedKeywords: List<String>,
        ruleConfidence: Float,
        ruleScamType: String
    ): ScamAnalysis? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            DebugLog.warnLog(TAG) { "step=analyzeExplanationOnly skip reason=not_available" }
            return@withContext null
        }

        val masked = DebugLog.maskText(recentContext.ifBlank { originalText }, maxLen = 60)
        DebugLog.debugLog(TAG) {
            "step=analyzeExplanationOnly ruleConf=$ruleConfidence ruleType=$ruleScamType masked=\"$masked\""
        }

        val prompt = buildPromptExplanationOnly(
            recentContextLines = recentContext.lines().filter { it.isNotBlank() },
            currentMessage = currentMessage,
            ruleReasons = ruleReasons,
            detectedKeywords = detectedKeywords,
            ruleConfidence = ruleConfidence,
            ruleScamType = ruleScamType
        )

        try {
            val responseText = callGemini(prompt) ?: return@withContext null
            val analysis = parseGeminiResponse(responseText, originalText, recentContext)

            DebugLog.debugLog(TAG) {
                "step=analyzeExplanationOnly done hasResult=${analysis != null}"
            }
            analysis
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error calling Gemini LLM (explanation-only)", e)
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
        return if (request.explanationOnlyMode) {
            analyzeExplanationOnly(
                originalText = request.originalText,
                recentContext = request.recentContext,
                currentMessage = request.currentMessage,
                ruleReasons = request.ruleReasons,
                detectedKeywords = request.detectedKeywords,
                ruleConfidence = request.ruleConfidence,
                ruleScamType = request.ruleScamType
            )
        } else {
            analyze(
                originalText = request.originalText,
                recentContext = request.recentContext,
                currentMessage = request.currentMessage,
                ruleReasons = request.ruleReasons,
                detectedKeywords = request.detectedKeywords
            )
        }
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

    /**
     * 설명 전용 프롬프트 생성: Rule-only 결과를 기반으로 warningMessage만 생성.
     *
     * - 점수/유형은 이미 결정되어 있음을 명시
     * - LLM은 사용자 친화적 경고 문구만 생성
     */
    private fun buildPromptExplanationOnly(
        recentContextLines: List<String>,
        currentMessage: String,
        ruleReasons: List<String>,
        detectedKeywords: List<String>,
        ruleConfidence: Float,
        ruleScamType: String
    ): String {
        val recentBlock = if (recentContextLines.isNotEmpty()) {
            recentContextLines.joinToString("\n")
        } else {
            "(없음)"
        }
        val reasonsText = ruleReasons.joinToString(", ").ifBlank { "없음" }
        val keywordsText = detectedKeywords.joinToString(", ").ifBlank { "없음" }
        val ruleConfidencePercent = (ruleConfidence * 100).toInt()

        return """
            # 역할
            당신은 사기 가능성을 사용자에게 알려주는 도우미입니다.
            
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
