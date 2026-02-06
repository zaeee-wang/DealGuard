package com.onguard.domain.usecase

/**
 * 전화번호·계좌번호 형식 검증 계약.
 *
 * CheckScamUseCase에서 사용하며, 테스트 시 mock 또는 다른 규칙 주입이 가능하다.
 */
interface PhoneAccountValidator {

    fun isValidPhoneNumber(phone: String): Boolean
    fun isValidAccountNumber(account: String): Boolean

    companion object {
        private const val MIN_PHONE_LENGTH = 10
        private const val MAX_PHONE_LENGTH = 11
        private const val MIN_ACCOUNT_LENGTH = 10
        private const val MAX_ACCOUNT_LENGTH = 14

        val Default = object : PhoneAccountValidator {
            override fun isValidPhoneNumber(phone: String): Boolean {
                val normalized = phone.replace(Regex("[^0-9]"), "")
                return normalized.length in MIN_PHONE_LENGTH..MAX_PHONE_LENGTH
            }

            override fun isValidAccountNumber(account: String): Boolean {
                val normalized = account.replace(Regex("[^0-9]"), "")
                return normalized.length in MIN_ACCOUNT_LENGTH..MAX_ACCOUNT_LENGTH
            }
        }
    }
}
