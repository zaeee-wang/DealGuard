package com.dealguard.data.local.dao

import androidx.room.*
import com.dealguard.data.local.entity.PhishingUrlEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhishingUrlDao {

    @Query("SELECT EXISTS(SELECT 1 FROM phishing_urls WHERE url LIKE '%' || :domain || '%' LIMIT 1)")
    suspend fun isPhishingUrl(domain: String): Boolean

    @Query("SELECT * FROM phishing_urls WHERE url LIKE '%' || :keyword || '%' LIMIT 10")
    suspend fun searchUrls(keyword: String): List<PhishingUrlEntity>

    @Query("SELECT COUNT(*) FROM phishing_urls")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM phishing_urls")
    fun getCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUrl(url: PhishingUrlEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUrls(urls: List<PhishingUrlEntity>)

    @Query("DELETE FROM phishing_urls")
    suspend fun deleteAll()

    @Query("DELETE FROM phishing_urls WHERE dateAdded < :cutoffDate")
    suspend fun deleteOldUrls(cutoffDate: Long)
}
