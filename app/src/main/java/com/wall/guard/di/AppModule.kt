package com.wall.guard.di

import android.content.Context
import androidx.room.Room
import com.wall.guard.data.db.AppDatabase
import com.wall.guard.data.db.RuleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "wallguard.db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideRuleDao(database: AppDatabase): RuleDao {
        return database.ruleDao()
    }
}
