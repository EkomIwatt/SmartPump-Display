// Instrumented verification of KeystorePumpCredentialsStore's AES-256-GCM-at-rest crypto.
//
// This can't run in the local JVM — Android KeyStore, KeyGenParameterSpec, and android.util.Base64
// are only real on a device/emulator. Runs on any emulator (no Arduino needed); closes TODO #10,
// the second of the two merge gates for feature/phase-7a-hardening.
//
// Covers: initial not-activated, save→current round-trip, isActivated toggle, persistence across a
// fresh store instance (the real "did it actually hit disk + re-decrypt" check), clear() wipe, and
// the corrupt-blob → null-fallback path that must also purge the unreadable ciphertext.
package app.balancee.smartpump.display.data.network

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.balancee.smartpump.display.domain.network.PumpCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher

@RunWith(AndroidJUnit4::class)
class KeystorePumpCredentialsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }

    // Mirror the store's private constants so we can seed/inspect its SharedPreferences directly.
    private val prefs get() = context.getSharedPreferences("pump_credentials", Context.MODE_PRIVATE)

    private val sampleCreds = PumpCredentials(
        deviceId = "pump-uno-01",
        pumpId = "7f108b57-7559-4837-8dfb-33c7aac7d632",
        apiKey = "bal_live_abc123",
        signingSecret = "s3cr3t-hmac-key-do-not-log",
    )

    private fun newStore() = KeystorePumpCredentialsStore(context, json)

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun freshStore_isNotActivated() {
        val store = newStore()
        assertNull(store.current())
        assertFalse(store.isActivated)
    }

    @Test
    fun save_thenCurrent_roundTripsSameInstance() = runBlocking {
        val store = newStore()
        store.save(sampleCreds)
        assertEquals(sampleCreds, store.current())
        assertTrue(store.isActivated)
    }

    @Test
    fun credentials_persistAcrossFreshInstance() = runBlocking {
        // save() on one instance...
        newStore().save(sampleCreds)
        // ...a brand-new instance must decrypt the on-disk blob back to the same credentials.
        val reopened = newStore()
        assertEquals(sampleCreds, reopened.current())
        assertTrue(reopened.isActivated)
    }

    @Test
    fun clear_wipesAcrossFreshInstance() = runBlocking {
        val store = newStore()
        store.save(sampleCreds)
        store.clear()

        assertNull(store.current())
        assertFalse(store.isActivated)
        // And it's gone from disk, not just the in-memory cache.
        assertNull(newStore().current())
    }

    /**
     * A blob in the pre-#13 format — no `pumpId`, no version — must be purged, not half-read. The
     * alternative (defaulting pumpId to "") would decode cleanly and then send an empty pumpId to
     * /authorise, which the server rejects with `401 pumpId does not match authenticated device`:
     * an opaque 401 in the field where "not activated" is the honest, recoverable answer.
     */
    @Test
    fun legacyFormatBlob_isPurged_ratherThanPartiallyRead() = runBlocking {
        // Let the store create its KeyStore key, then overwrite the blob with old-shape plaintext
        // encrypted under that same key — i.e. exactly what an install from before #13 would hold.
        newStore().save(sampleCreds)
        prefs.edit().putString("credentials_blob", encryptWithStoreKey(LEGACY_PLAINTEXT)).commit()

        val store = newStore()

        assertNull(store.current())
        assertFalse(store.isActivated)
        assertNull("stale-format blob should be purged", prefs.getString("credentials_blob", null))
    }

    @Test
    fun corruptBlob_fallsBackToNull_andPurgesCiphertext() {
        // "QUJD" is valid Base64 (= "ABC", 3 bytes) but shorter than the 12-byte GCM IV, so decrypt
        // throws and loadFromDisk() must swallow it → null, and drop the unreadable blob so a later
        // clean activation can write fresh state.
        prefs.edit().putString("credentials_blob", "QUJD").commit()

        val store = newStore()

        assertNull(store.current())
        assertFalse(store.isActivated)
        assertNull("unreadable ciphertext should be purged", prefs.getString("credentials_blob", null))
    }

    /** Mirrors the store's own crypto (same alias/transformation, IV prepended, Base64 NO_WRAP). */
    private fun encryptWithStoreKey(plaintext: String): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getEntry("pump_credentials_key", null) as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private companion object {
        const val LEGACY_PLAINTEXT =
            """{"deviceId":"pump-uno-01","apiKey":"bal_live_abc123","signingSecret":"old"}"""
    }
}
