package com.onguard.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "scam_alerts")
data class ScamAlertEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val message: String,
    val confidence: Float,
    val sourceApp: String,
    val detectedKeywords: List<String>,
    val reasons: List<String>,
    val timestamp: Date,
    val isDismissed: Boolean = false
)
