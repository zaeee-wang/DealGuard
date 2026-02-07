package com.onguard.util

/**
 * 개인정보(PII) 마스킹 유틸리티.
 *
 * LLM 프롬프트 전달 전 민감 정보를 마스킹하여
 * AccessibilityService에서 수집한 텍스트가 외부로 유출되지 않도록 보호.
 *
 * ## 마스킹 대상
 * - 전화번호: 부분 마스킹 (010-****-5678) - 대역 정보 유지
 * - 계좌번호: 완전 마스킹 ([계좌번호])
 * - 주민등록번호: 완전 마스킹 ([주민번호])
 * - 여권번호: 완전 마스킹 ([여권번호])
 *
 * ## 보안 고려사항
 * - AccessibilityService 데이터는 외부 서버 전송 금지 (security-reviewer.md)
 * - LLM 호출 전 반드시 PII 마스킹 적용
 * - 전화번호 대역(070, 050)은 스캠 판단에 유용하므로 부분 보존
 */
object PiiMasker {

    // ========== 전화번호 패턴 (PhoneAnalyzer 참조) ==========
    // 부분 마스킹: 앞 3-4자리 + 뒷 4자리 유지
    private val phonePatterns = listOf(
        // 휴대폰 (010, 011, 016, 017, 018, 019)
        Regex("01[016789]-?\\d{3,4}-?\\d{4}"),
        // 서울 지역번호 (02)
        Regex("02-?\\d{3,4}-?\\d{4}"),
        // 경기/인천/지방 지역번호 (031~064)
        Regex("0[3-6][0-9]-?\\d{3,4}-?\\d{4}"),
        // 대표번호 (15XX, 16XX, 18XX, 19XX)
        Regex("1[5689][0-9]{2}-?\\d{4}"),
        // 인터넷전화 (070)
        Regex("070-?\\d{3,4}-?\\d{4}"),
        // 050 번호 (발신번호 표시제한)
        Regex("050[0-9]-?\\d{3,4}-?\\d{4}"),
        // 국제번호 형식 (+82)
        Regex("\\+82-?1?0?-?\\d{4}-?\\d{4}")
    )

    // ========== 계좌번호 패턴 (KeywordMatcher 참조) ==========
    // 완전 마스킹: [계좌번호]
    private val accountPatterns = listOf(
        // 일반 계좌 형식: 3-4자리 - 2-6자리 - 4-7자리
        Regex("\\d{3,4}-\\d{2,6}-\\d{4,7}"),
        // 기업은행/농협 형식: 6자리-2자리-6자리
        Regex("\\d{6}-\\d{2}-\\d{6}"),
        // 카드번호 형식: 4-4-4-4 (16자리)
        Regex("\\d{4}-\\d{4}-\\d{4}-\\d{4}")
    )

    // ========== 주민등록번호 패턴 ==========
    // 완전 마스킹: [주민번호]
    // 6자리 생년월일 + 1~4로 시작하는 7자리
    private val ssnPattern = Regex("\\d{6}-?[1-4]\\d{6}")

    // ========== 여권번호 패턴 ==========
    // 완전 마스킹: [여권번호]
    // 알파벳 1-2자 + 숫자 7-8자리
    private val passportPattern = Regex("[A-Z]{1,2}\\d{7,8}")

    /**
     * 텍스트에서 PII를 마스킹한다.
     *
     * @param text 원본 텍스트
     * @return PII가 마스킹된 텍스트
     */
    fun mask(text: String): String {
        if (text.isBlank()) return text

        var result = text

        // 1. 주민번호 먼저 (6자리-7자리 형식이 계좌번호와 겹칠 수 있음)
        result = ssnPattern.replace(result, "[주민번호]")

        // 2. 여권번호
        result = passportPattern.replace(result, "[여권번호]")

        // 3. 계좌번호
        accountPatterns.forEach { pattern ->
            result = pattern.replace(result, "[계좌번호]")
        }

        // 4. 전화번호 (부분 마스킹 - 대역 정보 유지)
        phonePatterns.forEach { pattern ->
            result = pattern.replace(result) { matchResult ->
                maskPhoneNumber(matchResult.value)
            }
        }

        return result
    }

    /**
     * 전화번호를 부분 마스킹한다.
     *
     * 대역 정보(070, 050 등)는 스캠 판단에 유용하므로 앞 3-4자리 유지.
     * 중간 번호는 마스킹하고 뒷 4자리는 유지.
     *
     * 예:
     * - 010-1234-5678 → 010-****-5678
     * - 070-1234-5678 → 070-****-5678
     * - 01012345678 → 010****5678
     * - 1588-1234 → 158-****-1234
     *
     * @param phone 원본 전화번호
     * @return 부분 마스킹된 전화번호
     */
    private fun maskPhoneNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }

        // 최소 7자리 이상이어야 전화번호로 인식
        if (digits.replace("+", "").length < 7) return "[전화번호]"

        val hasHyphen = phone.contains("-")

        return when {
            // 국제번호 (+82)
            digits.startsWith("+82") -> {
                val suffix = digits.takeLast(4)
                if (hasHyphen) "+82-****-$suffix" else "+82****$suffix"
            }

            // 대표번호 (1588, 1566 등 8자리)
            digits.length == 8 && digits.startsWith("1") -> {
                val prefix = digits.take(4)
                val suffix = digits.takeLast(4)
                if (hasHyphen) "$prefix-****" else "${prefix}****"
            }

            // 일반 전화번호
            else -> {
                val prefix = when {
                    digits.startsWith("02") -> digits.take(2)
                    digits.length >= 3 -> digits.take(3)
                    else -> digits
                }
                val suffix = digits.takeLast(4)

                if (hasHyphen) "$prefix-****-$suffix" else "$prefix****$suffix"
            }
        }
    }
}
