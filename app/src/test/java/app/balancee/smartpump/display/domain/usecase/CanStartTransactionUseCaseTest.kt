// The guard that decides whether this pump is allowed to sell anything at all.
//
// It gained a second condition in 7b: a pump with a price but no fuel type must not start a
// transaction, because /authorise requires a fuelType and dispensing without one would bill the
// customer against a fuel the backend never agreed to. These tests pin both conditions and the
// deliberate split between what the operator screen learns (which field is missing) and what the
// customer is told (one message, since the fix is the same either way).
package app.balancee.smartpump.display.domain.usecase

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanStartTransactionUseCaseTest {

    private class FakeRepo(var config: DeviceConfig?) : DeviceConfigRepository {
        override suspend fun getConfig(): DeviceConfig? = config
        override suspend fun saveConfig(config: DeviceConfig) { this.config = config }
        override fun observeConfig(): Flow<DeviceConfig?> = MutableStateFlow(config)
    }

    private fun guard(config: DeviceConfig?) = CanStartTransactionUseCase(FakeRepo(config))

    private fun configured(
        koboPerLitre: Long = 87_000L,
        fuelType: FuelType? = FuelType.PETROL,
    ) = DeviceConfig(koboPerLitre = koboPerLitre, fuelType = fuelType)

    @Test
    fun `fully configured pump is allowed`() = runTest {
        val result = guard(configured())()

        assertTrue(result is CanStartTransactionUseCase.Result.Allowed)
        assertEquals(
            87_000L,
            (result as CanStartTransactionUseCase.Result.Allowed).config.koboPerLitre,
        )
    }

    @Test
    fun `missing fuel type blocks even when the price is set`() = runTest {
        val result = guard(configured(fuelType = null))()

        assertEquals(
            CanStartTransactionUseCase.Result.NotConfigured(
                setOf(CanStartTransactionUseCase.Missing.FUEL_TYPE),
            ),
            result,
        )
    }

    @Test
    fun `zero price blocks even when the fuel type is set`() = runTest {
        val result = guard(configured(koboPerLitre = 0))()

        assertEquals(
            CanStartTransactionUseCase.Result.NotConfigured(
                setOf(CanStartTransactionUseCase.Missing.PRICE),
            ),
            result,
        )
    }

    /** A negative price is as unusable as zero — guard on <= 0, not == 0. */
    @Test
    fun `negative price blocks`() = runTest {
        val result = guard(configured(koboPerLitre = -1))()

        assertTrue(result is CanStartTransactionUseCase.Result.NotConfigured)
    }

    /** Both fields wrong must report both, so the operator screen can flag both at once. */
    @Test
    fun `price and fuel type both unset are both reported`() = runTest {
        val result = guard(configured(koboPerLitre = 0, fuelType = null))()

        assertEquals(
            CanStartTransactionUseCase.Result.NotConfigured(
                setOf(
                    CanStartTransactionUseCase.Missing.PRICE,
                    CanStartTransactionUseCase.Missing.FUEL_TYPE,
                ),
            ),
            result,
        )
    }

    /**
     * A pump that has never been configured has no config row at all. It must report both fields
     * rather than an empty set — an empty set would read as "nothing missing" to the operator
     * screen, which is the opposite of the truth.
     */
    @Test
    fun `absent config reports both fields missing`() = runTest {
        val result = guard(null)()

        assertEquals(
            CanStartTransactionUseCase.Result.NotConfigured(
                setOf(
                    CanStartTransactionUseCase.Missing.PRICE,
                    CanStartTransactionUseCase.Missing.FUEL_TYPE,
                ),
            ),
            result,
        )
    }

    /**
     * The customer copy is deliberately generic — it covers a missing price and a missing fuel type
     * alike, because the customer cannot act on the difference and the fix path is identical.
     * Pinned so the two cases cannot drift into separate wording.
     */
    @Test
    fun `customer message names neither field and points at the attendant`() {
        val message = CanStartTransactionUseCase.CUSTOMER_MESSAGE

        assertTrue(message.contains("attendant", ignoreCase = true))
        assertTrue("must not leak which field is missing", !message.contains("price", true))
        assertTrue("must not leak which field is missing", !message.contains("fuel type", true))
    }
}
