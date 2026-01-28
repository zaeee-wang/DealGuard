package com.dealguard.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scam_alerts")
data class ScamAlertEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val sourceApp: String,
    val isScam: Boolean,
    val confidence: Float,
    val reasons: List<String>,
    val detectedKeywords: List<String>,
    val timestamp: Long,
    val isDismissed: Boolean = false
)
