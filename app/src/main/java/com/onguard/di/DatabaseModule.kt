package com.onguard.di

import android.content.Context
import androidx.room.Room
import com.onguard.data.local.AppDatabase
import com.onguard.data.local.dao.PhishingUrlDao
import com.onguard.data.local.dao.ScamAlertDao
import com.onguard.data.repository.PhishingUrlRepositoryImpl
import com.onguard.data.repository.ScamAlertRepositoryImpl
import com.onguard.domain.repository.PhishingUrlRepository
import com.onguard.domain.repository.ScamAlertRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "onguard_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideScamAlertDao(database: AppDatabase): ScamAlertDao {
        return database.scamAlertDao()
    }

    @Provides
    @Singleton
    fun providePhishingUrlDao(database: AppDatabase): PhishingUrlDao {
        return database.phishingUrlDao()
    }

    @Provides
    @Singleton
    fun provideScamAlertRepository(dao: ScamAlertDao): ScamAlertRepository {
        return ScamAlertRepositoryImpl(dao)
    }

    @Provides
    @Singleton
    fun providePhishingUrlRepository(
        @ApplicationContext context: Context,
        dao: PhishingUrlDao
    ): PhishingUrlRepository {
        return PhishingUrlRepositoryImpl(context, dao)
    }
}
