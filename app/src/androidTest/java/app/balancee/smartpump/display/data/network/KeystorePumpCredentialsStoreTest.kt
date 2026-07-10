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

@RunWith(AndroidJUnit4::class)
class KeystorePumpCredentialsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }

    // Mirror the store's private constants so we can seed/inspect its SharedPreferences directly.
    private val prefs get() = context.getSharedPreferences("pump_credentials", Context.MODE_PRIVATE)

    private val sampleCreds = PumpCredentials(
        deviceId = "pump-uno-01",
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
}
