package com.lethio.macros.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lethio.macros.core.SystemTimeProvider
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.food.FoodRepositoryImpl
import com.lethio.macros.data.user.CustomFoodDao
import com.lethio.macros.data.user.DailyLogDao
import com.lethio.macros.data.user.FavoriteDao
import com.lethio.macros.data.user.GoalsDao
import com.lethio.macros.data.user.GoalsRepositoryImpl
import com.lethio.macros.data.user.LogRepositoryImpl
import com.lethio.macros.data.user.ProfileDao
import com.lethio.macros.data.user.ProfileRepositoryImpl
import com.lethio.macros.data.user.UserDatabase
import com.lethio.macros.data.user.WeightDao
import com.lethio.macros.data.user.WeightRepositoryImpl
import com.lethio.macros.domain.repository.FoodRepository
import com.lethio.macros.domain.repository.GoalsRepository
import com.lethio.macros.domain.repository.LogRepository
import com.lethio.macros.domain.repository.ProfileRepository
import com.lethio.macros.domain.repository.WeightRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideUserDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME)

            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)

            .addMigrations(*UserDatabase.ALL_MIGRATIONS)
            .build()

    @Provides fun provideDailyLogDao(db: UserDatabase): DailyLogDao = db.dailyLogDao()
    @Provides fun provideGoalsDao(db: UserDatabase): GoalsDao = db.goalsDao()
    @Provides fun provideProfileDao(db: UserDatabase): ProfileDao = db.profileDao()
    @Provides fun provideWeightDao(db: UserDatabase): WeightDao = db.weightDao()
    @Provides fun provideFavoriteDao(db: UserDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideCustomFoodDao(db: UserDatabase): CustomFoodDao = db.customFoodDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    @Singleton
    abstract fun bindFoodRepository(impl: FoodRepositoryImpl): FoodRepository

    @Binds
    @Singleton
    abstract fun bindLogRepository(impl: LogRepositoryImpl): LogRepository

    @Binds
    @Singleton
    abstract fun bindGoalsRepository(impl: GoalsRepositoryImpl): GoalsRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindWeightRepository(impl: WeightRepositoryImpl): WeightRepository
}
