package com.dealguard.domain.repository

import com.dealguard.domain.model.ScamAlert
import kotlinx.coroutines.flow.Flow

interface ScamAlertRepository {
    fun getAllAlerts(): Flow<List<ScamAlert>>
    suspend fun getAlertById(id: Long): ScamAlert?
    suspend fun insertAlert(alert: ScamAlert): Long
    suspend fun deleteAlert(id: Long)
    suspend fun deleteOldAlerts(daysAgo: Int = 7)
    suspend fun markAsDismissed(id: Long)
}
