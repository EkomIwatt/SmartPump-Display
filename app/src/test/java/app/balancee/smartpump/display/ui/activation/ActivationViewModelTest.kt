// What is under test here is not the network call — PumpActivationRepositoryImplTest already
// covers that — but the operator's next move. The repository distinguishes six outcomes, and two
// of them ("refused" and "no answer") are the ones an installer will confuse, because on screen
// they both look like it didn't work. One means try again freely; the other means a second code
// may burn a spare on a pump that is already registered. These tests pin that difference.
package app.balancee.smartpump.display.ui.activation

import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.domain.repository.ActivationOutcome
import app.balancee.smartpump.display.domain.repository.PumpActivationRepository
import app.balancee.smartpump.display.ui.customer.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActivationViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val ourDeviceId = "11111111-2222-3333-4444-555555555555"
    private val issuedPumpId = "7f108b57-7559-4837-8dfb-33c7aac7d632"

    private class FakeDeviceIds(private val id: String) : DeviceIdProvider {
        override fun deviceId(): String = id
    }

    /** Records what was sent, and answers with whatever the test has queued. */
    private class FakeActivationRepo(
        var outcome: ActivationOutcome = ActivationOutcome.Refused("nope", null, 400),
    ) : PumpActivationRepository {
        var activated = false
        var storedPumpId: String? = null
        val sentCodes = mutableListOf<String>()

        override val isActivated: Boolean get() = activated
        override val pumpId: String? get() = storedPumpId

        override suspend fun activate(activationCode: String): ActivationOutcome {
            sentCodes += activationCode
            if (outcome is ActivationOutcome.Activated) {
                activated = true
                storedPumpId = (outcome as ActivationOutcome.Activated).pumpId
            }
            return outcome
        }
    }

    private val repo = FakeActivationRepo()

    private fun vm() = ActivationViewModel(repo, FakeDeviceIds(ourDeviceId))

    // ---- what gets sent ----------------------------------------------------------

    @Test
    fun `the code is uppercased and stripped of anything but letters digits and dashes`() {
        val vm = vm()
        vm.setCode(" blc-act 7f19\" ")
        assertEquals("BLC-ACT7F19", vm.ui.value.code)
    }

    @Test
    fun `a code shorter than four characters cannot be sent`() {
        val vm = vm()
        vm.setCode("ABC")
        assertFalse(vm.ui.value.canSubmit)
        vm.setCode("ABCD")
        assertTrue(vm.ui.value.canSubmit)
    }

    @Test
    fun `submitting twice sends the code once`() {
        // Not a cosmetic guard: the second call would race the first into the credential store.
        repo.outcome = ActivationOutcome.Activated(issuedPumpId)
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()
        vm.submit()
        assertEquals(listOf("BLC-ACT-7F19"), repo.sentCodes)
    }

    @Test
    fun `an activated device cannot send another code`() {
        repo.activated = true
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()
        assertTrue(repo.sentCodes.isEmpty())
    }

    // ---- what the operator is told -----------------------------------------------

    @Test
    fun `success clears the field and reports the pump id`() {
        repo.outcome = ActivationOutcome.Activated(issuedPumpId)
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        val state = vm.ui.value
        assertTrue(state.activated)
        assertEquals(issuedPumpId, state.pumpId)
        assertEquals("", state.code)
        assertEquals(ActivationTone.Success, state.report?.tone)
        assertTrue(state.report!!.detail.contains(issuedPumpId))
    }

    @Test
    fun `a refused code stays in the field so it can be checked against the paperwork`() {
        repo.outcome = ActivationOutcome.Refused("Invalid activation code", "INVALID_REQUEST", 400)
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        val state = vm.ui.value
        assertEquals("BLC-ACT-7F19", state.code)
        assertFalse(state.activated)
        assertEquals(ActivationTone.Failure, state.report?.tone)
        assertTrue(state.report!!.detail.contains("Invalid activation code"))
        assertTrue(state.report!!.detail.contains("INVALID_REQUEST"))
    }

    // ---- the distinction this file exists for ------------------------------------

    @Test
    fun `refused invites a new code - nothing was used up`() {
        repo.outcome = ActivationOutcome.Refused("Invalid activation code", null, 400)
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        val report = vm.ui.value.report!!
        assertTrue(report.mayRetrySameCode)
        assertTrue(report.mayTryNewCode)
    }

    @Test
    fun `unreachable permits the same code and warns off a different one`() {
        repo.outcome = ActivationOutcome.Unreachable("timeout")
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        val report = vm.ui.value.report!!
        assertTrue(report.mayRetrySameCode)
        assertFalse(report.mayTryNewCode)
        // The recovery is a phone call about this device, so the id has to be on screen.
        assertTrue(report.detail.contains(ourDeviceId))
        // Unchanged code: resending is the advised move, so no warning.
        assertFalse(vm.ui.value.warnNewCodeAfterUnknown)
    }

    @Test
    fun `typing a different code after an unknown outcome raises the warning but not a block`() {
        repo.outcome = ActivationOutcome.Unreachable("timeout")
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        vm.setCode("BLC-ACT-0000")
        val state = vm.ui.value
        assertTrue(state.warnNewCodeAfterUnknown)
        // Warned, not stopped — support may have confirmed the first code never landed.
        assertTrue(state.canSubmit)
    }

    @Test
    fun `credentials lost is terminal for this code and asks for a reissued one`() {
        repo.outcome = ActivationOutcome.CredentialsLost("the write failed")
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        val report = vm.ui.value.report!!
        assertEquals(ActivationTone.Failure, report.tone)
        assertFalse(report.mayRetrySameCode)
        assertTrue(report.mayTryNewCode)
        assertFalse(vm.ui.value.activated)
    }

    @Test
    fun `an identity mismatch still counts as activated and names both ids`() {
        repo.outcome = ActivationOutcome.IdentityMismatch(
            pumpId = issuedPumpId,
            sent = ourDeviceId,
            returned = "99999999-8888-7777-6666-555555555555",
        )
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        val report = vm.ui.value.report!!
        assertEquals(ActivationTone.Caution, report.tone)
        assertTrue(report.detail.contains(ourDeviceId))
        assertTrue(report.detail.contains("99999999-8888-7777-6666-555555555555"))
        assertFalse(report.mayTryNewCode)
    }

    @Test
    fun `already activated warns instead of failing, and offers no second code`() {
        repo.outcome = ActivationOutcome.AlreadyActivated
        val vm = vm()
        vm.setCode("BLC-ACT-7F19")
        vm.submit()

        val report = vm.ui.value.report!!
        assertEquals(ActivationTone.Caution, report.tone)
        assertFalse(report.mayTryNewCode)
        assertFalse(report.mayRetrySameCode)
    }

    // ---- what the panel shows before anything happens -----------------------------

    @Test
    fun `the device id is on screen from the first frame`() {
        // The whole ambiguous-activation recovery depends on an operator being able to read it out.
        val state = vm().ui.value
        assertEquals(ourDeviceId, state.deviceId)
        assertNull(state.report)
        assertFalse(state.activated)
    }

    @Test
    fun `an already-activated pump shows its pump id without being asked`() {
        repo.activated = true
        repo.storedPumpId = issuedPumpId
        val state = vm().ui.value
        assertTrue(state.activated)
        assertEquals(issuedPumpId, state.pumpId)
    }
}
