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
 * @param expectedLitres How much fuel that amount buys, floored to 2dp by
 *                      [DeviceConfig.litresCutoff] — never more than was paid for. For a fill-up
 *                      the tank is already full, so this is the measured figure rather than a
 *                      derived one.
 */
@Immutable
data class PaymentRequest(
    val method: PaymentMethod,
    val amountKobo: Long,
    val expectedLitres: Double,
)
