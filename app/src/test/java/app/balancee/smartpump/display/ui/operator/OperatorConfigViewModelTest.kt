// Operator config VM (Phase 7b). The screen is a form, but two pieces of its logic are the kind
// that quietly cost money if wrong: the naira→kobo parse (a mistyped price is billed on every
// litre until someone notices) and the blank-field fallback (an operator editing only the price
// must not wipe the station name off receipts).
//
// Uses the existing MainDispatcherRule so viewModelScope runs eagerly and ui.value can be read
// synchronously — same approach as the CustomerViewModel suite.
package app.balancee.smartpump.display.ui.operator

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import app.balancee.smartpump.display.ui.customer.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OperatorConfigViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeRepo(var config: DeviceConfig? = null) : DeviceConfigRepository {
        var saveCount = 0; private set
        override suspend fun getConfig(): DeviceConfig? = config
        override suspend fun saveConfig(config: DeviceConfig) { saveCount++; this.config = config }
        override fun observeConfig(): Flow<DeviceConfig?> = MutableStateFlow(config)
    }

    private fun vmFor(repo: FakeRepo) =
        OperatorConfigViewModel(repo, CanStartTransactionUseCase(repo))

    // ---- first run on an unconfigured pump -----------------------------------------

    /**
     * The screen must not pre-fill a plausible price on a pump that has never been configured.
     * A filled-looking field does not ask to be read, and accepting a leftover default is a
     * wrong-price sale on every litre.
     */
    @Test
    fun `unconfigured pump opens with blank fields and no fuel type`() {
        val vm = vmFor(FakeRepo(config = null))

        val ui = vm.ui.value
        assertTrue(ui.loaded)
        assertNull(ui.fuelType)
        assertEquals("", ui.nairaPerLitre)
        assertEquals("", ui.stationName)
        assertFalse("cannot save an empty form", ui.canSave)
    }

    @Test
    fun `unconfigured pump reports both fields missing`() {
        val vm = vmFor(FakeRepo(config = null))

        assertEquals(
            setOf(
                CanStartTransactionUseCase.Missing.PRICE,
                CanStartTransactionUseCase.Missing.FUEL_TYPE,
            ),
            vm.ui.value.missing,
        )
    }

    @Test
    fun `existing config is loaded into the form`() {
        val repo = FakeRepo(
            DeviceConfig(
                pumpLabel = "PUMP 3",
                stationName = "Total Lekki Ph2",
                koboPerLitre = 87_050,
                fuelType = FuelType.DIESEL,
            ),
        )

        val ui = vmFor(repo).ui.value

        assertEquals(FuelType.DIESEL, ui.fuelType)
        assertEquals("870.50", ui.nairaPerLitre)
        assertEquals("Total Lekki Ph2", ui.stationName)
        assertEquals("PUMP 3", ui.pumpLabel)
        assertTrue(ui.missing.isEmpty())
    }

    // ---- price parsing -------------------------------------------------------------

    /** Sub-naira prices are the reason money is carried as kobo — ₦870.50 must not truncate. */
    @Test
    fun `sub-naira price parses to exact kobo`() {
        val vm = vmFor(FakeRepo())
        vm.onPriceChanged("870.50")

        assertEquals(87_050L, vm.ui.value.koboPerLitre)
    }

    @Test
    fun `whole naira price parses to exact kobo`() {
        val vm = vmFor(FakeRepo())
        vm.onPriceChanged("870")

        assertEquals(87_000L, vm.ui.value.koboPerLitre)
    }

    @Test
    fun `unparseable, empty, zero and negative prices all block saving`() {
        val vm = vmFor(FakeRepo())
        vm.onFuelTypeSelected(FuelType.PETROL)

        listOf("", "   ", ".", "abc", "0", "0.00", "-5").forEach { input ->
            vm.onPriceChanged(input)
            assertNull("'$input' must not parse to a price", vm.ui.value.koboPerLitre)
            assertFalse("'$input' must not be saveable", vm.ui.value.canSave)
        }
    }

    /** A price alone is not enough — fuelType is as load-bearing as price now. */
    @Test
    fun `price without a fuel type cannot be saved`() {
        val vm = vmFor(FakeRepo())
        vm.onPriceChanged("870")

        assertFalse(vm.ui.value.canSave)
    }

    // ---- saving --------------------------------------------------------------------

    @Test
    fun `save persists the parsed kobo price and the chosen fuel type`() {
        val repo = FakeRepo()
        val vm = vmFor(repo)
        vm.onFuelTypeSelected(FuelType.KEROSENE)
        vm.onPriceChanged("912.75")
        vm.onStationNameChanged("Mobil Ikoyi")
        vm.onPumpLabelChanged("PUMP 2")

        vm.onSave()

        val saved = requireNonNull(repo.config)
        assertEquals(91_275L, saved.koboPerLitre)
        assertEquals(FuelType.KEROSENE, saved.fuelType)
        assertEquals("Mobil Ikoyi", saved.stationName)
        assertEquals("PUMP 2", saved.pumpLabel)
        assertTrue("the pump is now configured", vm.ui.value.missing.isEmpty())
    }

    /**
     * Editing only the price must not blank the station name — it prints on receipts, and an
     * operator who never touched the field would not expect to have cleared it.
     */
    @Test
    fun `blank identification fields fall back to the stored values`() {
        val repo = FakeRepo(
            DeviceConfig(
                pumpLabel = "PUMP 3",
                stationName = "Total Lekki Ph2",
                koboPerLitre = 87_000,
                fuelType = FuelType.PETROL,
            ),
        )
        val vm = vmFor(repo)
        vm.onStationNameChanged("")
        vm.onPumpLabelChanged("   ")
        vm.onPriceChanged("900")

        vm.onSave()

        val saved = requireNonNull(repo.config)
        assertEquals("Total Lekki Ph2", saved.stationName)
        assertEquals("PUMP 3", saved.pumpLabel)
        assertEquals(90_000L, saved.koboPerLitre)
    }

    /** virtualAccountNumber is not on this form; saving must not silently drop it. */
    @Test
    fun `save preserves fields the form does not expose`() {
        val repo = FakeRepo(
            DeviceConfig(
                koboPerLitre = 87_000,
                fuelType = FuelType.PETROL,
                virtualAccountNumber = "0123456789",
            ),
        )
        val vm = vmFor(repo)
        vm.onPriceChanged("910")

        vm.onSave()

        assertEquals("0123456789", requireNonNull(repo.config).virtualAccountNumber)
    }

    @Test
    fun `save is refused when the form is incomplete`() {
        val repo = FakeRepo()
        val vm = vmFor(repo)
        vm.onPriceChanged("870") // no fuel type

        vm.onSave()

        assertEquals("nothing written", 0, repo.saveCount)
        assertTrue(vm.ui.value.saveError!!.contains("fuel type"))
    }

    private fun <T : Any> requireNonNull(value: T?): T =
        value ?: throw AssertionError("expected a saved config")
}
