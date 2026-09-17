// What a sale is worth, in a form both the server and Paystack will accept.
//
// Three constraints meet here, and no two of them can be satisfied by rounding the third:
//
//  1. **The server checks `amount == expectedLitres × pricePerUnit` exactly.** A rounded amount is
//     refused, not accepted a few kobo out (observed: 400 AMOUNT_MISMATCH at the #32 gate).
//  2. **Paystack charges whole kobo.** An amount of ₦3,506.864 is 350,686.4 kobo, which the payment
//     rail cannot collect. The API accepts such an amount and says nothing — observed 2026-09-17,
//     `docs/api-probes/2026-09-17-prod-precision/`. That is a trap, not a feature.
//  3. **The pump must never dispense more fuel than was paid for**, so litres always floor.
//
// Together they mean a pre-pay customer's round sum is usually NOT the amount that can be charged:
// at ₦1,490/L a ₦5,000 pre-pay buys 3.35 L, worth ₦4,991.50. The gap is the customer's, so the job
// here is to make it as small as the constraints allow.
package app.balancee.smartpump.display.domain.model

import java.math.BigDecimal

/**
 * A quote the server will accept and Paystack can collect.
 *
 * @param litres what the customer is promised. Always a multiple of [litreStepMicros] and never more
 *   than four decimal places, which is the most the server has been observed to accept.
 * @param amountKobo what to charge: a whole number of kobo, and exactly `litres × price`.
 * @param litreStepMicros the litre granularity this price permits, in ten-thousandths of a litre.
 * @param shortfallKobo tendered minus charged. Always ≥ 0, and always the customer's loss.
 */
data class SaleQuote(
    val litres: BigDecimal,
    val amountKobo: Long,
    val litreStepMicros: Long,
    val shortfallKobo: Long,
) {
    /** The wire value for `AuthoriseRequest.amount`: decimal naira, exactly `litres × price`. */
    val amountNaira: BigDecimal get() = BigDecimal.valueOf(amountKobo, 2)
}

/**
 * The finest litre step, in ten-thousandths of a litre, whose product with [koboPerLitre] is always
 * a whole number of kobo.
 *
 * Quoting litres as `n / 10_000`, the amount in kobo is `n × price / 10_000`. Reduce that fraction:
 * with `g = gcd(price, 10_000)`, it is an integer exactly when `10_000 / g` divides `n`. So the step
 * is `10_000 / g` — derived, not chosen, and different for every price:
 *
 * | price | step | effective precision |
 * |---|---|---|
 * | ₦1,490 | 10 | 0.001 L |
 * | ₦1,491 | 100 | 0.01 L |
 * | ₦870.50 | 200 | 0.02 L |
 * | ₦1,250 | 2 | 0.0002 L |
 *
 * **There is no safe fixed answer**, which is the whole point: a constant would work until someone
 * changed the price, and the sales it then refused would look like a backend fault.
 */
internal fun litreStepMicrosFor(koboPerLitre: Long): Long {
    require(koboPerLitre > 0) { "price must be positive, was $koboPerLitre" }
    return MICROS_PER_LITRE / gcd(koboPerLitre, MICROS_PER_LITRE)
}

/**
 * Quote a pre-pay sale for [tenderedKobo] at [koboPerLitre].
 *
 * Litres floor, so the customer can be short — never over. The amount returned is what to authorise
 * **and** what to charge; the tendered figure is never sent, because it is only payable when the
 * price happens to divide it, and whether it does depends on the price's prime factors rather than
 * on anything anyone decided.
 */
fun quoteForTender(tenderedKobo: Long, koboPerLitre: Long): SaleQuote {
    val step = litreStepMicrosFor(koboPerLitre)
    // The most litres the tender buys, in ten-thousandths, then rounded down onto a payable step.
    val affordable = tenderedKobo * MICROS_PER_LITRE / koboPerLitre
    val micros = affordable / step * step
    return quoteOf(micros, koboPerLitre, tenderedKobo)
}

/**
 * Quote a fill-up, where the fuel is already in the tank.
 *
 * [litres] is measured rather than chosen, so there is nothing to floor and no shortfall — but it
 * still has to land on a payable step, or the amount is uncollectable for the same reason a too-fine
 * pre-pay quote would be. It floors, which costs the station a fraction of a kobo of fuel: the right
 * direction for a figure the customer has already taken.
 */
fun quoteForDispensed(litres: Double, koboPerLitre: Long): SaleQuote {
    val step = litreStepMicrosFor(koboPerLitre)
    val measured = Math.floor(litres * MICROS_PER_LITRE).toLong().coerceAtLeast(0L)
    val micros = measured / step * step
    return quoteOf(micros, koboPerLitre, tenderedKobo = null)
}

private fun quoteOf(micros: Long, koboPerLitre: Long, tenderedKobo: Long?): SaleQuote {
    val amountKobo = micros * koboPerLitre / MICROS_PER_LITRE
    return SaleQuote(
        // stripTrailingZeros so 3.3500 goes out as 3.35 — the wire has no use for the padding, and
        // a quote that reads finer than it is invites someone to trust a digit that is always zero.
        litres = BigDecimal.valueOf(micros, MICRO_SCALE).stripTrailingZeros(),
        amountKobo = amountKobo,
        litreStepMicrosFor(koboPerLitre),
        shortfallKobo = tenderedKobo?.minus(amountKobo) ?: 0L,
    )
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

/** Four decimal places — the most the server has been observed to accept (2026-09-17). */
private const val MICRO_SCALE = 4
private const val MICROS_PER_LITRE = 10_000L
