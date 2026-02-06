package com.onguard.data.local.dao

import androidx.room.*
import com.onguard.data.local.entity.ScamAlertEntity
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface ScamAlertDao {

    @Query("SELECT * FROM scam_alerts ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<ScamAlertEntity>>

    @Query("SELECT * FROM scam_alerts WHERE id = :id")
    suspend fun getAlertById(id: Long): ScamAlertEntity?

    @Query("SELECT * FROM scam_alerts WHERE isDismissed = 0 ORDER BY timestamp DESC")
    fun getActiveAlerts(): Flow<List<ScamAlertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: ScamAlertEntity): Long

    @Update
    suspend fun updateAlert(alert: ScamAlertEntity)

    @Query("UPDATE scam_alerts SET isDismissed = 1 WHERE id = :alertId")
    suspend fun markAsDismissed(alertId: Long)

    @Query("DELETE FROM scam_alerts WHERE id = :id")
    suspend fun deleteAlert(id: Long)

    @Query("DELETE FROM scam_alerts WHERE timestamp < :cutoffDate")
    suspend fun deleteOldAlerts(cutoffDate: Date)
}
