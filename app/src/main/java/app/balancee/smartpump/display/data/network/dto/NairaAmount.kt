// How money crosses the wire to the Balanceè Pump API: a decimal naira JSON *number*.
//
// TODO #44. `AuthoriseRequest.amount` was a `Long`, which could not express most real sales. The
// server checks `amount == expectedLitres × pricePerUnit` **exactly**, so a fractional amount is not
// something to round — a rounded 3501 in place of 3501.50 is REJECTED, not fifty kobo out. That was
// confirmed by observation at the #32 gate: `{"amount":3501.5,"expectedLitres":2.35}` at ₦1490/L was
// accepted and paid.
//
// **Not a Double.** The check being exact is precisely why binary floating point is the wrong
// carrier: 1490 × 2.3 is 3426.9999999999995 in a Double, and an equality test against a value like
// that starts failing on figures that look correct on screen. BigDecimal keeps decimal arithmetic
// decimal.
package app.balancee.smartpump.display.data.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/**
 * Serialises a [BigDecimal] as a bare JSON number, in plain notation, with trailing zeros stripped.
 *
 * Each of those three properties is load-bearing:
 *  - **bare, not quoted** — the observed request body is `"amount":3501.5`. A string `"3501.5"` is a
 *    different wire shape and the signature is computed over these exact bytes, so it is not a
 *    cosmetic difference.
 *  - **plain notation** — `BigDecimal.stripTrailingZeros()` renders 3500.00 as `3.5E+3`, which is
 *    valid JSON and absurd in a payment. [BigDecimal.toPlainString] is what keeps it `3500`.
 *  - **trailing zeros stripped** — so a whole-naira amount goes out as `2980`, matching every
 *    integer amount the Reference and the gate captures show, rather than `2980.00`.
 */
object NairaAmountSerializer : KSerializer<BigDecimal> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NairaAmount", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val plain = value.stripTrailingZeros().toPlainString()
        val json = encoder as? JsonEncoder
            ?: throw IllegalStateException(
                "NairaAmount is JSON-only — it emits an unquoted literal, which no other format has.",
            )
        json.encodeJsonElement(JsonUnquotedLiteral(plain))
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        // Read the literal text rather than a Double: going through a Double here would undo the
        // whole point of the type on the way back in.
        val json = decoder as? JsonDecoder ?: return BigDecimal(decoder.decodeString())
        return BigDecimal(json.decodeJsonElement().jsonPrimitive.content)
    }
}

/**
 * The app's kobo as decimal naira, exactly. 350_150 kobo → `3501.50`.
 *
 * Exact because the scale is set rather than divided: no rounding mode is involved and none is
 * needed. This is the conversion the money note in `TransactionState` has always described as "the
 * repository mapper owns the ÷100".
 */
fun nairaFromKobo(amountKobo: Long): BigDecimal = BigDecimal.valueOf(amountKobo, 2)

/**
 * The amount the server will accept for [litres] at [koboPerLitre] — its own check, computed the
 * same way it computes it.
 *
 * **Prefer this over [nairaFromKobo] when building an `/authorise` body.** The two can disagree, and
 * where they do it is the kobo figure that is wrong for this purpose: the app rounds money to the
 * kobo it can charge, while the server compares against the unrounded product. At a whole-naira
 * price — which is what `GET /config` returns — litres floored to 2dp give a product that lands
 * exactly on a kobo anyway, so they agree; at a sub-naira price they need not.
 *
 * [litres] arrives as a `Double` because that is what the meter and [DeviceConfig.litresCutoff]
 * deal in; `BigDecimal.valueOf` converts via the shortest decimal representation, so 2.35 becomes
 * `2.35` and not `2.35000000000000008881784197001`.
 */
fun nairaForSale(litres: Double, koboPerLitre: Long): BigDecimal =
    BigDecimal.valueOf(litres).multiply(BigDecimal.valueOf(koboPerLitre, 2))
