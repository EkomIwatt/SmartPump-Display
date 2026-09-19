// The real payment processor: GET /config → POST /authorise → a QR the customer can pay → poll
// GET /transactions/{id} until PAID.
//
// Phase 10c built the authorise half. **10d added the poll and the resume path**, which is what
// makes the terminal result real and what lets `PaymentModule` bind this at all.
package app.balancee.smartpump.display.data.payment

import app.balancee.smartpump.display.data.config.PumpConfigSync
import app.balancee.smartpump.display.data.config.SyncedConfig
import app.balancee.smartpump.display.data.config.formatNaira
import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.RetryPolicy
import app.balancee.smartpump.display.data.network.retryPolicy
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.SaleBasis
import app.balancee.smartpump.display.domain.model.SaleQuote
import app.balancee.smartpump.display.domain.model.quoteForDispensed
import app.balancee.smartpump.display.domain.model.quoteForTender
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.EventRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BalanceePaymentProcessor @Inject constructor(
    private val client: PumpApiClient,
    private val configSync: PumpConfigSync,
    private val events: EventRepository,
    private val transactionIds: TransactionIdFactory,
    // Injected so a test can reach the expiry deadline without waiting twenty real minutes.
    private val clock: Clock,
) : PaymentProcessor {

    override fun process(request: PaymentRequest): Flow<PaymentResult> = flow {
        // The correctness guarantee (OQ #8): fetch the price immediately before authorising, every
        // time. Push is a freshness optimisation and is allowed to be missing or late; this is not.
        //
        // 10c-bis: the fetch now stores what it reads, so the price on the customer's screen is the
        // one this sale is about to be authorised at rather than a number an operator typed once.
        val synced = when (val result = configSync.fetch()) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                emit(PaymentResult.Failed(reason = result.error.describe("could not read the price")))
                return@flow
            }
        }
        val config = synced.config

        val quote = quoteFor(request, synced.koboPerLitre)
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

        recordPriceRaceIfAny(request, synced, quote, authorised.transactionId)

        val expiresAt = authorised.expiresAt?.toInstantOrNull()
        emit(
            PaymentResult.Pending(
                transactionRef = authorised.transactionId,
                method = request.method,
                // The quote, not the request. What the customer tendered is not what Paystack will
                // collect, and the screen has to show the figure the checkout page shows.
                amountKobo = quote.amountKobo,
                litres = quote.litres.toDouble(),
                checkoutUrl = checkoutUrl,
                expiresAt = expiresAt,
                paymentReference = authorised.paymentReference,
            ),
        )

        pollUntilTerminal(
            transactionId = authorised.transactionId,
            method = request.method,
            amountKobo = quote.amountKobo,
            litres = quote.litres.toDouble(),
            paymentReference = authorised.paymentReference,
            deadline = expiresAt,
        )
    }

    /**
     * Resume after a restart: poll the sale that already exists, and never authorise a second one.
     *
     * No `/config` and no `/authorise`. Re-pricing would be wrong here even if it were free — the
     * customer may already have paid the figure they were quoted, and the sale the server is
     * holding is the one to ask about.
     */
    override fun resume(
        transactionRef: String,
        request: PaymentRequest,
        deadline: Instant?,
    ): Flow<PaymentResult> = flow {
        pollUntilTerminal(
            transactionId = transactionRef,
            method = request.method,
            amountKobo = request.amountKobo,
            litres = request.expectedLitres,
            // Not known on this path — it came back on the authorise, before the restart. The poll
            // returns it, and 10f's upload cannot go out without it.
            paymentReference = null,
            deadline = deadline,
        )
    }

    /**
     * Poll `GET /transactions/{id}` until the sale resolves, the window closes, or the collector
     * goes away.
     *
     * **What ends the poll early is deliberately a short list**: `PAID`, `DISPENSED`, a server that
     * says the transaction does not exist, and a device with no credentials to ask with. Everything
     * else — an unrecognised status string, a reply that would not parse, a 500, no signal — keeps
     * polling until the deadline.
     *
     * That asymmetry is the point. Giving up on a status nobody has observed would refuse fuel to
     * someone who has paid, on a guess about a word. Riding to the deadline costs at worst a wait
     * the server's own expiry bounds, and the caller's countdown cancels this flow at that same
     * moment anyway. The statuses treated as terminal are the ones observed at the #32 gate
     * (`PENDING_PAYMENT` → `PAID` → `DISPENSED`); `DISPENSED` counts because a sale that completed
     * and uploaded before the restart is a paid sale.
     */
    private suspend fun FlowCollector<PaymentResult>.pollUntilTerminal(
        transactionId: String,
        method: PaymentMethod,
        amountKobo: Long,
        litres: Double,
        paymentReference: String?,
        deadline: Instant?,
    ) {
        var reference = paymentReference
        while (true) {
            if (deadline != null && !clock.instant().isBefore(deadline)) {
                emit(
                    PaymentResult.Failed(
                        reason = "The payment window closed before this was paid.",
                        transactionRef = transactionId,
                    ),
                )
                return
            }

            when (val result = client.transactionStatus(transactionId)) {
                is ApiResult.Success -> {
                    // Keep the newest one: `resume` starts without a reference and the upload
                    // cannot go out without it.
                    result.data.paymentReference?.let { reference = it }
                    when (result.data.status) {
                        STATUS_PAID, STATUS_DISPENSED -> {
                            emit(
                                PaymentResult.Success(
                                    transactionRef = transactionId,
                                    amountKobo = amountKobo,
                                    method = method,
                                    paymentReference = reference,
                                    litresAuthorised = litres,
                                ),
                            )
                            return
                        }
                        // PENDING_PAYMENT, or a word this app has never seen. Both wait.
                        else -> Unit
                    }
                }

                is ApiResult.Failure -> if (result.error.isPollTerminal) {
                    emit(
                        PaymentResult.Failed(
                            reason = result.error.describe("could not confirm the payment"),
                            transactionRef = transactionId,
                        ),
                    )
                    return
                }
            }

            delay(POLL_INTERVAL.toMillis())
        }
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
    private fun quoteFor(request: PaymentRequest, koboPerLitre: Long): SaleQuote =
        when (request.basis) {
            SaleBasis.Tender -> quoteForTender(request.amountKobo, koboPerLitre)
            SaleBasis.Dispensed -> quoteForDispensed(request.expectedLitres, koboPerLitre)
        }

    /**
     * Log the seconds-wide race that 10c-bis could not close: the price moved between the nozzle
     * clicking off and this authorise, so a fill-up customer is charged an amount other than the one
     * they watched climb.
     *
     * Only for [SaleBasis.Dispensed]. A pre-pay customer has taken nothing yet and is buying a sum
     * rather than a volume, so a re-price simply buys them fewer litres — correct, and not an event.
     *
     * Recorded after the authorise succeeds, so the log never carries a discrepancy for a sale that
     * never happened.
     */
    private suspend fun recordPriceRaceIfAny(
        request: PaymentRequest,
        synced: SyncedConfig,
        quote: SaleQuote,
        transactionRef: String,
    ) {
        if (request.basis != SaleBasis.Dispensed || !synced.priceChanged) return
        events.record(
            type = EventType.PRICE_CHANGED_MID_SALE,
            transactionRef = transactionRef,
            detail = "Price changed during this fill-up: " +
                "${formatNaira(synced.previousKoboPerLitre!!)} → ${formatNaira(synced.koboPerLitre)} per litre. " +
                "Displayed ${formatNaira(request.amountKobo)}, charged ${formatNaira(quote.amountKobo)}.",
        )
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

/**
 * Whether a failed poll is worth giving up on, as opposed to waiting out.
 *
 * **This is not the same question [retryPolicy] answers, and the difference is the deadline.** The
 * poll is bounded by the server's own `expiresAt`, so waiting out a failure it cannot classify
 * costs a wait that ends on its own; an upload queue has no such bound. So where the shared
 * taxonomy calls a 500, a dropped connection or an unparseable reply terminal-or-not, the poll
 * simply keeps asking.
 *
 * What it does share — and what #45 put in one place — is **which answers from the server are
 * final**. A considered refusal will read the same on the next poll, and `PAYMENT_NOT_CONFIRMED`
 * will not, so both follow [retryPolicy] rather than a second list of codes kept in step by hand.
 */
private val ApiError.isPollTerminal: Boolean
    get() = when (this) {
        // No credentials to ask with; every subsequent poll fails identically.
        is ApiError.NotActivated -> true
        is ApiError.Business -> retryPolicy == RetryPolicy.TERMINAL
        else -> false
    }

/** Observed at the #32 gate: `PENDING_PAYMENT` → `PAID` → `DISPENSED`. */
private const val STATUS_PAID = "PAID"
private const val STATUS_DISPENSED = "DISPENSED"

/** The cadence OQ #8 settled on. A twenty-minute window is ~120 requests per unpaid sale. */
private val POLL_INTERVAL: Duration = Duration.ofSeconds(10)
