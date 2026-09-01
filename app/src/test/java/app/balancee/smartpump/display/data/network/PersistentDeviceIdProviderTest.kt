// The deviceId's whole job is to never change (TODO #16). These tests hold that invariant against
// the ways it could quietly break: a second mint on a later boot, a mint per caller, a value cached
// in memory that never reached disk, or a blank read being mistaken for an identity.
//
// Pure JVM — SharedPreferences is behind the DeviceIdStorage seam, so the mint-once logic is
// verifiable here rather than only on a device (which is where the crypto store's equivalent
// coverage had to live, and where it then sat unrun for weeks).
package app.balancee.smartpump.display.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PersistentDeviceIdProviderTest {

    /** In-memory stand-in for the prefs file, with a switch for a failed write. */
    private class FakeStorage(
        var value: String? = null,
        var writable: Boolean = true,
    ) : DeviceIdStorage {
        var writes = 0
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            writes++
            if (!writable) return false
            this.value = value
            return true
        }
    }

    /** Mints a different value every call, so a second mint is impossible to miss. */
    private class CountingMint : () -> String {
        var calls = 0
        override fun invoke(): String = "id-${++calls}"
    }

    @Test
    fun `first call mints an id and persists it`() {
        val storage = FakeStorage()
        val provider = PersistentDeviceIdProvider(storage) { "minted-id" }

        assertEquals("minted-id", provider.deviceId())
        assertEquals("minted-id", storage.value)
    }

    @Test
    fun `repeated calls return the same id and mint only once`() {
        val mint = CountingMint()
        val provider = PersistentDeviceIdProvider(FakeStorage(), mint)

        val first = provider.deviceId()
        repeat(5) { assertEquals(first, provider.deviceId()) }
        assertEquals(1, mint.calls)
    }

    /** The real case: app restarts, a fresh provider reads what the previous install wrote. */
    @Test
    fun `a new provider over the same storage reuses the stored id and never mints`() {
        val storage = FakeStorage("previously-minted")
        val mint = CountingMint()

        val provider = PersistentDeviceIdProvider(storage, mint)

        assertEquals("previously-minted", provider.deviceId())
        assertEquals(0, mint.calls)
        assertEquals(0, storage.writes)
    }

    /**
     * A half-written or truncated value is not an identity. Reading "" as one would send an empty
     * X-Device-Id and fail every signed request with a 401 that says nothing about the cause.
     */
    @Test
    fun `a blank stored value is treated as absent and replaced`() {
        val storage = FakeStorage("   ")

        val id = PersistentDeviceIdProvider(storage) { "fresh-id" }.deviceId()

        assertEquals("fresh-id", id)
        assertEquals("fresh-id", storage.value)
    }

    /**
     * The dangerous failure: handing back an id that only exists in memory. A caller would activate
     * against it, the backend would bind the station's once-only credentials to it, and the next
     * reboot would mint a different one — leaving the pump permanently unable to authenticate.
     * Better to fail activation loudly now.
     */
    @Test
    fun `a failed write throws instead of returning an unpersisted id`() {
        val storage = FakeStorage(writable = false)
        val provider = PersistentDeviceIdProvider(storage) { "doomed-id" }

        assertThrows(IllegalStateException::class.java) { provider.deviceId() }
        assertNull(storage.value)
    }

    /** Concurrent first callers (e.g. activation racing a header read) must not mint twice. */
    @Test
    fun `concurrent first calls all see one id`() {
        val mint = CountingMint()
        val provider = PersistentDeviceIdProvider(FakeStorage(), mint)

        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = List(8) { Thread { results.add(provider.deviceId()) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(8, results.size)
        assertEquals(1, results.distinct().size)
        assertEquals(1, mint.calls)
    }

    /** The production mint: a random UUID, not a device-derived value that a factory reset changes. */
    @Test
    fun `the default mint is a random UUID`() {
        val storageA = FakeStorage()
        val storageB = FakeStorage()

        val a = PersistentDeviceIdProvider(storageA) { UUID.randomUUID().toString() }.deviceId()
        val b = PersistentDeviceIdProvider(storageB) { UUID.randomUUID().toString() }.deviceId()

        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }
}
