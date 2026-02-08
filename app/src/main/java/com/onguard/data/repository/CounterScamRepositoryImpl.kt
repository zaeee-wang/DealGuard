package com.onguard.data.repository

import android.util.Log
import android.util.LruCache
import com.onguard.data.remote.api.CounterScam112Api
import com.onguard.data.remote.dto.CounterScamRequest
import com.onguard.data.remote.dto.CounterScamResponse
import com.onguard.domain.repository.CounterScamRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counter Scam 112 Repository 구현체
 *
 * LRU 캐시를 포함하여 중복 API 호출을 방지하고,
 * API 실패 시 graceful degradation을 지원합니다.
 *
 * 세션 관리:
 * - 첫 API 호출 시 자동으로 세션 초기화 (GET 요청으로 쿠키 획득)
 * - CookieJar가 세션 쿠키를 자동 저장/전송
 * - 세션 만료 시 자동 재초기화
 */
@Singleton
class CounterScamRepositoryImpl @Inject constructor(
    private val api: CounterScam112Api
) : CounterScamRepository {

    companion object {
        private const val TAG = "CounterScamRepository"
        private const val API_TIMEOUT_MS = 10000L
        private const val CACHE_MAX_SIZE = 100
        private const val CACHE_TTL_MS = 15 * 60 * 1000L  // 15분
        private const val SESSION_TTL_MS = 30 * 60 * 1000L  // 30분
    }

    /** 캐시 엔트리: 응답 + 타임스탬프 */
    private data class CacheEntry(
        val response: CounterScamResponse,
        val timestamp: Long
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    /** LRU 캐시 (전화번호 → 응답) */
    private val cache = LruCache<String, CacheEntry>(CACHE_MAX_SIZE)

    /** 캐시 동시 접근 제어 */
    private val cacheMutex = Mutex()

    /** 세션 초기화 여부 */
    @Volatile
    private var sessionInitialized = false
    private var sessionInitTime = 0L
    private val sessionMutex = Mutex()

    override suspend fun searchPhone(phoneNumber: String): Result<CounterScamResponse> {
        val normalizedPhone = normalizePhoneNumber(phoneNumber)

        if (normalizedPhone.isEmpty()) {
            Log.w(TAG, "Invalid phone number: $phoneNumber")
            return Result.failure(IllegalArgumentException("Invalid phone number"))
        }

        // 1. 캐시 확인
        cacheMutex.withLock {
            cache.get(normalizedPhone)?.let { entry ->
                if (!entry.isExpired()) {
                    Log.d(TAG, "Cache hit for $normalizedPhone")
                    return Result.success(entry.response)
                } else {
                    cache.remove(normalizedPhone)
                    Log.d(TAG, "Cache expired for $normalizedPhone")
                }
            }
        }

        // 2. 세션 확보
        ensureSession()

        // 3. API 호출
        return try {
            withTimeout(API_TIMEOUT_MS) {
                Log.d(TAG, "API call for $normalizedPhone")
                val request = CounterScamRequest(telNum = normalizedPhone)
                val response = api.searchPhone(request)

                // 캐시 저장
                cacheMutex.withLock {
                    cache.put(normalizedPhone, CacheEntry(response, System.currentTimeMillis()))
                }

                Log.i(TAG, "Phone lookup result: total=${response.totalCount}, " +
                        "voice=${response.voiceCount}, sms=${response.smsCount}")

                Result.success(response)
            }
        } catch (e: Exception) {
            Log.w(TAG, "API call failed for $normalizedPhone: ${e.message}")
            // 세션 만료일 수 있으므로 재초기화 플래그 설정 (thread-safe)
            invalidateSession()
            // API 실패를 명시적으로 반환 - 호출자가 try-catch로 처리
            Result.failure(e)
        }
    }

    /**
     * 세션 무효화 (thread-safe)
     *
     * API 호출 실패 시 세션 만료일 수 있으므로 세션을 무효화합니다.
     */
    private suspend fun invalidateSession() {
        sessionMutex.withLock {
            sessionInitialized = false
        }
    }

    /**
     * 세션 초기화 확보
     *
     * 세션이 초기화되지 않았거나 만료된 경우 페이지를 로드하여
     * 세션 쿠키를 획득합니다. CookieJar가 자동으로 쿠키를 저장합니다.
     */
    private suspend fun ensureSession() {
        val now = System.currentTimeMillis()
        val sessionExpired = now - sessionInitTime > SESSION_TTL_MS

        if (sessionInitialized && !sessionExpired) {
            return
        }

        sessionMutex.withLock {
            // Double-check after acquiring lock
            if (sessionInitialized && !sessionExpired) {
                return
            }

            try {
                Log.d(TAG, "Initializing session...")
                val response = api.initSession()

                if (response.isSuccessful) {
                    sessionInitialized = true
                    sessionInitTime = now
                    Log.i(TAG, "Session initialized successfully")
                } else {
                    Log.w(TAG, "Session init failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session init error: ${e.message}")
            }
        }
    }

    override fun clearCache() {
        cache.evictAll()
        Log.d(TAG, "Cache cleared")
    }

    override fun getCacheSize(): Int = cache.size()

    /**
     * 전화번호 정규화 (하이픈 및 공백 제거)
     */
    private fun normalizePhoneNumber(phone: String): String {
        return phone.replace(Regex("[^0-9]"), "")
    }
}
