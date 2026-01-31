package com.dealguard.detector

import com.dealguard.domain.model.DetectionMethod
import com.dealguard.domain.repository.PhishingUrlRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

// Note: This class uses UrlAnalyzer which depends on Android Patterns.
// Run as instrumented tests for full coverage.
@Ignore("Uses Android Patterns via UrlAnalyzer - run as instrumented tests")
class HybridScamDetectorTest {

    private lateinit var hybridScamDetector: HybridScamDetector
    private lateinit var keywordMatcher: KeywordMatcher
    private lateinit var urlAnalyzer: UrlAnalyzer
    private lateinit var mockPhishingUrlRepository: PhishingUrlRepository

    @Before
    fun setup() {
        mockPhishingUrlRepository = mockk()
        coEvery { mockPhishingUrlRepository.isPhishingUrl(any()) } returns false

        keywordMatcher = KeywordMatcher()
        urlAnalyzer = UrlAnalyzer(mockPhishingUrlRepository)
        hybridScamDetector = HybridScamDetector(keywordMatcher, urlAnalyzer)
    }

    @Test
    fun `일반 대화는 스캠 아님`() = runBlocking {
        val text = "내일 점심 같이 먹을래? 12시에 학교 앞에서 만나자"
        val result = hybridScamDetector.analyze(text)

        assertFalse("일반 대화는 스캠 아님", result.isScam)
        assertTrue("낮은 신뢰도", result.confidence < 0.5f)
    }

    @Test
    fun `스캠 키워드만 포함 시 RULE_BASED 탐지`() = runBlocking {
        val text = "급전 필요합니다. 계좌번호 알려주세요."
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertEquals(DetectionMethod.RULE_BASED, result.detectionMethod)
    }

    @Test
    fun `URL과 키워드 모두 포함 시 HYBRID 탐지`() = runBlocking {
        val text = "급전 필요합니다. 여기로 접속: bit.ly/abc123"
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertEquals(DetectionMethod.HYBRID, result.detectionMethod)
    }

    @Test
    fun `높은 신뢰도 스캠은 즉시 반환`() = runBlocking {
        val text = "긴급! 검찰청에서 연락드립니다. 계좌번호 보내주세요. 인증번호도 필요합니다."
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertTrue("높은 신뢰도", result.confidence > 0.7f)
    }

    @Test
    fun `긴급 + 금전 + URL 조합 시 추가 위험도`() = runBlocking {
        val text = "긴급합니다! 입금 확인해주세요. 링크: bit.ly/urgent"
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertTrue("조합 공격 이유 포함", result.reasons.any { it.contains("조합") || it.contains("긴급") })
    }

    @Test
    fun `빈 문자열은 스캠 아님`() = runBlocking {
        val result = hybridScamDetector.analyze("")

        assertFalse("빈 문자열은 스캠 아님", result.isScam)
    }

    @Test
    fun `피싱 URL만 있어도 위험도 증가`() = runBlocking {
        val text = "확인해보세요: http://fake-login.tk/verify"
        val result = hybridScamDetector.analyze(text)

        assertTrue("URL 관련 이유 포함", result.reasons.isNotEmpty())
        assertTrue("위험도 0 이상", result.confidence > 0f)
    }

    @Test
    fun `여러 스캠 요소 결합 시 높은 신뢰도`() = runBlocking {
        val text = """
            경찰청에서 연락드립니다.
            급하게 처리하셔야 합니다.
            계좌번호: 123-456-789012
            OTP 번호도 알려주세요.
            확인 링크: bit.ly/verify
        """.trimIndent()
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertTrue("매우 높은 신뢰도", result.confidence > 0.8f)
        assertTrue("여러 키워드 탐지", result.detectedKeywords.size > 3)
        assertEquals(DetectionMethod.HYBRID, result.detectionMethod)
    }

    @Test
    fun `투자 사기 패턴 탐지`() = runBlocking {
        val text = "원금보장! 고수익 비트코인투자 기회. 지금 바로 연락주세요."
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertTrue("투자 관련 키워드 탐지", result.detectedKeywords.any {
            it.contains("원금보장") || it.contains("고수익") || it.contains("비트코인")
        })
    }

    @Test
    fun `대출 사기 패턴 탐지`() = runBlocking {
        val text = "무담보대출 당일대출 가능합니다. 선입금 필요합니다."
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertTrue("대출 관련 키워드 탐지", result.detectedKeywords.any {
            it.contains("대출") || it.contains("선입금")
        })
    }

    @Test
    fun `택배 사칭 스캠 탐지`() = runBlocking {
        val text = "택배발송 안내입니다. 추가배송비 결제가 필요합니다. bit.ly/delivery"
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertEquals(DetectionMethod.HYBRID, result.detectionMethod)
    }

    @Test
    fun `재택알바 사기 탐지`() = runBlocking {
        val text = "재택알바 고수익알바 누구나가능! 통장만있으면 됩니다."
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
    }

    @Test
    fun `신뢰도 범위 검증`() = runBlocking {
        // 매우 많은 스캠 요소
        val text = """
            긴급! 경찰청입니다. 체포영장이 발부되었습니다.
            지금 당장 계좌번호 알려주세요.
            인증번호와 OTP도 필요합니다.
            비밀번호 확인 바랍니다.
            http://fake-police.tk/verify
            bit.ly/urgent
        """.trimIndent()
        val result = hybridScamDetector.analyze(text)

        assertTrue("신뢰도 0 이상", result.confidence >= 0f)
        assertTrue("신뢰도 1 이하", result.confidence <= 1f)
    }

    @Test
    fun `reasons 리스트가 비어있지 않음`() = runBlocking {
        val text = "급전 필요해요"
        val result = hybridScamDetector.analyze(text)

        if (result.isScam) {
            assertTrue("이유가 있어야 함", result.reasons.isNotEmpty())
        }
    }

    @Test
    fun `송금 요청 패턴 탐지`() = runBlocking {
        val text = "빨리 입금해주세요. 계좌번호 보내드릴게요."
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
    }

    @Test
    fun `기관 사칭 + 협박 패턴`() = runBlocking {
        val text = "금감원에서 연락드립니다. 벌금납부 하지 않으면 체포됩니다."
        val result = hybridScamDetector.analyze(text)

        assertTrue("스캠으로 판정", result.isScam)
        assertTrue("높은 신뢰도", result.confidence > 0.6f)
    }
}
