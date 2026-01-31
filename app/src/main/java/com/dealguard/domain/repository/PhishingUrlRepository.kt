package com.dealguard.domain.repository

import kotlinx.coroutines.flow.Flow

interface PhishingUrlRepository {
    suspend fun isPhishingUrl(url: String): Boolean
    suspend fun loadFromCsv(): Result<Int>
    suspend fun getUrlCount(): Int
    fun getUrlCountFlow(): Flow<Int>
    suspend fun clearAll()
}
