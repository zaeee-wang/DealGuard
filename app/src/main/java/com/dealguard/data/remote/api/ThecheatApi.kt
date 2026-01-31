package com.dealguard.data.remote.api

import com.dealguard.data.remote.dto.ScamCheckRequest
import com.dealguard.data.remote.dto.ScamCheckResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface ThecheatApi {

    @POST("search")
    suspend fun checkScam(
        @Body request: ScamCheckRequest
    ): ScamCheckResponse
}
