package com.onguard.domain.repository

import com.onguard.domain.model.ScamAlert
import kotlinx.coroutines.flow.Flow

/**
 * 스캠 알림 저장소 인터페이스.
 *
 * Room DB를 통해 [ScamAlert] 목록을 조회·추가·삭제·무시 처리한다.
 */
interface ScamAlertRepository {

    /** 전체 알림 목록을 Flow로 반환한다. */
    fun getAllAlerts(): Flow<List<ScamAlert>>

    /**
     * ID로 단일 알림을 조회한다.
     * @param id 알림 ID
     * @return [ScamAlert] 또는 null
     */
    suspend fun getAlertById(id: Long): ScamAlert?

    /**
     * 알림을 저장한다.
     * @param alert 저장할 알림
     * @return 생성된 row ID
     */
    suspend fun insertAlert(alert: ScamAlert): Long

    /** ID에 해당하는 알림을 삭제한다. */
    suspend fun deleteAlert(id: Long)

    /**
     * 지정 일수 이전 알림을 삭제한다.
     * @param daysAgo 오늘 기준 며칠 이전 (기본 7일)
     */
    suspend fun deleteOldAlerts(daysAgo: Int = 7)

    /** ID에 해당하는 알림을 "무시됨"으로 표시한다. */
    suspend fun markAsDismissed(id: Long)
}
