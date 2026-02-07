package com.onguard.detector

import android.util.Log
import com.onguard.domain.model.AccountAnalysisResult
import com.onguard.domain.repository.PoliceFraudRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 계좌번호 위험도 분석기
 *
 * 텍스트에서 계좌번호를 추출한 뒤 경찰청 사기계좌 DB를 조회하여 위험도를 산출합니다.
 * API 실패 시에도 로컬 패턴 분석으로 기본 위험도를 제공합니다 (graceful degradation).
 *
 * @param policeFraudRepository 경찰청 사기계좌 조회 Repository
 */
@Singleton
class AccountAnalyzer @Inject constructor(
    private val policeFraudRepository: PoliceFraudRepository
) {

    companion object {
        private const val TAG = "AccountAnalyzer"

        // 위험도 점수 상수
        /** 경찰청 DB 등록 계좌 (사기 신고 3건 이상) */
        private const val SCORE_DB_REGISTERED = 0.95f
        /** 다수 신고 (5건 이상) 추가 가중치 */
        private const val SCORE_MULTIPLE_REPORTS = 0.3f
        // REMOVED: SCORE_ACCOUNT_PRESENT - 패턴 감지만으로 위험도 추가하지 않음
        // API에서 사기 계좌로 확인된 경우에만 위험도 반영
        /** 경찰청 API 유의미 신고 기준 (3건 이상) */
        private const val FRAUD_THRESHOLD = 3
    }

    /**
     * 한국 은행 계좌번호 패턴
     *
     * KeywordMatcher.kt의 패턴을 재사용
     */
    private val accountPatterns = listOf(
        // 3단 형식: 3~4자리 - 2~6자리 - 4~7자리 (신한, 우리, 하나 등)
        Regex("\\d{3,4}-\\d{2,6}-\\d{4,7}"),
        // 국민은행 구형식: 6-2-6
        Regex("\\d{6}-\\d{2}-\\d{6}"),
        // 농협/새마을금고 4단 형식: 3-4-4-2
        Regex("\\d{3}-\\d{4}-\\d{4}-\\d{2}"),
        // 연속 숫자 (하이픈 없는 계좌번호, 10-14자리)
        Regex("(?<!\\d)\\d{10,14}(?!\\d)")
    )

    /** 전화번호로 간주되어 제외할 패턴 */
    private val phoneExclusionPatterns = listOf(
        Regex("^01[016789]"),  // 휴대폰 (010, 011, 016, 017, 018, 019)
        Regex("^02"),          // 서울
        Regex("^0[3-6][0-9]")  // 지역번호 (031~064)
    )

    /**
     * 텍스트에서 계좌번호를 추출하고 위험도를 분석합니다.
     *
     * @param text 분석할 채팅/메시지 텍스트
     * @return AccountAnalysisResult 추출 계좌, 사기 계좌, 위험도 등
     */
    suspend fun analyze(text: String): AccountAnalysisResult {
        val extractedAccounts = extractAccountNumbers(text)

        if (extractedAccounts.isEmpty()) {
            return AccountAnalysisResult.EMPTY
        }

        val fraudAccounts = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var riskScore = 0f
        var totalFraudCount = 0

        extractedAccounts.forEach { account ->
            val normalizedAccount = normalizeAccount(account)

            // 계좌번호 유효성 검증 (10-14자리)
            if (normalizedAccount.length !in 10..14) {
                Log.d(TAG, "Invalid account length: ${normalizedAccount.length}")
                return@forEach
            }

            // 경찰청 사기계좌 DB 조회
            // 패턴 감지만으로는 위험도 추가하지 않음 - API에서 확인된 경우에만 반영
            // - count >= 3: 3건 이상 신고 = 유의미한 위험
            // - count >= 5: 다수 신고 = 추가 가중치
            try {
                val result = policeFraudRepository.searchAccount(normalizedAccount)
                result.getOrNull()?.let { response ->
                    val fraudValue = response.value?.firstOrNull()
                    val fraudCount = fraudValue?.fraudCount ?: 0

                    if (fraudCount >= FRAUD_THRESHOLD) {
                        // 3건 이상 신고: 사기 계좌로 판정
                        fraudAccounts.add(account)
                        totalFraudCount += fraudCount
                        riskScore += SCORE_DB_REGISTERED
                        reasons.add("경찰청 사기신고 계좌: ${maskAccount(account)} (${fraudCount}건)")
                        Log.w(TAG, "Fraud account detected: ${maskAccount(account)} (count=$fraudCount)")

                        // 다수 신고 (5건 이상): 추가 가중치
                        if (fraudCount >= 5) {
                            riskScore += SCORE_MULTIPLE_REPORTS
                            reasons.add("다수 사기 신고 이력 (${fraudCount}건)")
                        }
                    } else if (fraudCount > 0) {
                        // 1-2건 신고: 로그만 기록 (아직 유의미하지 않음)
                        Log.d(TAG, "Account has minor reports: ${maskAccount(account)} (count=$fraudCount)")
                    } else {
                        Log.d(TAG, "Account clean: ${maskAccount(account)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check account in Police DB: ${maskAccount(account)}", e)
            }
        }

        return AccountAnalysisResult(
            extractedAccounts = extractedAccounts,
            fraudAccounts = fraudAccounts,
            reasons = reasons.distinct(),
            riskScore = riskScore.coerceIn(0f, 1f),
            totalFraudCount = totalFraudCount
        )
    }

    /**
     * 텍스트에서 계좌번호 추출
     */
    private fun extractAccountNumbers(text: String): List<String> {
        val accounts = mutableSetOf<String>()

        accountPatterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val value = match.value
                val normalized = value.replace("-", "")

                // 전화번호와 구분: 010, 02, 031 등으로 시작하면 제외
                val isPhoneNumber = phoneExclusionPatterns.any { it.containsMatchIn(normalized) }
                if (!isPhoneNumber) {
                    accounts.add(value)
                }
            }
        }

        return accounts.toList()
    }

    /**
     * 계좌번호 정규화 (하이픈, 공백 제거)
     */
    private fun normalizeAccount(account: String): String {
        return account.replace(Regex("[^0-9]"), "")
    }

    /**
     * 로그용 계좌번호 마스킹 (앞 4자리 + **** + 뒤 4자리)
     */
    private fun maskAccount(account: String): String {
        val normalized = normalizeAccount(account)
        return if (normalized.length > 8) {
            "${normalized.take(4)}****${normalized.takeLast(4)}"
        } else {
            "****"
        }
    }
}
