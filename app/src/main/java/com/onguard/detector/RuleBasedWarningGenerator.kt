package com.onguard.detector

import com.onguard.domain.model.ScamType

/**
 * Rule-based 전용 경고 메시지를 생성한다.
 *
 * 순수 함수로 분리하여 단위 테스트가 가능하다.
 */
object RuleBasedWarningGenerator {

    fun generateWarning(scamType: ScamType, confidence: Float): String {
        val confidencePercent = (confidence * 100).toInt()
        return when (scamType) {
            ScamType.INVESTMENT ->
                "이 메시지는 투자 사기로 의심됩니다 (위험도 $confidencePercent%). 고수익을 보장하는 투자는 대부분 사기입니다."

            ScamType.USED_TRADE ->
                "중고거래 사기가 의심됩니다 (위험도 $confidencePercent%). 선입금을 요구하면 직거래로 진행하세요."

            ScamType.PHISHING ->
                "피싱 링크가 포함되어 있습니다 (위험도 $confidencePercent%). 의심스러운 링크를 클릭하지 마세요."

            ScamType.IMPERSONATION ->
                "사칭 사기가 의심됩니다 (위험도 $confidencePercent%). 공식 채널을 통해 확인하세요."

            ScamType.LOAN ->
                "대출 사기가 의심됩니다 (위험도 $confidencePercent%). 선수수료 요구는 불법입니다."

            else ->
                "사기 의심 메시지입니다 (위험도 $confidencePercent%). 주의하세요."
        }
    }
}
