package com.onguard.detector

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType

/**
 * LLM(llama.cpp + Qwen) 응답 문자열을 파싱해 [ScamAnalysis]로 변환.
 *
 * JSON 블록 추출 → DTO 매핑 → [ScamType] 변환까지 담당.
 */
object LlamaResponseParser {

    private const val TAG = "LlamaResponseParser"
    private val gson = Gson()

    /**
     * LLM 원시 응답에서 JSON 블록을 추출해 [ScamAnalysis]로 파싱.
     *
     * @param response LLM 원시 응답 (앞뒤 일반 텍스트 허용)
     * @return 파싱 성공 시 [ScamAnalysis], 실패 시 null
     */
    fun parseResponse(response: String): ScamAnalysis? {
        return try {
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                Log.w(TAG, "No valid JSON found in response (length=${response.length})")
                return null
            }
            val jsonString = response.substring(jsonStart, jsonEnd + 1)
            val dto = gson.fromJson(jsonString, LLMResponseDto::class.java)
            ScamAnalysis(
                isScam = dto.isScam,
                confidence = dto.confidence.coerceIn(0f, 1f),
                reasons = dto.reasons.orEmpty(),
                detectedKeywords = emptyList(),
                detectionMethod = DetectionMethod.LLM,
                scamType = parseScamType(dto.scamType),
                warningMessage = dto.warningMessage,
                suspiciousParts = dto.suspiciousParts.orEmpty()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response", e)
            null
        }
    }

    /**
     * LLM이 반환한 스캠 유형 문자열을 [ScamType]으로 변환.
     */
    fun parseScamType(typeString: String): ScamType = when {
        typeString.contains("투자") -> ScamType.INVESTMENT
        typeString.contains("중고") || typeString.contains("거래") -> ScamType.USED_TRADE
        typeString.contains("피싱") -> ScamType.PHISHING
        typeString.contains("사칭") -> ScamType.IMPERSONATION
        typeString.contains("로맨스") -> ScamType.ROMANCE
        typeString.contains("대출") -> ScamType.LOAN
        typeString.contains("정상") -> ScamType.SAFE
        else -> ScamType.UNKNOWN
    }

    /**
     * LLM 응답 JSON 파싱용 DTO.
     */
    data class LLMResponseDto(
        @SerializedName("isScam") val isScam: Boolean = false,
        @SerializedName("confidence") val confidence: Float = 0f,
        @SerializedName("scamType") val scamType: String = "정상",
        @SerializedName("warningMessage") val warningMessage: String = "",
        @SerializedName("reasons") val reasons: List<String>? = null,
        @SerializedName("suspiciousParts") val suspiciousParts: List<String>? = null
    )
}
