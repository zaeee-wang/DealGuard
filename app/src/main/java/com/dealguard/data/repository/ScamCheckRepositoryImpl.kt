package com.dealguard.data.repository

import android.util.Log
import com.dealguard.data.remote.api.ThecheatApi
import com.dealguard.data.remote.dto.ScamCheckRequest
import com.dealguard.data.remote.dto.ScamCheckResponse
import com.dealguard.domain.repository.ScamCheckRepository
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScamCheckRepositoryImpl @Inject constructor(
    private val api: ThecheatApi
) : ScamCheckRepository {

    companion object {
        private const val TAG = "ScamCheckRepository"
        private const val API_TIMEOUT_MS = 3000L
    }

    override suspend fun checkPhoneNumber(phone: String): Result<ScamCheckResponse> {
        return try {
            withTimeout(API_TIMEOUT_MS) {
                val request = ScamCheckRequest(
                    keywordType = "phone",
                    keyword = normalizePhoneNumber(phone),
                    addInfo = null
                )

                val response = api.checkScam(request)
                Log.d(TAG, "Phone check result: ${response.result}, count: ${response.count}")

                Result.success(response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check phone number", e)
            Result.failure(e)
        }
    }

    override suspend fun checkAccountNumber(account: String, bankCode: String?): Result<ScamCheckResponse> {
        return try {
            withTimeout(API_TIMEOUT_MS) {
                val request = ScamCheckRequest(
                    keywordType = "account",
                    keyword = normalizeAccountNumber(account),
                    addInfo = bankCode
                )

                val response = api.checkScam(request)
                Log.d(TAG, "Account check result: ${response.result}, count: ${response.count}")

                Result.success(response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check account number", e)
            Result.failure(e)
        }
    }

    private fun normalizePhoneNumber(phone: String): String {
        // Remove all non-digit characters
        return phone.replace(Regex("[^0-9]"), "")
    }

    private fun normalizeAccountNumber(account: String): String {
        // Remove all non-digit characters
        return account.replace(Regex("[^0-9]"), "")
    }
}
