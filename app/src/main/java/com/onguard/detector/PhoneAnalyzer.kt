package com.onguard.detector

import android.util.Log
import com.onguard.domain.model.PhoneAnalysisResult
import com.onguard.domain.repository.CounterScamRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 전화번호 위험도 분석기
 *
 * 텍스트에서 전화번호를 추출한 뒤 Counter Scam 112 DB를 조회하여 위험도를 산출합니다.
 * API 실패 시에도 로컬 패턴 분석으로 기본 위험도를 제공합니다 (graceful degradation).
 *
 * @param counterScamRepository Counter Scam 112 API Repository
 */
@Singleton
class PhoneAnalyzer @Inject constructor(
    private val counterScamRepository: CounterScamRepository
) {

    companion object {
        private const val TAG = "PhoneAnalyzer"

        // 위험도 점수 상수
        /** Counter Scam 112 DB 등록 번호 */
        private const val SCORE_DB_REGISTERED = 0.9f
        /** 보이스피싱 신고 이력 */
        private const val SCORE_VOICE_PHISHING = 0.21f
        /** 스미싱 신고 이력 */
        private const val SCORE_SMS_PHISHING = 0.18f
        /** 다수 신고 (5건 이상) */
        private const val SCORE_MULTIPLE_REPORTS = 0.3f
        /** 의심 대역 (070/050) */
        private const val SCORE_SUSPICIOUS_PREFIX = 0.2f
    }

    /** 한국 전화번호 패턴 */
    private val phonePatterns = listOf(
        // 휴대폰 (010, 011, 016, 017, 018, 019)
        Regex("01[016789]-?\\d{3,4}-?\\d{4}"),

        // 서울 지역번호 (02) - 2자리
        Regex("02-?\\d{3,4}-?\\d{4}"),

        // 경기/인천/지방 지역번호 (031~064) - 3자리
        Regex("0[3-6][0-9]-?\\d{3,4}-?\\d{4}"),

        // 대표번호 (15XX, 16XX, 18XX 계열)
        // 1588, 1577, 1566, 1544, 1600, 1644, 1899 등
        Regex("1[5689][0-9]{2}-?\\d{4}"),

        // 인터넷전화 (070)
        Regex("070-?\\d{3,4}-?\\d{4}"),

        // 050 번호 (발신번호 표시제한 서비스)
        Regex("050[0-9]-?\\d{3,4}-?\\d{4}"),

        // 국제번호 형식 (+82)
        Regex("\\+82-?1?0?-?\\d{4}-?\\d{4}")
    )

    /** 의심 전화번호 대역 */
    private val suspiciousPrefixes = setOf(
        "070",  // 인터넷전화 (보이스피싱에 악용)
        "050"   // 발신번호 표시제한
    )

    /**
     * 텍스트에서 전화번호를 추출하고 위험도를 분석합니다.
     *
     * @param text 분석할 채팅/메시지 텍스트
     * @return PhoneAnalysisResult 추출 번호, DB 등록 번호, 위험도 등
     */
    suspend fun analyze(text: String): PhoneAnalysisResult {
        val extractedPhones = extractPhoneNumbers(text)

        if (extractedPhones.isEmpty()) {
            return PhoneAnalysisResult.EMPTY
        }

        val scamPhones = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var riskScore = 0f
        var totalVoiceCount = 0
        var totalSmsCount = 0
        var hasSuspiciousPrefix = false

        extractedPhones.forEach { phone ->
            // 이미 최대 위험도에 도달한 경우 추가 분석 스킵 (최적화)
            if (riskScore >= 1.0f) {
                Log.d(TAG, "Max risk score reached, skipping remaining phones")
                return@forEach
            }

            val normalizedPhone = normalizePhone(phone)

            // 1. 의심 대역 확인 (로컬)
            if (suspiciousPrefixes.any { normalizedPhone.startsWith(it) }) {
                hasSuspiciousPrefix = true
                riskScore = (riskScore + SCORE_SUSPICIOUS_PREFIX).coerceAtMost(1f)
                reasons.add("의심 전화번호 대역: ${phone.take(3)}xxx")
                Log.d(TAG, "Suspicious prefix detected: $phone")
            }

            // 2. Counter Scam 112 DB 조회
            try {
                val result = counterScamRepository.searchPhone(normalizedPhone)
                result.getOrNull()?.let { response ->
                    if (response.totalCount > 0) {
                        scamPhones.add(phone)
                        riskScore = (riskScore + SCORE_DB_REGISTERED).coerceAtMost(1f)
                        reasons.add("Counter Scam 112 DB 등록 번호: $phone")
                        Log.w(TAG, "Scam phone detected: $phone (total=${response.totalCount})")

                        // 보이스피싱 이력
                        if (response.voiceCount > 0) {
                            totalVoiceCount += response.voiceCount
                            riskScore = (riskScore + SCORE_VOICE_PHISHING).coerceAtMost(1f)
                            reasons.add("보이스피싱 신고 ${response.voiceCount}건")
                        }

                        // 스미싱 이력
                        if (response.smsCount > 0) {
                            totalSmsCount += response.smsCount
                            riskScore = (riskScore + SCORE_SMS_PHISHING).coerceAtMost(1f)
                            reasons.add("스미싱 신고 ${response.smsCount}건")
                        }

                        // 다수 신고
                        if (response.totalCount >= 5) {
                            riskScore = (riskScore + SCORE_MULTIPLE_REPORTS).coerceAtMost(1f)
                            reasons.add("다수 신고 이력 (${response.totalCount}건)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check phone in Counter Scam DB: $phone", e)
            }
        }

        return PhoneAnalysisResult(
            extractedPhones = extractedPhones,
            scamPhones = scamPhones,
            reasons = reasons.distinct(),
            riskScore = riskScore,  // 이미 중간에 coerceAtMost(1f) 적용됨
            voicePhishingCount = totalVoiceCount,
            smsPhishingCount = totalSmsCount,
            isSuspiciousPrefix = hasSuspiciousPrefix
        )
    }

    /**
     * 텍스트에서 전화번호 추출
     */
    private fun extractPhoneNumbers(text: String): List<String> {
        val phones = mutableSetOf<String>()

        phonePatterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                phones.add(match.value)
            }
        }

        return phones.toList()
    }

    /**
     * 전화번호 정규화 (하이픈, 공백 제거)
     */
    private fun normalizePhone(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
            .let { if (it.startsWith("+82")) "0${it.drop(3)}" else it }
    }
}
