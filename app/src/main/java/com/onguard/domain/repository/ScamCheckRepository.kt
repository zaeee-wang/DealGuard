package com.onguard.domain.repository

import com.onguard.data.remote.dto.ScamCheckResponse

interface ScamCheckRepository {
    suspend fun checkPhoneNumber(phone: String): Result<ScamCheckResponse>
    suspend fun checkAccountNumber(account: String, bankCode: String? = null): Result<ScamCheckResponse>
}
