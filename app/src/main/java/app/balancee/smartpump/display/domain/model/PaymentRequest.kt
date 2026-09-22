// Everything a payment processor needs to start a charge — the caller's half of the contract.
//
// Why this exists (Phase 10a): the seam used to be `process(method, amountKobo)`, which was enough
// for the mock and is not enough for the real thing. `POST /api/pump/authorise` requires an
// `expectedLitres` alongside the amount, and the server checks `amount == expectedLitres ×
// pricePerUnit` EXACTLY — so litres is not a nicety the request can omit and let the backend infer.
//
// What is deliberately NOT here: `pumpId` and `fuelType`. Both are properties of the device rather
// than of the sale — pumpId lives on PumpCredentials, fuelType on DeviceConfig — so a processor
// with the right dependencies can source them itself. Threading them through every call site would
// make four screens responsible for remembering facts about the pump they are running on.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

/**
 * @param method        How the customer is paying. Cash never reaches a processor.
 * @param amountKobo    What to charge, in kobo. The app carries money as kobo everywhere; the
 *                      repository mapper owns the conversion to the wire's decimal naira.
 * @param expectedLitres How much fuel that amount buys, floored by [DeviceConfig.litresCutoff] —
 *                      never more than was paid for. For a fill-up the tank is already full, so
 *                      this is the measured figure rather than a derived one.
 * @param basis         **Which of the two numbers above is fixed.** Added in 10c, because a real
 *                      processor re-prices against the server's `/config` and has to know which
 *                      figure to hold still while the other moves. Both are always populated; this
 *                      says which one is the truth and which is a consequence of it.
 */
@Immutable
data class PaymentRequest(
    val method: PaymentMethod,
    val amountKobo: Long,
    val expectedLitres: Double,
    val basis: SaleBasis,
)

/**
 * Which end of a sale is nailed down.
 *
 * It matters only once a price can change underneath a sale — which it can, because the server's
 * price is authoritative and is fetched immediately before every authorise (OQ #8). Re-pricing has
 * to move the *other* number, and moving the wrong one either charges for fuel that was not
 * dispensed or dispenses fuel that was not charged for.
 */
enum class SaleBasis {
    /**
     * The customer chose a sum and has not received anything yet. Hold the money; litres follow.
     * A pre-pay customer who tendered ₦5,000 gets fewer litres if the price went up, which is
     * correct — they are buying ₦5,000 of fuel, not a fixed volume.
     */
    Tender,

    /**
     * The fuel is already in the tank and the volume cannot be argued with. Hold the litres; the
     * amount follows. A price change between the nozzle clicking off and the QR appearing therefore
     * changes what is owed — see the note in `BalanceePaymentProcessor`, which cannot avoid it.
     */
    Dispensed,
}
