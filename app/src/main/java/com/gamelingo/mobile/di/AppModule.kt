package com.gamelingo.mobile.di

import android.content.Context
import androidx.room.Room
import com.gamelingo.mobile.data.local.GameLingoDatabase
import com.gamelingo.mobile.data.local.Migrations
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.settings.DataStoreSettingsRepository
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.ocr.MlKitTextRecognizerEngine
import com.gamelingo.mobile.ocr.TextRecognizerEngine
import com.gamelingo.mobile.translate.GeminiApi
import com.gamelingo.mobile.translate.GeminiTranslationEngine
import com.gamelingo.mobile.translate.MlKitTranslationEngine
import com.gamelingo.mobile.translate.TranslationEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import com.gamelingo.mobile.translate.local.LocalAiRouter
import com.gamelingo.mobile.translate.local.LocalEngineChain
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GameLingoDatabase =
        Room.databaseBuilder(context, GameLingoDatabase::class.java, "gamelingo.db")
            // Never fallbackToDestructiveMigration: an upgrade must keep the
            // user's translation history. Every schema change ships a real
            // migration in Migrations plus a migration test.
            .addMigrations(*Migrations.ALL)
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

    /**
     * Backstop socket timeouts only; the user-facing request timeout lives in
     * settings and is enforced per call with a coroutine timeout.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(SOCKET_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(SOCKET_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(GEMINI_BASE_URL)
            .client(client)
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory(JSON_MEDIA_TYPE.toMediaType()),
            )
            .build()

    @Provides
    @Singleton
    fun provideGeminiApi(retrofit: Retrofit): GeminiApi =
        retrofit.create(GeminiApi::class.java)

    /** Hybrid engine delegates, named so they can be bound to the interface. */
    @Provides
    @Singleton
    @Named("online")
    fun provideOnlineEngine(impl: GeminiTranslationEngine): TranslationEngine = impl

    @Provides
    @Singleton
    @Named("local")
    fun provideLocalEngine(impl: MlKitTranslationEngine): TranslationEngine = impl

    /** The same ML Kit engine under the name the local engine chain consumes. */
    @Provides
    @Singleton
    @Named("mlkit")
    fun provideMlKitEngine(impl: MlKitTranslationEngine): TranslationEngine = impl

    /** The local chain as the offline-first routing surface the hybrid engine consumes. */
    @Provides
    @Singleton
    fun provideLocalAiRouter(impl: LocalEngineChain): LocalAiRouter = impl

    private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val JSON_MEDIA_TYPE = "application/json"
    private const val SOCKET_CONNECT_TIMEOUT_SEC = 10L
    private const val SOCKET_READ_TIMEOUT_SEC = 65L
}
