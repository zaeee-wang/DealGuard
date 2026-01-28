package com.dealguard.detector

import com.dealguard.domain.model.DetectionMethod
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KeywordMatcherTest {

    private lateinit var keywordMatcher: KeywordMatcher

    @Before
    fun setup() {
        keywordMatcher = KeywordMatcher()
    }

    @Test
    fun `급전 키워드 포함 시 스캠 탐지`() {
        val text = "급전 필요하시면 연락주세요"
        val result = keywordMatcher.analyze(text)

        assertTrue("스캠으로 판정되어야 함", result.isScam)
        assertTrue("신뢰도가 0.5 이상이어야 함", result.confidence > 0.5f)
        assertEquals(DetectionMethod.RULE_BASED, result.detectionMethod)
    }

    @Test
    fun `일반 대화는 스캠 아님`() {
        val text = "내일 점심 같이 먹을래? 12시에 학교 앞에서 만나자"
        val result = keywordMatcher.analyze(text)

        assertFalse("일반 대화는 스캠이 아님", result.isScam)
        assertTrue("신뢰도가 낮아야 함", result.confidence < 0.3f)
    }

    @Test
    fun `계좌번호 패턴 포함 시 고위험 판정`() {
        val text = "여기로 이체해주세요 1234-5678-9012-3456"
        val result = keywordMatcher.analyze(text)

        assertTrue("스캠으로 판정되어야 함", result.isScam)
        assertTrue("높은 신뢰도", result.confidence > 0.6f)
        assertTrue("계좌번호 패턴 감지", result.reasons.any { it.contains("계좌") })
    }

    @Test
    fun `매우 위험한 키워드 조합 시 매우 높은 신뢰도`() {
        val text = "긴급! 계좌번호 알려주세요. 인증번호도 보내주세요. 1234-5678-9012"
        val result = keywordMatcher.analyze(text)

        assertTrue("스캠으로 판정되어야 함", result.isScam)
        assertTrue("매우 높은 신뢰도", result.confidence > 0.8f)
        assertTrue("여러 키워드 감지", result.detectedKeywords.size >= 3)
    }

    @Test
    fun `전화번호 패턴 감지`() {
        val text = "010-1234-5678로 연락주세요"
        val result = keywordMatcher.analyze(text)

        assertTrue("전화번호 패턴 감지", result.reasons.any { it.contains("전화") || it.contains("번호") })
    }

    @Test
    fun `금융기관 사칭 키워드`() {
        val text = "경찰청에서 연락드렸습니다. 계좌확인 필요합니다."
        val result = keywordMatcher.analyze(text)

        assertTrue("스캠으로 판정되어야 함", result.isScam)
        assertTrue("높은 신뢰도", result.confidence > 0.5f)
    }

    @Test
    fun `복합 스캠 패턴 보너스 적용`() {
        val text = "급하게 송금 필요합니다. OTP 번호 알려주세요."
        val result = keywordMatcher.analyze(text)

        assertTrue("스캠으로 판정되어야 함", result.isScam)
        assertTrue("복합 패턴 보너스", result.reasons.any { it.contains("복합") })
        assertTrue("높은 신뢰도", result.confidence > 0.7f)
    }

    @Test
    fun `대소문자 구분 없이 탐지`() {
        val text = "급전 NEEDED 송금해주세요"
        val result = keywordMatcher.analyze(text)

        assertTrue("대소문자 구분 없이 탐지", result.isScam)
    }

    @Test
    fun `공백 제거 후 탐지`() {
        val text = "급 전 필 요 합 니 다"
        val result = keywordMatcher.analyze(text)

        assertTrue("공백 무시하고 탐지", result.isScam)
    }

    @Test
    fun `URL 패턴 감지`() {
        val text = "http://bit.ly/abc123 클릭해주세요"
        val result = keywordMatcher.analyze(text)

        assertTrue("URL 패턴 감지", result.reasons.any { it.contains("URL") })
    }

    @Test
    fun `주민번호 패턴 고위험`() {
        val text = "주민번호 123456-1234567 확인 부탁드립니다"
        val result = keywordMatcher.analyze(text)

        assertTrue("스캠으로 판정되어야 함", result.isScam)
        assertTrue("주민번호 패턴 감지", result.reasons.any { it.contains("주민") })
        assertTrue("매우 높은 신뢰도", result.confidence > 0.7f)
    }

    @Test
    fun `금액 표시 패턴`() {
        val text = "100,000원 입금해주시면 됩니다"
        val result = keywordMatcher.analyze(text)

        assertTrue("금액 패턴 감지", result.reasons.any { it.contains("금액") })
    }

    @Test
    fun `빈 문자열은 스캠 아님`() {
        val text = ""
        val result = keywordMatcher.analyze(text)

        assertFalse("빈 문자열은 스캠 아님", result.isScam)
        assertEquals(0f, result.confidence, 0.01f)
    }
}
