// The two deviceId invariants that only a device can prove (TODO #16) — the mint-once logic itself
// is covered off-device in PersistentDeviceIdProviderTest via the DeviceIdStorage seam.
//
// Both tests here are about *where* the id lives: it must reach a real SharedPreferences file and
// survive a new process, and it must be far enough away from the credentials store that wiping
// credentials cannot take the identity with it. If it could, revoke-and-reissue (or a KeyStore key
// invalidation) would silently mint a new deviceId and the pump could never authenticate again.
package app.balancee.smartpump.display.data.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.balancee.smartpump.display.domain.network.PumpCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentDeviceIdProviderInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Mirror the private constants of both stores so we can inspect/clear their files directly.
    private val identityPrefs
        get() = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    private val credentialPrefs
        get() = context.getSharedPreferences("pump_credentials", Context.MODE_PRIVATE)

    private fun newProvider() = PersistentDeviceIdProvider(context)

    @Before
    fun clearPrefs() {
        identityPrefs.edit().clear().commit()
        credentialPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() = clearPrefs()

    @Test
    fun mintedId_persistsAcrossFreshInstance() {
        val first = newProvider().deviceId()

        assertNotNull(identityPrefs.getString("device_id", null))
        // A brand-new instance stands in for the next app start: it must read, not re-mint.
        assertEquals(first, newProvider().deviceId())
    }

    @Test
    fun clearingCredentials_doesNotChangeTheDeviceId() = runBlocking {
        val provider = newProvider()
        val before = provider.deviceId()

        val store = KeystorePumpCredentialsStore(context, Json { ignoreUnknownKeys = true })
        store.save(
            PumpCredentials(
                deviceId = before,
                pumpId = "7f108b57-7559-4837-8dfb-33c7aac7d632",
                apiKey = "bal_live_abc123",
                signingSecret = "s3cr3t-hmac-key-do-not-log",
            ),
        )
        store.clear() // revoke/reissue, or debug re-onboarding

        // Re-activation has to present the same identity the backend already knows.
        assertEquals(before, newProvider().deviceId())
    }
}
