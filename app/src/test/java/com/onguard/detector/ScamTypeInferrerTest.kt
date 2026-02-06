package com.onguard.detector

import com.onguard.domain.model.ScamType
import org.junit.Assert.assertEquals
import org.junit.Test

class ScamTypeInferrerTest {

    @Test
    fun `투자 관련 사유면 INVESTMENT`() {
        assertEquals(
            ScamType.INVESTMENT,
            ScamTypeInferrer.inferScamType(listOf("투자 키워드 발견", "수익 보장"))
        )
        assertEquals(
            ScamType.INVESTMENT,
            ScamTypeInferrer.inferScamType(listOf("코인", "주식"))
        )
    }

    @Test
    fun `입금 거래 관련 사유면 USED_TRADE`() {
        assertEquals(
            ScamType.USED_TRADE,
            ScamTypeInferrer.inferScamType(listOf("입금 요청", "선결제 필요"))
        )
        assertEquals(
            ScamType.USED_TRADE,
            ScamTypeInferrer.inferScamType(listOf("거래", "택배"))
        )
    }

    @Test
    fun `URL 피싱 관련 사유면 PHISHING`() {
        assertEquals(
            ScamType.PHISHING,
            ScamTypeInferrer.inferScamType(listOf("URL 감지", "링크 클릭"))
        )
        assertEquals(
            ScamType.PHISHING,
            ScamTypeInferrer.inferScamType(listOf("피싱 의심"))
        )
    }

    @Test
    fun `사칭 기관 관련 사유면 IMPERSONATION`() {
        assertEquals(
            ScamType.IMPERSONATION,
            ScamTypeInferrer.inferScamType(listOf("사칭", "기관 사칭"))
        )
    }

    @Test
    fun `대출 관련 사유면 LOAN`() {
        assertEquals(
            ScamType.LOAN,
            ScamTypeInferrer.inferScamType(listOf("대출"))
        )
    }

    @Test
    fun `매칭 없으면 UNKNOWN`() {
        assertEquals(
            ScamType.UNKNOWN,
            ScamTypeInferrer.inferScamType(emptyList())
        )
        assertEquals(
            ScamType.UNKNOWN,
            ScamTypeInferrer.inferScamType(listOf("일반 메시지"))
        )
    }
}
