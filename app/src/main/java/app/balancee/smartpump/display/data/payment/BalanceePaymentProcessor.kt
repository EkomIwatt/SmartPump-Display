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
import app.balancee.smartpump.display.data.network.toFailureCopy
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.FailureCopy
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.SaleBasis
import app.balancee.smartpump.display.domain.model.SaleQuote
import app.balancee.smartpump.display.domain.model.quoteForDispensed
import app.balancee.smartpump.display.domain.model.quoteForTender
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.EventRepository
import app.balancee.smartpump.display.domain.util.runCatchingCancellable
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
                emit(PaymentResult.Failed(failure = result.error.toFailureCopy("could not read the price")))
                return@flow
            }
        }
        val config = synced.config

        // **Before quoting, because quoting divides by it** (review #7). A station whose price is
        // not set yet comes back as `pricePerUnit: 0`, which parses cleanly, and
        // `litreStepMicrosFor` answers it with a `require` — thrown inside this `flow { }`, on
        // `viewModelScope`, which is process death at the pump rather than a refused sale.
        //
        // There is no fallback to the device's own price here for the reason `quoteFor` states: the
        // server checks the amount against *its* figure, so a sale priced at anything else is
        // refused anyway. Cash is unaffected — the sync keeps the last known good price for it.
        if (!synced.hasUsablePrice) {
            emit(
                PaymentResult.Failed(
                    failure = FailureCopy(
                        customerMessage = FailureCopy.SEE_ATTENDANT,
                        attendantDetail = "Balanceè has no price set for this pump, so a card sale " +
                            "cannot be started. Set this pump's price on Balanceè, then try again. " +
                            "Cash sales still work.",
                        recoverable = true,
                    ),
                ),
            )
            return@flow
        }

        val quote = quoteFor(request, synced.koboPerLitre)
        if (quote.amountKobo <= 0) {
            emit(
                PaymentResult.Failed(
                    failure = FailureCopy(
                        customerMessage = FailureCopy.AMOUNT_TOO_SMALL,
                        attendantDetail = "That amount does not buy any fuel at the current price.",
                    ),
                ),
            )
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
                emit(PaymentResult.Failed(failure = result.error.toFailureCopy("could not start the sale")))
                return@flow
            }
        }

        val checkoutUrl = authorised.authorizationUrl
        if (checkoutUrl.isNullOrBlank()) {
            // A 200 with nothing to scan is worse than a refusal: the screen would show a QR-shaped
            // hole and the customer would wait at it. #46 made this field nullable; this is the
            // caller that has to care.
            emit(
                PaymentResult.Failed(
                    failure = FailureCopy(
                        customerMessage = FailureCopy.SEE_ATTENDANT,
                        attendantDetail = "The sale started but the server returned no payment page, " +
                            "so there was nothing to scan. Nothing was charged — start a new sale, " +
                            "and report it if it repeats.",
                    ),
                    transactionRef = authorised.transactionId,
                ),
            )
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
     * **What ends the poll early is deliberately a short list**: `PAID`, `DISPENSED`, a coded
     * refusal the shared taxonomy calls final, and a device with no credentials to ask with.
     * Everything else — an unrecognised status string, a reply that would not parse, a 500, no
     * signal, **and since the 2026-09-20 review a 401** — keeps polling until the deadline.
     *
     * That 401 is worth naming, because it used to end the poll and should never have. A tablet
     * whose clock drifts past the signing window, or one NTP corrects mid-payment, returns a
     * code-less 401 that the taxonomy read as an unrecognised refusal. The poll gave up and a
     * customer who had paid was shown a failure. It is `RETRY_LATER` now, so this rides to the
     * deadline like every other thing the app cannot classify.
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
                        // **Both halves of the previous wording were false**, and the 10g gate
                        // proved it (2026-09-19): it said the *server's* window had expired and
                        // that nothing had been charged. The server does not close these — 3m16s
                        // past `expiresAt` it still answered 200 / PENDING_PAYMENT with a live
                        // checkout URL — and because this pump stops polling here, whether money
                        // arrived afterwards is precisely the thing it no longer knows.
                        //
                        // So neither line claims it. The window that closed is ours, and an
                        // attendant is pointed at the transaction rather than told a customer is
                        // mistaken. Saying "nothing was charged" is the one sentence that turns a
                        // recoverable mix-up into a customer being sent away.
                        failure = FailureCopy(
                            customerMessage = "This pump stopped waiting for the payment. " +
                                "If you have already paid, please see the attendant.",
                            attendantDetail = "The pump stopped waiting when the payment window " +
                                "elapsed. The server does not close these on its own, so a " +
                                "payment made after this may still have gone through — check " +
                                "this transaction before treating it as unpaid.",
                        ),
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
                            failure = result.error.toFailureCopy("could not confirm the payment"),
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
     *
     * **This never throws, and the ordering is why it must not.** By the time it is called the
     * sale exists on the server and its checkout page is payable; the only thing still standing
     * between the customer and a QR is this write. A database that is full or corrupt would
     * otherwise put a Room exception through `flow { }` and out into the collector's
     * `viewModelScope` — the app dies, no QR is ever drawn, and a live payable transaction is left
     * with nobody watching it. A lost audit line is a smaller loss than that, so the sale wins and
     * the loss is made noisy instead of fatal.
     *
     * The sentence is logged on the way down rather than merely counted, because it is the only
     * remaining copy of a figure nobody can reconstruct later: `synced.previousKoboPerLitre` is
     * gone from the device the moment the sync overwrote it.
     */
    private suspend fun recordPriceRaceIfAny(
        request: PaymentRequest,
        synced: SyncedConfig,
        quote: SaleQuote,
        transactionRef: String,
    ) {
        if (request.basis != SaleBasis.Dispensed || !synced.priceChanged) return
        val detail = "Price changed during this fill-up: " +
            "${formatNaira(synced.previousKoboPerLitre!!)} → ${formatNaira(synced.koboPerLitre)} per litre. " +
            "Displayed ${formatNaira(request.amountKobo)}, charged ${formatNaira(quote.amountKobo)}."
        // Cancellation is rethrown rather than swallowed: `paymentJob` is cancelled by a
        // double-tap guard, a customer cancel and a boot resume, and a flow that absorbed it here
        // would go on to emit a Pending into a collector that has gone away.
        runCatchingCancellable {
            events.record(
                type = EventType.PRICE_CHANGED_MID_SALE,
                transactionRef = transactionRef,
                detail = detail,
            )
        }.onFailure {
            android.util.Log.e(
                "PaymentProcessor",
                "Could not record the mid-sale price change for $transactionRef: $detail",
                it,
            )
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
