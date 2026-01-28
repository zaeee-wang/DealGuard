package com.dealguard.data.local.dao

import androidx.room.*
import com.dealguard.data.local.entity.ScamAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScamAlertDao {

    @Query("SELECT * FROM scam_alerts ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<ScamAlertEntity>>

    @Query("SELECT * FROM scam_alerts WHERE isDismissed = 0 ORDER BY timestamp DESC")
    fun getActiveAlerts(): Flow<List<ScamAlertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: ScamAlertEntity): Long

    @Update
    suspend fun updateAlert(alert: ScamAlertEntity)

    @Query("UPDATE scam_alerts SET isDismissed = 1 WHERE id = :alertId")
    suspend fun dismissAlert(alertId: Long)

    @Query("DELETE FROM scam_alerts WHERE timestamp < :timestamp")
    suspend fun deleteOldAlerts(timestamp: Long)
}
