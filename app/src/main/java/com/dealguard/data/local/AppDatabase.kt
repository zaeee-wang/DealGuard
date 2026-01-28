package com.dealguard.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dealguard.data.local.dao.ScamAlertDao
import com.dealguard.data.local.entity.ScamAlertEntity

@Database(
    entities = [ScamAlertEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scamAlertDao(): ScamAlertDao
}
