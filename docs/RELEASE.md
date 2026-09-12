# Cutting a release build

Until 2026-09-12 the project had **no signing configuration at all**, so `assembleRelease` produced
an unsigned APK that Android will not install. This is how it works now.

## The one-time setup

### 1. Create the keystore

Run this once, ever. Keep the file **outside the repo**.

```
keytool -genkeypair -v \
  -keystore ../smartpump-release.jks \
  -alias smartpump \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

`-validity 10000` is roughly 27 years, which is the convention: an Android app's signing key cannot
be rotated for an existing install base, so a key that expires is a key that strands every tablet in
the field. `keytool` ships with the JDK — the Android Studio JBR at
`C:\Program Files\Android\Android Studio\jbr\bin` has it.

### 2. Point the build at it

```
cp keystore.properties.example keystore.properties
```

Fill in the four values. `keystore.properties`, `*.jks` and `*.keystore` are all gitignored.

For CI, set the same four as environment variables instead and leave the file absent:
`SMARTPUMP_STORE_FILE`, `SMARTPUMP_STORE_PASSWORD`, `SMARTPUMP_KEY_ALIAS`,
`SMARTPUMP_KEY_PASSWORD`.

### 3. Back the keystore up somewhere that is not this laptop

**Losing this file ends the app's upgrade path.** Every future update must be signed by the same
key, and there is no recovery: a tablet in the field will refuse an APK signed by a different one,
so the only fix is uninstall-and-reinstall on every unit, which wipes local transaction history and
the device's activation identity — and activation cannot be re-issued without the station revoking
it. Treat the file and its passwords as station infrastructure, not developer convenience.

## Cutting a build

1. **Bump `appVersionCode`** at the top of `app/build.gradle.kts`. Every build handed to anyone,
   even a re-cut of identical code. Android refuses to install an APK whose `versionCode` is not
   greater than the installed one, and a kiosk tablet is exactly where "uninstall it first" is not
   available. Update `appVersionName` too when it is a real version.
2. Build:
   ```
   ./gradlew clean assembleRelease
   ```
   On a bare shell, set `JAVA_HOME` to the Android Studio JBR first or Gradle exits 49.
3. **Verify it is actually signed** — this is the step that catches the failure mode this whole
   document exists for:
   ```
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
   `apksigner` lives in `<sdk>/build-tools/<version>/`. It must print the certificate. If it says
   the APK is not signed, `keystore.properties` was not found — the Gradle configuration log will
   also carry the `release signing is NOT configured` warning.

## Notes

- **The build does not fail when signing is unconfigured**, on purpose: a fresh clone, a CI lint run
  and every debug build must work without the station's private key. The cost is that an unsigned
  release is producible, which is why step 3 above is not optional.
- **Release is not minified.** `isMinifyEnabled = false`, deferred 2026-06-01 — enabling R8 needs
  keep rules for kotlinx-serialization, Room and Hilt plus an end-to-end verify. Do it **after**
  signing is proven working, so a broken release build can only have one cause at a time.
- **`debug` and `debugRealHw` are unaffected.** They keep the auto-generated debug signing key and
  install side by side with each other as before.
