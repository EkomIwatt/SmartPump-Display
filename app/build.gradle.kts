// App-level build config for SmartPump Display kiosk app.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

// ---- Version -------------------------------------------------------------------------------
//
// Bump [appVersionCode] on EVERY build handed to anyone, even a re-cut of the same code. Android
// refuses to install an APK whose versionCode is not greater than the one already installed, and a
// kiosk tablet bolted to a forecourt is exactly where "just uninstall it first" is not an option.
// [appVersionName] is what a human reads on the operator screen; keep it semantic.
val appVersionCode = 1
val appVersionName = "1.0.0"

// ---- Release signing -----------------------------------------------------------------------
//
// Credentials come from `keystore.properties` at the repo root (gitignored — see
// keystore.properties.example) or, for CI, the matching environment variables. Nothing
// signing-related is ever committed: not the keystore, not the passwords, not the alias.
//
// When they are absent the release build stays UNSIGNED rather than failing configuration. That is
// deliberate — a fresh clone, a CI lint run and anyone building debug must all still work without
// the station's private key. The trade is that an unsigned release is possible, so the build warns
// loudly at configuration time and `docs/RELEASE.md` says how to verify before handing an APK over.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (keystoreProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "SMARTPUMP_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "SMARTPUMP_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SMARTPUMP_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SMARTPUMP_KEY_PASSWORD")

// The keystore path is resolved against the repo root so a relative entry works on any machine;
// an absolute path (the safer place to keep it) is passed through unchanged by File's own rules.
val releaseKeystore = releaseStoreFile?.let { rootProject.file(it) }
val releaseSigningReady = releaseKeystore != null &&
    releaseKeystore.exists() &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

if (!releaseSigningReady) {
    logger.warn(
        "SmartPump: release signing is NOT configured — `assembleRelease` will produce an " +
            "UNSIGNED apk that cannot be installed. See docs/RELEASE.md.",
    )
}

android {
    namespace = "app.balancee.smartpump.display"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.balancee.smartpump.display"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "MOCK_HARDWARE", "true")
            // Payments too (Phase 10d). This build points at the DEV backend, where no pump has
            // ever been activated, so the real processor would refuse every sale — and the debug
            // screen's auto-approve / force-resolve controls, which the demo depends on, only
            // exist on the mock. Mirrors MOCK_HARDWARE deliberately: same idea, same shape.
            buildConfigField("Boolean", "MOCK_PAYMENTS", "true")
            // Hosted dev backend (per boss, 2026-07-04). debugRealHw inherits this via initWith.
            // To run against a LOCAL backend instead, point this at "http://10.0.2.2:8080/"
            // (emulator → host loopback) — the debug network-security-config already permits
            // cleartext to 10.0.2.2/localhost.
            buildConfigField("String", "PUMP_API_BASE_URL", "\"https://api.dev.balancee.app/\"")
        }
        // Real-hardware demo/bench build: debuggable (inherits debug signing + debug screen),
        // but talks to the real Arduino over USB. Distinct applicationId suffix so it installs
        // side-by-side with the mock `debug` app — the live-demo fallback (open the mock app if
        // the rig misbehaves, no uninstall needed).
        create("debugRealHw") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".realhw"
            versionNameSuffix = "-realhw"
            buildConfigField("Boolean", "MOCK_HARDWARE", "false")
        }
        // Same app as `debug` — mock hardware, debuggable, self-seeding config, debug hotspot —
        // but pointed at PRODUCTION. It exists for one job: the activation gate (TODO #32) against
        // a pump that only exists on the live backend, because the code we hold was minted there.
        //
        // The separate applicationId is the point. It installs beside the dev app, keeps its own
        // credentials and deviceId, and can be uninstalled without touching either. A gradle
        // property toggling the URL on `debug` would be smaller and worse: the next build without
        // the flag silently points production credentials at dev, and nothing on screen would say
        // so. (The probe panel prints the server for the same reason.)
        //
        // NOT a candidate for the parallel run — it is a debug build, with everything V1_BLOCKERS
        // says disqualifies one: it seeds its own placeholder price and carries the debug hotspot.
        create("debugProd") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".prod"
            versionNameSuffix = "-prod"
            // **Real payments.** This is the only debuggable build that can take one: production is
            // where the activated pump lives. It is the build the 10g gate runs on — and it charges
            // a real card, so keep the amounts small.
            buildConfigField("Boolean", "MOCK_PAYMENTS", "false")
            buildConfigField("String", "PUMP_API_BASE_URL", "\"https://api.balancee.app/\"")
        }
        release {
            // Null when the credentials are absent, which leaves the apk unsigned rather than
            // failing the build. Verify with `apksigner verify` before handing one over —
            // docs/RELEASE.md.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            buildConfigField("Boolean", "MOCK_HARDWARE", "false")
            buildConfigField("Boolean", "MOCK_PAYMENTS", "false")
            // Production backend (per boss, 2026-07-04).
            buildConfigField("String", "PUMP_API_BASE_URL", "\"https://api.balancee.app/\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Phase 8: CustomerViewModel unit tests call android.util.Log directly (a stubbed
    // framework class under plain JVM tests). Return default values instead of throwing
    // "not mocked", so the tests stay pure-JVM with no Robolectric and no production
    // Logger-interface refactor. (Decision: test-only flag over touching src/main.)
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Room's exported schema JSONs (app/schemas) are bundled as androidTest assets so
    // MigrationTestHelper can load them when migration tests are added.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    // debugRealHw is a debuggable build derived from debug (initWith) that may also hit a local
    // backend, so it reuses the debug network-security-config + manifest overlay. initWith copies
    // build-type *properties* only, not source sets — so wire debug's manifest/res in explicitly.
    sourceSets.getByName("debugRealHw") {
        manifest.srcFile("src/debug/AndroidManifest.xml")
        res.srcDir("src/debug/res")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    // Export the Room schema to app/schemas (committed to git) so migrations can be
    // authored and tested against a baseline. Destructive fallback is debug-only.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hardware
    implementation(libs.usbserial)

    // Payments / QR
    implementation(libs.zxing)

    // Serialization (state persistence to Room + network DTOs)
    implementation(libs.kotlinx.serialization.json)

    // Network (Balancee Pump API — Retrofit/OkHttp + outbound HMAC signing)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}