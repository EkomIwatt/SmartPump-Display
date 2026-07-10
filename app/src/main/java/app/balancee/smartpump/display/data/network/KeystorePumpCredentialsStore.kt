// Production PumpCredentialsStore: credentials encrypted at rest with an AES-256-GCM key held in
// the Android KeyStore (non-exportable, device-bound), ciphertext in a private SharedPreferences.
//
// KeyStore-direct rather than androidx.security:security-crypto (which is in maintenance) — the
// signingSecret is sensitive material, so we avoid a deprecated dependency and keep the crypto
// explicit. The decrypted credentials are cached in memory so current() — called by the signing
// interceptor on every request — is a cheap volatile read, not a KeyStore round-trip.
package app.balancee.smartpump.display.data.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.balancee.smartpump.display.domain.network.PumpCredentials
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystorePumpCredentialsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : PumpCredentialsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Decrypted credentials cached for the synchronous current() hot path. Loaded once at
    // construction; kept in sync by save()/clear().
    private val cache = AtomicReference<PumpCredentials?>(loadFromDisk())

    override fun current(): PumpCredentials? = cache.get()

    override val isActivated: Boolean get() = cache.get() != null

    override suspend fun save(credentials: PumpCredentials) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(StoredCredentials.serializer(), credentials.toStored())
            .toByteArray(Charsets.UTF_8)
        val blob = encrypt(plaintext)
        prefs.edit().putString(KEY_BLOB, blob).commit()
        cache.set(credentials)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_BLOB).commit()
        cache.set(null)
        Unit
    }

    // ---- persistence ----

    private fun loadFromDisk(): PumpCredentials? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val plaintext = decrypt(blob).toString(Charsets.UTF_8)
            json.decodeFromString(StoredCredentials.serializer(), plaintext).toDomain()
        } catch (e: Exception) {
            // Corrupt blob or a rotated/invalidated KeyStore key → treat as not activated and
            // drop the unreadable ciphertext so a fresh activation can write clean state.
            prefs.edit().remove(KEY_BLOB).commit()
            null
        }
    }

    // ---- crypto ----

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv // GCM: 12 bytes
        val ciphertext = cipher.doFinal(plaintext)
        // Store IV prepended to the ciphertext, whole thing Base64'd.
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): ByteArray {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Unattended kiosk: no user-auth gate, or the pump couldn't sign on its own.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    @Serializable
    private data class StoredCredentials(
        @SerialName("deviceId") val deviceId: String,
        @SerialName("apiKey") val apiKey: String,
        @SerialName("signingSecret") val signingSecret: String,
    )

    private fun PumpCredentials.toStored() = StoredCredentials(deviceId, apiKey, signingSecret)
    private fun StoredCredentials.toDomain() = PumpCredentials(deviceId, apiKey, signingSecret)

    private companion object {
        const val PREFS_NAME = "pump_credentials"
        const val KEY_BLOB = "credentials_blob"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pump_credentials_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
