package com.gamelingo.mobile.di

import android.content.Context
import androidx.room.Room
import com.gamelingo.mobile.data.local.GameLingoDatabase
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.settings.DataStoreSettingsRepository
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.ocr.MlKitTextRecognizerEngine
import com.gamelingo.mobile.ocr.TextRecognizerEngine
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
    fun provideDatabase(@ApplicationContext context: Context): GameLingoDatabase =
        Room.databaseBuilder(context, GameLingoDatabase::class.java, "gamelingo.db")
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()

    @Provides
    fun provideTranslationHistoryDao(database: GameLingoDatabase): TranslationHistoryDao =
        database.translationHistoryDao()

    @Provides
    @Singleton
    fun provideSettingsRepository(
        impl: DataStoreSettingsRepository,
    ): SettingsRepository = impl

    @Provides
    @Singleton
    fun provideTextRecognizerEngine(
        impl: MlKitTextRecognizerEngine,
    ): TextRecognizerEngine = impl
}
