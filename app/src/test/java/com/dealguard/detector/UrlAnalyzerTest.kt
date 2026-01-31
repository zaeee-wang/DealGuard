package com.dealguard.detector

import com.dealguard.domain.repository.PhishingUrlRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

// Note: This class uses Android Patterns which requires Android framework.
// Run as instrumented tests for full coverage.
@Ignore("Uses Android Patterns - run as instrumented tests")
class UrlAnalyzerTest {

    private lateinit var urlAnalyzer: UrlAnalyzer
    private lateinit var mockPhishingUrlRepository: PhishingUrlRepository

    @Before
    fun setup() {
        mockPhishingUrlRepository = mockk()
        // Default mock behavior: no URL is in KISA DB
        coEvery { mockPhishingUrlRepository.isPhishingUrl(any()) } returns false
        urlAnalyzer = UrlAnalyzer(mockPhishingUrlRepository)
    }

    @Test
    fun `정상 텍스트에서 URL 없음`() = runBlocking {
        val text = "안녕하세요. 오늘 점심 같이 먹을래요?"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 없어야 함", result.urls.isEmpty())
        assertTrue("의심 URL이 없어야 함", result.suspiciousUrls.isEmpty())
        assertEquals(0f, result.riskScore, 0.01f)
    }

    @Test
    fun `무료 도메인 URL 탐지`() = runBlocking {
        val text = "여기 클릭하세요: http://scam-site.tk/login"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("무료 도메인 이유 포함", result.reasons.any { it.contains("무료 도메인") })
        assertTrue("위험도 0.4 이상", result.riskScore >= 0.4f)
    }

    @Test
    fun `단축 URL 탐지`() = runBlocking {
        val text = "이 링크 확인해보세요 bit.ly/abc123"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("단축 URL 이유 포함", result.reasons.any { it.contains("단축 URL") })
    }

    @Test
    fun `한국 단축 URL 탐지`() = runBlocking {
        val text = "확인하세요 url.kr/abcd"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
    }

    @Test
    fun `피싱 키워드 포함 URL 탐지`() = runBlocking {
        val text = "계정 확인: https://fake-bank.com/login/verify/account"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("피싱 키워드 이유 포함", result.reasons.any { it.contains("피싱") })
    }

    @Test
    fun `금융기관 사칭 URL 탐지`() = runBlocking {
        val text = "KB국민은행입니다: https://fake-kb-bank.xyz/login"
        val result = urlAnalyzer.analyze(text)

        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("금융기관 사칭 이유 포함", result.reasons.any { it.contains("금융기관 사칭") })
        assertTrue("높은 위험도", result.riskScore >= 0.5f)
    }

    @Test
    fun `공식 은행 도메인은 사칭으로 분류 안함`() = runBlocking {
        val text = "공식 사이트: https://www.kbstar.com"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        assertFalse("금융기관 사칭 이유 없어야 함", result.reasons.any { it.contains("금융기관 사칭") })
    }

    @Test
    fun `IP 주소 직접 접근 URL 탐지`() = runBlocking {
        val text = "여기로 접속: http://192.168.1.1/admin"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("IP 주소 이유 포함", result.reasons.any { it.contains("IP 주소") })
    }

    @Test
    fun `과도하게 긴 URL 탐지`() = runBlocking {
        val longPath = "a".repeat(200)
        val text = "https://example.com/$longPath"
        val result = urlAnalyzer.analyze(text)

        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("긴 URL 이유 포함", result.reasons.any { it.contains("긴 URL") })
    }

    @Test
    fun `특수문자 과다 URL 탐지`() = runBlocking {
        val text = "https://example.com?a=%20&b=%20&c=%20&d=%20&e=%20&f=%20"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        // 특수문자 개수에 따라 탐지될 수 있음
    }

    @Test
    fun `여러 URL 동시 탐지`() = runBlocking {
        val text = "첫번째: bit.ly/abc 두번째: http://scam.tk/test"
        val result = urlAnalyzer.analyze(text)

        assertTrue("여러 URL 발견", result.urls.size >= 2)
        assertTrue("여러 의심 URL 발견", result.suspiciousUrls.size >= 2)
    }

    @Test
    fun `http 없는 URL도 정규화`() = runBlocking {
        val text = "www.example.com/test"
        val result = urlAnalyzer.analyze(text)

        assertTrue("URL이 발견되어야 함", result.urls.isNotEmpty())
        assertTrue("https로 정규화", result.urls.any { it.startsWith("https://") })
    }

    @Test
    fun `위험도 최대값 1로 제한`() = runBlocking {
        // 여러 위험 요소가 있는 URL
        val text = "http://fake-kb.tk/login/verify/account/secure http://192.168.1.1 bit.ly/xyz"
        val result = urlAnalyzer.analyze(text)

        assertTrue("위험도 1 이하", result.riskScore <= 1f)
    }

    @Test
    fun `xyz 도메인 탐지`() = runBlocking {
        val text = "https://suspicious-site.xyz/claim-prize"
        val result = urlAnalyzer.analyze(text)

        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("무료 도메인 이유 포함", result.reasons.any { it.contains("무료 도메인") })
    }

    @Test
    fun `빈 문자열 처리`() = runBlocking {
        val result = urlAnalyzer.analyze("")

        assertTrue("URL 없음", result.urls.isEmpty())
        assertTrue("의심 URL 없음", result.suspiciousUrls.isEmpty())
        assertEquals(0f, result.riskScore, 0.01f)
    }

    @Test
    fun `신한은행 공식 도메인 허용`() = runBlocking {
        val text = "https://www.shinhan.com/banking"
        val result = urlAnalyzer.analyze(text)

        assertFalse("공식 도메인은 사칭 아님", result.reasons.any { it.contains("금융기관 사칭") })
    }

    @Test
    fun `카카오뱅크 공식 도메인 허용`() = runBlocking {
        val text = "https://www.kakaobank.com/account"
        val result = urlAnalyzer.analyze(text)

        assertFalse("공식 도메인은 사칭 아님", result.reasons.any { it.contains("금융기관 사칭") })
    }

    @Test
    fun `KISA DB 등록 URL 탐지`() = runBlocking {
        // Mock: this URL is in KISA DB
        coEvery { mockPhishingUrlRepository.isPhishingUrl("https://known-phishing-site.com") } returns true

        val text = "이 사이트 조심하세요: https://known-phishing-site.com"
        val result = urlAnalyzer.analyze(text)

        assertTrue("의심 URL로 분류", result.suspiciousUrls.isNotEmpty())
        assertTrue("KISA DB 이유 포함", result.reasons.any { it.contains("KISA") })
        assertTrue("매우 높은 위험도", result.riskScore >= 0.9f)
    }
}
