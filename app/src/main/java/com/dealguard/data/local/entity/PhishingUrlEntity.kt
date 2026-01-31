package com.dealguard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "phishing_urls",
    indices = [Index(value = ["url"], unique = true)]
)
data class PhishingUrlEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val dateAdded: Date,
    val source: String = "KISA"
)
