package com.onguard.data.repository

import com.onguard.data.local.dao.ScamAlertDao
import com.onguard.data.local.entity.ScamAlertEntity
import com.onguard.domain.model.ScamAlert
import com.onguard.domain.repository.ScamAlertRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ScamAlertRepository] 구현체.
 *
 * Room [ScamAlertDao]를 통해 [ScamAlertEntity]와 [ScamAlert] 간 변환을 수행한다.
 */
@Singleton
class ScamAlertRepositoryImpl @Inject constructor(
    private val dao: ScamAlertDao
) : ScamAlertRepository {

    override fun getAllAlerts(): Flow<List<ScamAlert>> {
        return dao.getAllAlerts().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAlertById(id: Long): ScamAlert? {
        return dao.getAlertById(id)?.toDomain()
    }

    override suspend fun insertAlert(alert: ScamAlert): Long {
        val entity = ScamAlertEntity(
            id = 0, // Auto-generate
            message = alert.message,
            confidence = alert.confidence,
            sourceApp = alert.sourceApp,
            detectedKeywords = alert.detectedKeywords,
            reasons = alert.reasons,
            timestamp = alert.timestamp,
            isDismissed = alert.isDismissed
        )
        return dao.insertAlert(entity)
    }

    override suspend fun deleteAlert(id: Long) {
        dao.deleteAlert(id)
    }

    override suspend fun deleteOldAlerts(daysAgo: Int) {
        val cutoffDate = Date(System.currentTimeMillis() - (daysAgo * 24 * 60 * 60 * 1000L))
        dao.deleteOldAlerts(cutoffDate)
    }

    override suspend fun markAsDismissed(id: Long) {
        dao.markAsDismissed(id)
    }

    private fun ScamAlertEntity.toDomain(): ScamAlert {
        return ScamAlert(
            id = this.id,
            message = this.message,
            confidence = this.confidence,
            sourceApp = this.sourceApp,
            detectedKeywords = this.detectedKeywords,
            reasons = this.reasons,
            timestamp = this.timestamp,
            isDismissed = this.isDismissed
        )
    }
}
