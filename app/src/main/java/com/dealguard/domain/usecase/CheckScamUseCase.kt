package com.dealguard.domain.usecase

import com.dealguard.data.remote.dto.ScamCheckResponse
import com.dealguard.domain.repository.ScamCheckRepository
import javax.inject.Inject

class CheckScamUseCase @Inject constructor(
    private val repository: ScamCheckRepository
) {

    suspend fun checkPhoneNumber(phone: String): ScamCheckResult {
        if (!isValidPhoneNumber(phone)) {
            return ScamCheckResult.Invalid("유효하지 않은 전화번호 형식")
        }

        return when (val result = repository.checkPhoneNumber(phone)) {
            is Result.Success -> {
                val response = result.getOrNull()
                if (response != null && response.result) {
                    ScamCheckResult.Scam(
                        count = response.count,
                        data = response.data ?: emptyList()
                    )
                } else {
                    ScamCheckResult.Safe
                }
            }

            is Result.Failure -> {
                ScamCheckResult.Error(result.exceptionOrNull()?.message ?: "API 호출 실패")
            }
        }
    }

    suspend fun checkAccountNumber(account: String, bankCode: String? = null): ScamCheckResult {
        if (!isValidAccountNumber(account)) {
            return ScamCheckResult.Invalid("유효하지 않은 계좌번호 형식")
        }

        return when (val result = repository.checkAccountNumber(account, bankCode)) {
            is Result.Success -> {
                val response = result.getOrNull()
                if (response != null && response.result) {
                    ScamCheckResult.Scam(
                        count = response.count,
                        data = response.data ?: emptyList()
                    )
                } else {
                    ScamCheckResult.Safe
                }
            }

            is Result.Failure -> {
                ScamCheckResult.Error(result.exceptionOrNull()?.message ?: "API 호출 실패")
            }
        }
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        val normalized = phone.replace(Regex("[^0-9]"), "")
        // Korean phone numbers: 10-11 digits
        return normalized.length in 10..11
    }

    private fun isValidAccountNumber(account: String): Boolean {
        val normalized = account.replace(Regex("[^0-9]"), "")
        // Account numbers: typically 10-14 digits
        return normalized.length in 10..14
    }

    sealed class ScamCheckResult {
        object Safe : ScamCheckResult()
        data class Scam(
            val count: Int,
            val data: List<com.dealguard.data.remote.dto.ScamData>
        ) : ScamCheckResult()

        data class Error(val message: String) : ScamCheckResult()
        data class Invalid(val reason: String) : ScamCheckResult()
    }
}
