package com.dealguard.detector

import android.util.Log
import android.util.Patterns
import com.dealguard.domain.repository.PhishingUrlRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * URL 위험도 분석기
 *
 * URL 위험도 점수 기준:
 * - 0.9f: KISA 피싱사이트 DB 등록 URL (확정적 위험, 신고된 피싱)
 * - 0.5f: 금융기관 사칭 의심 (가짜 은행 도메인)
 * - 0.4f: 무료/의심 도메인 (.tk, .ml 등 - 스캠에 자주 사용)
 * - 0.35f: IP 주소 직접 접근 (정상 서비스는 도메인 사용)
 * - 0.3f: 단축 URL (목적지 불명, 추적 어려움)
 * - 0.25f: 피싱 키워드 포함 (login, verify 등)
 * - 0.2f: 과도하게 긴 URL 또는 특수문자 과다 (난독화 의심)
 *
 * 점수 누적 방식:
 * - 여러 위험 요소가 있으면 점수가 누적됨
 * - 최종 점수는 0~1 범위로 정규화
 */
@Singleton
class UrlAnalyzer @Inject constructor(
    private val phishingUrlRepository: PhishingUrlRepository
) {

    companion object {
        private const val TAG = "UrlAnalyzer"

        // 위험도 점수 상수 (근거 문서화)
        private const val SCORE_KISA_DB = 0.9f      // KISA 등록 = 거의 확정
        private const val SCORE_BANK_SPOOF = 0.5f   // 금융기관 사칭 = 매우 위험
        private const val SCORE_FREE_TLD = 0.4f     // 무료 도메인 = 위험
        private const val SCORE_IP_ACCESS = 0.35f   // IP 직접 접근 = 의심
        private const val SCORE_SHORT_URL = 0.3f    // 단축 URL = 목적지 불명
        private const val SCORE_PHISHING_KW = 0.25f // 피싱 키워드 = 의심
        private const val SCORE_LONG_URL = 0.2f     // 긴 URL = 난독화 의심
        private const val SCORE_SPECIAL_CHAR = 0.2f // 특수문자 과다 = 난독화 의심
    }

    // 무료/의심 도메인 확장자
    // - 스캠에 자주 악용되는 무료 도메인
    // - 정상적인 서비스도 사용 가능하나 주의 필요
    private val suspiciousTlds = setOf(
        "tk", "ml", "ga", "cf", "gq",  // 무료 도메인
        "top", "xyz", "club", "work", "click",
        "loan", "men", "icu", "win", "bid"
    )

    // 단축 URL 서비스
    private val shortUrlDomains = setOf(
        "bit.ly", "goo.gl", "tinyurl.com", "ow.ly",
        "is.gd", "buff.ly", "adf.ly", "t.co",
        "url.kr", "han.gl", "me2.do"  // 한국 단축 URL
    )

    // 피싱 키워드 (도메인/경로에 포함)
    private val phishingKeywords = setOf(
        "login", "signin", "account", "secure", "verify",
        "update", "confirm", "banking", "payment", "wallet",
        "security", "suspended", "locked", "unusual",
        "gift", "prize", "winner", "claim", "bonus"
    )

    // 한국 금융기관 사칭 키워드
    private val koreanBankKeywords = setOf(
        "kb", "kookmin", "shinhan", "woori", "hana",
        "nh", "nonghyup", "ibk", "sc", "citi",
        "kakaobank", "kbank", "tossbank"
    )

    data class UrlAnalysisResult(
        val urls: List<String>,
        val suspiciousUrls: List<String>,
        val reasons: List<String>,
        val riskScore: Float
    )

    suspend fun analyze(text: String): UrlAnalysisResult {
        val urls = extractUrls(text)
        val suspiciousUrls = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var riskScore = 0f

        urls.forEach { url ->
            val urlLower = url.lowercase()

            // 0. KISA 피싱사이트 DB 체크 (최우선)
            try {
                if (phishingUrlRepository.isPhishingUrl(url)) {
                    suspiciousUrls.add(url)
                    riskScore += 0.9f
                    reasons.add("KISA 피싱사이트 DB 등록 URL")
                    Log.w(TAG, "KISA DB match found: $url")
                }
            } catch (e: Exception) {
                Log.e(TAG, "KISA DB check failed", e)
            }

            // 1. 무료/의심 도메인 체크
            if (suspiciousTlds.any { tld -> urlLower.contains(".$tld") }) {
                suspiciousUrls.add(url)
                riskScore += 0.4f
                reasons.add("무료 도메인 URL 감지: ${extractDomain(url)}")
            }

            // 2. 단축 URL 체크
            if (shortUrlDomains.any { domain -> urlLower.contains(domain) }) {
                suspiciousUrls.add(url)
                riskScore += 0.3f
                reasons.add("단축 URL 감지 (목적지 불명)")
            }

            // 3. 피싱 키워드 체크
            val phishingMatches = phishingKeywords.filter { keyword ->
                urlLower.contains(keyword)
            }
            if (phishingMatches.isNotEmpty()) {
                suspiciousUrls.add(url)
                riskScore += 0.25f * phishingMatches.size
                reasons.add("피싱 의심 키워드: ${phishingMatches.joinToString(", ")}")
            }

            // 4. 금융기관 사칭 체크 (공식 도메인이 아닌 경우)
            koreanBankKeywords.forEach { bankKeyword ->
                if (urlLower.contains(bankKeyword) && !isOfficialBankDomain(urlLower, bankKeyword)) {
                    suspiciousUrls.add(url)
                    riskScore += 0.5f
                    reasons.add("금융기관 사칭 의심: $bankKeyword")
                }
            }

            // 5. IP 주소 URL 체크 (일반적으로 의심)
            if (Regex("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(url)) {
                suspiciousUrls.add(url)
                riskScore += 0.35f
                reasons.add("IP 주소 직접 접근 (비정상)")
            }

            // 6. 과도하게 긴 URL (난독화 시도)
            if (url.length > 150) {
                suspiciousUrls.add(url)
                riskScore += 0.2f
                reasons.add("비정상적으로 긴 URL")
            }

            // 7. 특수문자 과다 사용 (난독화 시도)
            val specialCharCount = url.count { it == '@' || it == '%' || it == '&' }
            if (specialCharCount > 5) {
                suspiciousUrls.add(url)
                riskScore += 0.2f
                reasons.add("특수문자 과다 사용 (난독화 의심)")
            }
        }

        return UrlAnalysisResult(
            urls = urls,
            suspiciousUrls = suspiciousUrls.distinct(),
            reasons = reasons.distinct(),
            riskScore = riskScore.coerceIn(0f, 1f)
        )
    }

    private fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val matcher = Patterns.WEB_URL.matcher(text)

        while (matcher.find()) {
            matcher.group()?.let { url ->
                // http/https로 시작하지 않으면 https:// 추가
                val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                    url
                } else {
                    "https://$url"
                }
                urls.add(normalizedUrl)
            }
        }

        return urls
    }

    private fun extractDomain(url: String): String {
        return try {
            val domain = url.substringAfter("://")
                .substringBefore("/")
                .substringBefore("?")
            domain
        } catch (e: Exception) {
            ""
        }
    }

    private fun isOfficialBankDomain(url: String, bankKeyword: String): Boolean {
        // 공식 도메인 매핑
        val officialDomains = mapOf(
            "kb" to listOf("kbstar.com", "kbcard.com"),
            "kookmin" to listOf("kbstar.com"),
            "shinhan" to listOf("shinhan.com", "shinhansec.com", "shinhancard.com"),
            "woori" to listOf("wooribank.com"),
            "hana" to listOf("hanabank.com", "hanafn.com"),
            "nh" to listOf("nonghyup.com", "banking.nonghyup.com"),
            "nonghyup" to listOf("nonghyup.com"),
            "ibk" to listOf("ibk.co.kr"),
            "kakaobank" to listOf("kakaobank.com"),
            "kbank" to listOf("kbanknow.com"),
            "tossbank" to listOf("tossbank.com")
        )

        val domains = officialDomains[bankKeyword] ?: return false
        return domains.any { officialDomain ->
            url.contains(officialDomain, ignoreCase = true)
        }
    }
}
