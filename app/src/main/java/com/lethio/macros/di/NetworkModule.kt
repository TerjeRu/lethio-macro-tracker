package com.lethio.macros.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The HTTP client for the opt-in Open Food Facts lookup and contribution. Nothing calls it unless
 * the setting and the reader's tap allow it.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Short timeouts and no automatic retry: the reader is standing in a shop, and failing fast
     * beats a spinner. No cache, cookies or interceptors.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** `ignoreUnknownKeys`: OFF's product object is large and evolving, and a new key must not break parsing. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
}
