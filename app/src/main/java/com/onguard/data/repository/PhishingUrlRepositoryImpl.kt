package com.onguard.data.repository

import android.content.Context
import android.util.Log
import com.onguard.data.local.dao.PhishingUrlDao
import com.onguard.data.local.entity.PhishingUrlEntity
import com.onguard.domain.repository.PhishingUrlRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhishingUrlRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PhishingUrlDao
) : PhishingUrlRepository {

    companion object {
        private const val TAG = "PhishingUrlRepository"
        private const val CSV_FILE_PATH = "kisa/KISA.csv"
        private const val BATCH_SIZE = 500
    }

    override suspend fun isPhishingUrl(url: String): Boolean {
        return try {
            val domain = extractDomain(url)
            dao.isPhishingUrl(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking phishing URL", e)
            false
        }
    }

    override suspend fun loadFromCsv(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(CSV_FILE_PATH)
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

            var count = 0
            val batch = mutableListOf<PhishingUrlEntity>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            // Skip header line
            reader.readLine()

            reader.useLines { lines ->
                lines.forEach { line ->
                    try {
                        val parsed = parseCsvLine(line, dateFormat)
                        if (parsed != null) {
                            batch.add(parsed)
                            count++

                            if (batch.size >= BATCH_SIZE) {
                                dao.insertUrls(batch.toList())
                                batch.clear()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse line: $line", e)
                    }
                }
            }

            // Insert remaining batch
            if (batch.isNotEmpty()) {
                dao.insertUrls(batch)
            }

            Log.i(TAG, "Loaded $count phishing URLs from CSV")
            Result.success(count)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load CSV", e)
            Result.failure(e)
        }
    }

    override suspend fun getUrlCount(): Int {
        return dao.getCount()
    }

    override fun getUrlCountFlow(): Flow<Int> {
        return dao.getCountFlow()
    }

    override suspend fun clearAll() {
        dao.deleteAll()
    }

    private fun parseCsvLine(line: String, dateFormat: SimpleDateFormat): PhishingUrlEntity? {
        if (line.isBlank()) return null

        // CSV format: DATE,URL or URL,DATE (depending on the actual file format)
        val parts = line.split(",").map { it.trim() }
        if (parts.size < 2) return null

        // Try to detect which column is URL and which is date
        val (dateStr, url) = if (parts[0].matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            parts[0] to parts[1]
        } else {
            parts[1] to parts[0]
        }

        val date = try {
            dateFormat.parse(dateStr) ?: Date()
        } catch (e: Exception) {
            Date()
        }

        // Clean up URL
        val cleanUrl = url
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/")
            .lowercase()

        if (cleanUrl.isBlank() || cleanUrl.length < 4) return null

        return PhishingUrlEntity(
            url = cleanUrl,
            dateAdded = date,
            source = "KISA"
        )
    }

    private fun extractDomain(url: String): String {
        return url
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore(":")
            .lowercase()
    }
}
