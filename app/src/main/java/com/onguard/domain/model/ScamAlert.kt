package com.onguard.domain.model

import java.util.Date

/**
 * 스캠 경고 알림 도메인 모델.
 *
 * 탐지된 스캠 메시지에 대한 정보를 담으며, Room DB에 저장·조회 시 사용된다.
 *
 * @param id DB 기본키 (0이면 insert 시 자동 생성)
 * @param message 경고 요약 메시지 (LLM 생성 문구 또는 reasons 조합)
 * @param confidence 위험도 (0.0 ~ 1.0)
 * @param sourceApp 출처 앱 패키지명 (예: com.kakao.talk)
 * @param detectedKeywords 탐지된 위험 키워드 목록
 * @param reasons 탐지 사유 목록
 * @param timestamp 알림 발생 시각
 * @param isDismissed 사용자 무시 여부
 */
data class ScamAlert(
    val id: Long = 0,
    val message: String,
    val confidence: Float,
    val sourceApp: String,
    val detectedKeywords: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val timestamp: Date = Date(),
    val isDismissed: Boolean = false
)
