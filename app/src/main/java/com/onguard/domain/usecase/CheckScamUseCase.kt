package com.onguard.domain.usecase

import com.onguard.data.remote.dto.ScamData
import com.onguard.domain.repository.ScamCheckRepository
import javax.inject.Inject

/**
 * 전화번호·계좌번호의 스캠 여부를 확인하는 UseCase.
 *
 * [ScamCheckRepository]를 통해 더치트 API를 호출하여 신고된 스캠 정보를 조회한다.
 *
 * @param repository 더치트 API 연동 저장소
 */
class CheckScamUseCase @Inject constructor(
    private val repository: ScamCheckRepository
) {

    companion object {
        private const val MIN_PHONE_LENGTH = 10
        private const val MAX_PHONE_LENGTH = 11
        private const val MIN_ACCOUNT_LENGTH = 10
        private const val MAX_ACCOUNT_LENGTH = 14
    }

    /**
     * 전화번호 스캠 여부 확인
     *
     * @param phone 확인할 전화번호 (하이픈 포함 가능)
     * @return ScamCheckResult (Safe, Scam, Error, Invalid 중 하나)
     */
    suspend fun checkPhoneNumber(phone: String): ScamCheckResult {
        if (!isValidPhoneNumber(phone)) {
            return ScamCheckResult.Invalid("유효하지 않은 전화번호 형식")
        }

        return repository.checkPhoneNumber(phone).fold(
            onSuccess = { response ->
                if (response.result) {
                    ScamCheckResult.Scam(
                        count = response.count,
                        data = response.data ?: emptyList()
                    )
                } else {
                    ScamCheckResult.Safe
                }
            },
            onFailure = { exception ->
                ScamCheckResult.Error(exception.message ?: "API 호출 실패")
            }
        )
    }

    /**
     * 계좌번호 스캠 여부 확인
     *
     * @param account 확인할 계좌번호 (하이픈 포함 가능)
     * @param bankCode 은행 코드 (선택사항)
     * @return ScamCheckResult (Safe, Scam, Error, Invalid 중 하나)
     */
    suspend fun checkAccountNumber(account: String, bankCode: String? = null): ScamCheckResult {
        if (!isValidAccountNumber(account)) {
            return ScamCheckResult.Invalid("유효하지 않은 계좌번호 형식")
        }

        return repository.checkAccountNumber(account, bankCode).fold(
            onSuccess = { response ->
                if (response.result) {
                    ScamCheckResult.Scam(
                        count = response.count,
                        data = response.data ?: emptyList()
                    )
                } else {
                    ScamCheckResult.Safe
                }
            },
            onFailure = { exception ->
                ScamCheckResult.Error(exception.message ?: "API 호출 실패")
            }
        )
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        val normalized = phone.replace(Regex("[^0-9]"), "")
        return normalized.length in MIN_PHONE_LENGTH..MAX_PHONE_LENGTH
    }

    private fun isValidAccountNumber(account: String): Boolean {
        val normalized = account.replace(Regex("[^0-9]"), "")
        return normalized.length in MIN_ACCOUNT_LENGTH..MAX_ACCOUNT_LENGTH
    }

    /**
     * 스캠 체크 결과를 나타내는 sealed class
     */
    sealed class ScamCheckResult {
        /** 안전한 번호/계좌 */
        data object Safe : ScamCheckResult()

        /** 스캠으로 신고된 번호/계좌 */
        data class Scam(
            val count: Int,
            val data: List<ScamData>
        ) : ScamCheckResult()

        /** API 호출 또는 네트워크 오류 */
        data class Error(val message: String) : ScamCheckResult()

        /** 유효하지 않은 입력 */
        data class Invalid(val reason: String) : ScamCheckResult()
    }
}
