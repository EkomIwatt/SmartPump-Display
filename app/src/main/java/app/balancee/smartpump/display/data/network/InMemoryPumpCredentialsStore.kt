// PLACEHOLDER credentials store — in-memory only.
//
// This is a deliberate stand-in so the network layer compiles and is testable before the activation
// flow (sub-phase 7f) exists. It does NOT persist: credentials are lost on process death, and the
// signingSecret is not encrypted at rest. Replace with a KeyStore-backed / EncryptedSharedPreferences
// implementation when activation lands — nothing but the Hilt binding in NetworkModule changes,
// because the signing interceptor depends only on the PumpCredentialsStore interface.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.domain.network.PumpCredentials
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryPumpCredentialsStore @Inject constructor() : PumpCredentialsStore {

    private val ref = AtomicReference<PumpCredentials?>(null)

    override fun current(): PumpCredentials? = ref.get()

    override val isActivated: Boolean
        get() = ref.get() != null

    override suspend fun save(credentials: PumpCredentials) {
        ref.set(credentials)
    }

    override suspend fun clear() {
        ref.set(null)
    }
}
