// Hilt wiring for the Balancee Pump API network stack: JSON, OkHttp (with the signing +
// logging interceptors), Retrofit, and the PumpApiService. Base URL comes from BuildConfig so it
// varies per build type (debug → localhost, release → staging/prod).
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.data.config.PumpConfigSync
import app.balancee.smartpump.display.data.network.KeystorePumpCredentialsStore
import app.balancee.smartpump.display.data.network.PersistentDeviceIdProvider
import app.balancee.smartpump.display.data.network.ProbeClock
import app.balancee.smartpump.display.data.network.ProbeClockOffset
import app.balancee.smartpump.display.data.network.ProbeCaptureInterceptor
import app.balancee.smartpump.display.data.network.ProbeResponseRecorder
import app.balancee.smartpump.display.data.network.PumpApiService
import app.balancee.smartpump.display.data.network.PumpLoggingInterceptor
import app.balancee.smartpump.display.data.network.PumpSigningInterceptor
import app.balancee.smartpump.display.domain.config.DeviceConfigSync
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // AES-256-GCM encrypted-at-rest store (Android KeyStore key + private SharedPreferences).
    @Provides
    @Singleton
    fun provideCredentialsStore(impl: KeystorePumpCredentialsStore): PumpCredentialsStore = impl

    // 10c-bis. The interface is the narrow boot-time view ("refresh what you know"); the payment
    // processor takes the concrete PumpConfigSync, because it needs the response it just stored.
    @Provides
    @Singleton
    fun provideDeviceConfigSync(impl: PumpConfigSync): DeviceConfigSync = impl

    // Mint-once, never-changing deviceId in its own prefs file (TODO #16) — deliberately outside
    // the encrypted blob above, so a KeyStore wipe cannot change this device's identity.
    @Provides
    @Singleton
    fun provideDeviceIdProvider(impl: PersistentDeviceIdProvider): DeviceIdProvider = impl

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
        probeOffset: ProbeClockOffset,
    ): PumpSigningInterceptor =
        // ProbeClock, not the bare clock: the debug probe panel can shift request signing into the
        // past to see what a stale timestamp actually returns (#15). The shift reaches signing and
        // nothing else — audit rows, receipts and the fuel log keep the real clock — and
        // ProbeClockOffset.set() is inert in release.
        PumpSigningInterceptor(credentialsStore, ProbeClock(clock, probeOffset))

    @Provides
    @Singleton
    fun provideOkHttpClient(
        signing: PumpSigningInterceptor,
        recorder: ProbeResponseRecorder,
    ): OkHttpClient {
        // Never print credential material, even in debug logs. PumpLoggingInterceptor redacts the
        // credential HEADERS and — the part plain redactHeader() cannot do — withholds the BODY of
        // /api/pump/activate, which is where apiKey and signingSecret actually arrive (TODO #12).
        val logging = PumpLoggingInterceptor(enabled = BuildConfig.DEBUG)
        return OkHttpClient.Builder()
            .addInterceptor(signing)   // signs first…
            .addInterceptor(logging)   // …so the log shows the final signed request
            // Keeps the literal response bytes of body-safe calls for the API probe panel (TODO
            // #32). Debug-only, and it reuses the logging interceptor's allowlist, so /activate —
            // the one response carrying apiKey and signingSecret — is excluded by the very same
            // predicate that keeps it out of the log (#12).
            .addInterceptor(ProbeCaptureInterceptor(recorder, enabled = BuildConfig.DEBUG))
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
