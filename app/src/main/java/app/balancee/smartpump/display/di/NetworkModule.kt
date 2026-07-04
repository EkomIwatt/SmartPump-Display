// Hilt wiring for the Balancee Pump API network stack: JSON, OkHttp (with the signing +
// logging interceptors), Retrofit, and the PumpApiService. Base URL comes from BuildConfig so it
// varies per build type (debug → localhost, release → staging/prod).
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.data.network.InMemoryPumpCredentialsStore
import app.balancee.smartpump.display.data.network.PumpApiService
import app.balancee.smartpump.display.data.network.PumpSigningInterceptor
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // In-memory placeholder until the activation flow (7f) provides an encrypted-at-rest store.
    @Provides
    @Singleton
    fun provideCredentialsStore(impl: InMemoryPumpCredentialsStore): PumpCredentialsStore = impl

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true   // tolerate extra server fields
        explicitNulls = false      // omit nulls we don't set
    }

    @Provides
    @Singleton
    fun provideSigningInterceptor(
        credentialsStore: PumpCredentialsStore,
        clock: Clock,
    ): PumpSigningInterceptor = PumpSigningInterceptor(credentialsStore, clock)

    @Provides
    @Singleton
    fun provideOkHttpClient(signing: PumpSigningInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // Never print credential material, even in debug logs.
            redactHeader("X-Api-Key")
            redactHeader("X-Signature")
        }
        return OkHttpClient.Builder()
            .addInterceptor(signing)   // signs first…
            .addInterceptor(logging)   // …so the log shows the final signed request
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PUMP_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun providePumpApiService(retrofit: Retrofit): PumpApiService =
        retrofit.create(PumpApiService::class.java)
}
