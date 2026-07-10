// App-level build config for SmartPump Display kiosk app.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.balancee.smartpump.display"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.balancee.smartpump.display"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "MOCK_HARDWARE", "true")
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
        release {
            isMinifyEnabled = false
            buildConfigField("Boolean", "MOCK_HARDWARE", "false")
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