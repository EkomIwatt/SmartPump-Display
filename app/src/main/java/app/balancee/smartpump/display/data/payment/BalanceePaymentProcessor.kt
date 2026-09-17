// The real payment processor: GET /config → POST /authorise → a QR the customer can actually pay.
//
// **NOT BOUND IN DI YET.** Phase 10c builds the authorise half; the terminal result — PAID, detected
// by polling `GET /transactions/{id}` — is 10d. Binding this before the poll exists would give a
// customer a QR that never resolves, so `PaymentModule` still binds the mock and this is exercised
// by its tests until 10d flips it.
package app.balancee.smartpump.display.data.payment

import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.SaleBasis
import app.balancee.smartpump.display.domain.model.SaleQuote
import app.balancee.smartpump.display.domain.model.quoteForDispensed
import app.balancee.smartpump.display.domain.model.quoteForTender
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BalanceePaymentProcessor @Inject constructor(
    private val client: PumpApiClient,
    private val transactionIds: TransactionIdFactory,
) : PaymentProcessor {

    override fun process(request: PaymentRequest): Flow<PaymentResult> = flow {
        // The correctness guarantee (OQ #8): fetch the price immediately before authorising, every
        // time. Push is a freshness optimisation and is allowed to be missing or late; this is not.
        val config = when (val result = client.config()) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                emit(PaymentResult.Failed(reason = result.error.describe("could not read the price")))
                return@flow
            }
        }

        val quote = quoteFor(request, config)
        if (quote.amountKobo <= 0) {
            emit(PaymentResult.Failed(reason = "That amount does not buy any fuel at the current price."))
            return@flow
        }

        // Ours, and sent rather than received: the server echoes it back unchanged. Generating it
        // here is what makes a payment resumable after a restart — there is an id to ask about
        // instead of a second sale to create (10d).
        val transactionId = transactionIds.next()

        val authorised = when (
            val result = client.authorise(
                AuthoriseRequest(
                    pumpId = config.pumpId,
                    transactionId = transactionId,
                    amount = quote.amountNaira,
                    expectedLitres = quote.litres.toDouble(),
                    // From /config, not from the device. 7b's operator-entered fuel type was a
                    // workaround for an API that did not supply one; it does, so the sale is
                    // authorised against the fuel the server believes this pump sells.
                    fuelType = config.fuelType,
                ),
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                emit(PaymentResult.Failed(reason = result.error.describe("could not start the sale")))
                return@flow
            }
        }

        val checkoutUrl = authorised.authorizationUrl
        if (checkoutUrl.isNullOrBlank()) {
            // A 200 with nothing to scan is worse than a refusal: the screen would show a QR-shaped
            // hole and the customer would wait at it. #46 made this field nullable; this is the
            // caller that has to care.
            emit(PaymentResult.Failed(reason = "The sale started but no payment page was returned."))
            return@flow
        }

        emit(
            PaymentResult.Pending(
                transactionRef = authorised.transactionId,
                method = request.method,
                checkoutUrl = checkoutUrl,
                expiresAt = authorised.expiresAt?.toInstantOrNull(),
                paymentReference = authorised.paymentReference,
            ),
        )

        // TODO(10d): poll GET /transactions/{id} until PAID, expiry or cancellation, then emit the
        //  terminal. Suspending rather than completing is deliberate — a flow that ended here would
        //  look to a collector like a payment that resolved, and the collector's `when` would treat
        //  the absence of a terminal as nothing having gone wrong.
        awaitCancellation()
    }

    /**
     * Re-price against the server, holding whichever end of the sale [SaleBasis] says is fixed.
     *
     * **There is no option to use the device's own price here, and that is not a preference.** The
     * server checks `amount == expectedLitres × pricePerUnit` against *its* price, so a body built
     * on a stale one is refused outright — every sale, until someone reconciles the two by hand.
     *
     * The consequence worth naming: for [SaleBasis.Dispensed] the fuel is already in the tank, so a
     * price change between the nozzle clicking off and the QR appearing changes what is owed, and
     * the customer watched the old figure climb on the display. Rare, and unavoidable from here —
     * the alternative is a sale that cannot be authorised at all. Flagged on the board rather than
     * papered over.
     */
    private fun quoteFor(request: PaymentRequest, config: PumpConfigResponse): SaleQuote {
        val koboPerLitre = config.pricePerUnit * KOBO_PER_NAIRA
        return when (request.basis) {
            SaleBasis.Tender -> quoteForTender(request.amountKobo, koboPerLitre)
            SaleBasis.Dispensed -> quoteForDispensed(request.expectedLitres, koboPerLitre)
        }
    }
}

/** Seam so a test can pin the id the app generates; production mints a UUID and never reuses one. */
fun interface TransactionIdFactory {
    fun next(): String
}

@Singleton
class UuidTransactionIdFactory @Inject constructor() : TransactionIdFactory {
    override fun next(): String = UUID.randomUUID().toString()
}

/**
 * A placeholder for the attendant-facing reason string, and **deliberately thin**.
 *
 * Phase 10e wires `ERROR_COPY_DRAFT.md` Catalogue A in properly: one plain line for the customer,
 * the diagnostic detail behind the swipe-up panel, and a retryable failure looking different from a
 * terminal one. That work was blocked on #8 because nothing produced an `ApiError` a customer could
 * see — this class is the first thing that does. Until then the server's own `message` is carried
 * through verbatim rather than paraphrased, because a paraphrase written now is copy nobody agreed.
 */
private fun ApiError.describe(context: String): String = when (this) {
    is ApiError.Business -> message ?: "$context — the server declined it."
    is ApiError.Network -> "$context — no connection to Balanceè."
    is ApiError.NotActivated -> "This pump is not activated yet."
    is ApiError.Http -> "$context — the server returned $code."
    is ApiError.Serialization -> "$context — the server's reply was not understood."
    is ApiError.Unknown -> context
}

/**
 * Server timestamps have been well-formed every time they have been observed, but a countdown is not
 * worth crashing a sale over: an unparseable expiry becomes null and the caller falls back to its own
 * window, which is the same path a response that omits the field takes.
 */
private fun String.toInstantOrNull(): Instant? =
    try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

private const val KOBO_PER_NAIRA = 100L
