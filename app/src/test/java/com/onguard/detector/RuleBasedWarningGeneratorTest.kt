package com.onguard.detector

import com.onguard.domain.model.ScamType
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedWarningGeneratorTest {

    @Test
    fun `경고 메시지에 위험도 퍼센트 포함`() {
        val msg = RuleBasedWarningGenerator.generateWarning(ScamType.PHISHING, 0.75f)
        assertTrue("75% 포함", msg.contains("75"))
        assertTrue("피싱 관련 문구", msg.contains("피싱") || msg.contains("링크"))
    }

    @Test
    fun `투자 사기 타입 경고`() {
        val msg = RuleBasedWarningGenerator.generateWarning(ScamType.INVESTMENT, 0.5f)
        assertTrue(msg.contains("50"))
        assertTrue(msg.contains("투자") || msg.contains("고수익"))
    }

    @Test
    fun `중고거래 사기 타입 경고`() {
        val msg = RuleBasedWarningGenerator.generateWarning(ScamType.USED_TRADE, 0.6f)
        assertTrue(msg.contains("60"))
        assertTrue(msg.contains("중고") || msg.contains("선입금"))
    }

    @Test
    fun `UNKNOWN 타입도 기본 경고 반환`() {
        val msg = RuleBasedWarningGenerator.generateWarning(ScamType.UNKNOWN, 0.3f)
        assertTrue(msg.contains("30"))
        assertTrue(msg.contains("의심") || msg.contains("주의"))
    }
}
