package com.onguard.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.onguard.data.local.dao.PhishingUrlDao
import com.onguard.data.local.dao.ScamAlertDao
import com.onguard.data.local.entity.PhishingUrlEntity
import com.onguard.data.local.entity.ScamAlertEntity

@Database(
    entities = [
        ScamAlertEntity::class,
        PhishingUrlEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scamAlertDao(): ScamAlertDao
    abstract fun phishingUrlDao(): PhishingUrlDao
}
