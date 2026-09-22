// Backing VM for the debug-only API probe panel — the thing that makes TODO #32 runnable.
//
// #32 insists the gate sequence be driven through PumpApiClient rather than curl, because what is
// under test is our signing, our envelope parsing and our credential store; a curl script would
// test a second implementation we do not ship. Before 9d-1, activate() was the only client method
// with a caller anywhere in ui/, so steps 2-7 had no button.
//
// Stage 9d-1 built step 2. Stage 9d-2 adds the rest, split by what they cost to press:
//
//   Read-only, safe against any server: GET /config, GET /transactions/{id}, and the clock-skew
//   probe (a /config signed from the past, which is the only way to observe #15's strings).
//
//   Creates records: /authorise and its two variants, and /transactions/upload. /authorise returns
//   a Paystack checkout URL, so on production it initialises a real payment even against a
//   throwaway pump. These sit behind an explicit acknowledgement rather than a bare button.
package app.balancee.smartpump.display.ui.probe

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.ProbeCapture
import app.balancee.smartpump.display.data.network.ProbeCaptureFormat
import app.balancee.smartpump.display.data.network.ProbeClockOffset
import app.balancee.smartpump.display.data.network.ProbeResponseRecorder
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.nairaForSale
import app.balancee.smartpump.display.data.network.dto.AuthoriseResponse
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.data.network.dto.TransactionStatusResponse
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.data.network.dto.UploadTransactionResponse
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

enum class ProbeTone { Success, Caution, Failure }

/** What one probe call is reported as. Kept out of the composable so it can be unit-tested. */
data class ProbeSummary(
    val tone: ProbeTone,
    val headline: String,
    val detail: String,
)

/** Which shape of /authorise to send. The variants are the point, not an afterthought. */
enum class AuthoriseVariant {
    /** amount = price × litres, exactly as the server's own check computes it. */
    Happy,

    /** amount deliberately one naira out — reveals whether refusals carry a stable code (#18f). */
    Mismatch,

    /** A decimal amount, which our Long-typed DTO cannot express. Sent as raw JSON (#18c). */
    Decimal,

    /**
     * Four decimal places of litres, and the 4dp amount that is their exact product.
     *
     * **The question this exists to answer** (raised in 10b, decides 10c): a pre-pay customer hands
     * over a round sum, and litres quoted at 2dp cannot spend all of it — ₦5,000 at ₦1,490/L buys
     * 3.35 L, worth ₦4,991.50, and the server's exact check refuses the ₦5,000 that was actually
     * tendered. Quoting finer litres shrinks that shortfall from ₦14.90 at worst to under a kobo,
     * **if** the server accepts more than the one decimal place we have observed it take.
     *
     * Nothing about this is inferable from the Reference, and guessing it wrong means either giving
     * away fuel or refusing sales. So it is measured.
     */
    Precision,
}

/**
 * What `price × litres` comes to, and whether integer naira can say it.
 *
 * This is the arithmetic TODO #18c is really about. The server checks
 * `amount == expectedLitres × pricePerUnit` **exactly**, and at the observed ₦1490/L any litre
 * figure with a fractional part that is not a multiple of 1/10 produces a fractional naira amount —
 * ₦1490 × 2.35 L = ₦3,501.50. If `amount` must be a whole number, that sale cannot be authorised at
 * all: a rounded 3501 is not off by fifty kobo, it is **rejected**.
 */
/**
 * What a pre-pay sale looks like when litres are quoted to [scale] decimal places.
 *
 * This is the whole 10b finding expressed as arithmetic. The customer tenders a round sum; the pump
 * can only promise litres to some finite precision; and the server accepts the sale only if the
 * amount is **exactly** the product. So the amount that can be charged is the product, and whatever
 * the tendered sum exceeds it by is [shortfall] — fuel the customer paid for and does not get.
 *
 * `RoundingMode.DOWN` on the litres, never UP: the floor is what stops the pump giving away more
 * fuel than was paid for, and that rule outranks tidiness.
 */
data class PrecisionQuote(
    val litres: BigDecimal,
    val amount: BigDecimal,
    /** Tendered minus chargeable. Always >= 0, and always the customer's loss. */
    val shortfall: BigDecimal,
)

internal fun precisionQuote(
    tenderedNaira: BigDecimal,
    koboPerLitre: Long,
    scale: Int,
): PrecisionQuote {
    val price = BigDecimal.valueOf(koboPerLitre, 2)
    val litres = tenderedNaira.divide(price, scale, RoundingMode.DOWN)
    val amount = litres.multiply(price)
    return PrecisionQuote(litres = litres, amount = amount, shortfall = tenderedNaira.subtract(amount))
}

/** The round sum a customer would plausibly hand over for [litres] — the next whole naira up. */
internal fun tenderedFor(litres: Double, koboPerLitre: Long): BigDecimal =
    nairaForSale(litres, koboPerLitre).setScale(0, RoundingMode.CEILING)

sealed interface AmountPlan {
    data class Exact(val naira: Long) : AmountPlan
    data class Fractional(val naira: Double) : AmountPlan

    /**
     * What actually goes on the wire. Both branches are now sendable — `amount` is a `BigDecimal`
     * since TODO #44 — so the distinction survives only to *tell the operator* which case a litre
     * figure lands on, which is still worth seeing on a probe screen. It is no longer a gate.
     */
    val wireAmount: BigDecimal
        get() = when (this) {
            is Exact -> BigDecimal.valueOf(naira)
            is Fractional -> BigDecimal.valueOf(naira)
        }
}

internal fun amountFor(litres: Double, pricePerUnit: Long): AmountPlan {
    val exact = litres * pricePerUnit
    val rounded = Math.round(exact)
    // Tolerance, not equality: 1490 * 2.3 is 3426.9999999999995 in binary floating point, and a
    // probe that called that "fractional" would be reporting its own arithmetic, not the server's.
    return if (abs(exact - rounded) < AMOUNT_EPSILON) {
        AmountPlan.Exact(rounded)
    } else {
        AmountPlan.Fractional(exact)
    }
}

private const val AMOUNT_EPSILON = 1e-6

data class ApiProbeUiState(
    val baseUrl: String = BuildConfig.PUMP_API_BASE_URL,
    val activated: Boolean = false,
    val pumpId: String? = null,
    val running: Boolean = false,
    val summary: ProbeSummary? = null,
    val captures: List<ProbeCapture> = emptyList(),
    val savedPath: String? = null,
    val saveError: String? = null,
    /** Last successful /config, which is where the price and fuel for an authorise come from. */
    val config: PumpConfigResponse? = null,
    /** Typed by hand, or filled in by the last authorise. */
    val transactionId: String = "",
    val litres: String = "2.0",
    /** Gate on everything that creates a record. Off by default, every time the panel opens. */
    val writesAcknowledged: Boolean = false,
    /** The last authorise, so status and upload have something real to talk about. */
    val lastAuthorise: AuthoriseResponse? = null,
) {
    val productionServer: Boolean get() = baseUrl.contains("//api.balancee.app")

    val canProbe: Boolean get() = !running && activated

    val litresValue: Double? get() = litres.trim().toDoubleOrNull()?.takeIf { it > 0 }

    /** Null until a /config has landed — an authorise built on a guessed price is not a test. */
    val amountPlan: AmountPlan?
        get() = config?.let { c -> litresValue?.let { amountFor(it, c.pricePerUnit) } }

    val canWrite: Boolean get() = canProbe && writesAcknowledged && amountPlan != null

    val canUpload: Boolean get() = canProbe && writesAcknowledged && lastAuthorise != null
}

@HiltViewModel
class ApiProbeViewModel @Inject constructor(
    private val client: PumpApiClient,
    private val recorder: ProbeResponseRecorder,
    private val credentials: PumpCredentialsStore,
    private val clockOffset: ProbeClockOffset,
    private val clock: Clock,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        ApiProbeUiState(
            activated = credentials.isActivated,
            pumpId = credentials.current()?.pumpId,
        ),
    )
    val ui: StateFlow<ApiProbeUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            recorder.captures.collect { captures -> _ui.update { it.copy(captures = captures) } }
        }
    }

    fun setTransactionId(value: String) = _ui.update { it.copy(transactionId = value.trim()) }

    fun setLitres(value: String) = _ui.update { it.copy(litres = value) }

    fun setWritesAcknowledged(value: Boolean) = _ui.update { it.copy(writesAcknowledged = value) }

    // ---- read-only ------------------------------------------------------------------------

    /** #32 step 2. */
    fun probeConfig() = probe {
        val result = client.config()
        _ui.update { it.copy(config = (result as? ApiResult.Success)?.data) }
        result.toConfigSummary()
    }

    /**
     * #32 step 5. Safe with any id: a transaction that does not exist is itself the finding, since
     * nobody has seen what this endpoint says about one.
     */
    fun probeStatus() = probe {
        val id = _ui.value.transactionId.ifBlank { UNKNOWN_TRANSACTION_ID }
        client.transactionStatus(id).toStatusSummary(id)
    }

    /**
     * #32 step 6, second half — TODO #15. Signs a /config from [SKEW] in the past and reads what
     * comes back. Creates nothing; the request is rejected or it is not.
     */
    fun probeClockSkew() = probe {
        val result = clockOffset.shiftedBy(SKEW.negated()) { client.config() }
        result.toSkewSummary()
    }

    // ---- creates records ------------------------------------------------------------------

    /** #32 steps 3 and 4. */
    fun probeAuthorise(variant: AuthoriseVariant) = probe {
        val state = _ui.value
        val config = state.config ?: return@probe notReadySummary("a /config")
        val litres = state.litresValue ?: return@probe notReadySummary("a litres figure")
        val plan = amountFor(litres, config.pricePerUnit)
        val transactionId = "probe-${UUID.randomUUID()}"

        val result = when (variant) {
            // Both branches send now. Until #44 this refused on a fractional amount, because
            // `amount` was a Long and the sale genuinely could not be expressed — that refusal is
            // how #18c was first answered, and it is kept in the log rather than in the code.
            AuthoriseVariant.Happy -> client.authorise(
                AuthoriseRequest(
                    pumpId = config.pumpId,
                    transactionId = transactionId,
                    amount = nairaForSale(litres, config.pricePerUnit * 100),
                    expectedLitres = litres,
                    fuelType = config.fuelType,
                ),
            )

            AuthoriseVariant.Mismatch -> client.authorise(
                AuthoriseRequest(
                    pumpId = config.pumpId,
                    transactionId = transactionId,
                    // Deliberately one naira off the exact product, so the server's own check is
                    // what refuses it rather than anything of ours.
                    amount = nairaForSale(litres, config.pricePerUnit * 100).add(BigDecimal.ONE),
                    expectedLitres = litres,
                    fuelType = config.fuelType,
                ),
            )

            AuthoriseVariant.Precision -> {
                val tendered = tenderedFor(litres, config.pricePerUnit * 100)
                val fine = precisionQuote(tendered, config.pricePerUnit * 100, scale = 4)
                client.authorise(
                    AuthoriseRequest(
                        pumpId = config.pumpId,
                        transactionId = transactionId,
                        amount = fine.amount,
                        expectedLitres = fine.litres.toDouble(),
                        fuelType = config.fuelType,
                    ),
                )
            }

            // Kept on authoriseRaw even though the typed client can now carry a decimal: this probe
            // exists to ask what the SERVER does with a body we would never build, and routing it
            // through the DTO would only ever re-test our own serializer.
            AuthoriseVariant.Decimal -> client.authoriseRaw(
                JsonObject(
                    mapOf(
                        "pumpId" to JsonPrimitive(config.pumpId),
                        "transactionId" to JsonPrimitive(transactionId),
                        // The whole point: a fractional amount our own DTO cannot carry.
                        "amount" to JsonPrimitive(
                            when (plan) {
                                is AmountPlan.Exact -> plan.naira + 0.5
                                is AmountPlan.Fractional -> plan.naira
                            },
                        ),
                        "expectedLitres" to JsonPrimitive(litres),
                        "fuelType" to JsonPrimitive(config.fuelType.name),
                    ),
                ),
            )
        }

        (result as? ApiResult.Success)?.data?.let { authorised ->
            _ui.update {
                it.copy(lastAuthorise = authorised, transactionId = authorised.transactionId)
            }
        }
        result.toAuthoriseSummary(variant)
    }

    /** #32 step 7. Needs the ids only a real authorise can produce. */
    fun probeUpload() = probe {
        val state = _ui.value
        val authorised = state.lastAuthorise ?: return@probe notReadySummary("an /authorise")
        val litres = state.litresValue ?: return@probe notReadySummary("a litres figure")
        // #46 made this nullable and the compiler found this call site, which is the point: the
        // endpoint demands a paymentReference and only /authorise issues one. Sending an empty
        // string would trade a clear "nothing was sent" for an opaque server refusal.
        val reference = authorised.paymentReference
            ?: return@probe notReadySummary("an /authorise that returned a paymentReference")
        val now = clock.instant()

        client.uploadTransaction(
            UploadTransactionRequest(
                pumpId = state.config?.pumpId ?: state.pumpId.orEmpty(),
                transactionId = authorised.transactionId,
                paymentReference = reference,
                actualLitresDispensed = litres,
                startedAt = ISO.format(now.minusSeconds(UPLOAD_WINDOW_SECONDS)),
                completedAt = ISO.format(now),
            ),
        ).toUploadSummary()
    }

    // ---- plumbing -------------------------------------------------------------------------

    /**
     * One place that owns the running flag, the credential refresh and the summary, so each probe
     * above is only the call it makes. [block] returns the summary to show.
     */
    private fun probe(block: suspend () -> ProbeSummary) {
        if (!_ui.value.canProbe) return
        _ui.update { it.copy(running = true, summary = null, savedPath = null, saveError = null) }
        viewModelScope.launch {
            val summary = block()
            _ui.update {
                it.copy(
                    running = false,
                    summary = summary,
                    activated = credentials.isActivated,
                    pumpId = credentials.current()?.pumpId,
                )
            }
        }
    }

    /**
     * Write the captures somewhere adb pull can reach. External files dir rather than internal:
     * run-as needs a debuggable package and this tablet's logcat has already proved unreliable
     * (7h), so the file has to be readable without either.
     */
    fun saveCaptures() {
        viewModelScope.launch {
            val at = clock.instant()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(context.getExternalFilesDir(null), CAPTURE_DIR).apply { mkdirs() }
                    File(dir, ProbeCaptureFormat.fileName(at)).apply {
                        writeText(
                            ProbeCaptureFormat.render(
                                baseUrl = BuildConfig.PUMP_API_BASE_URL,
                                capturedAt = at,
                                captures = _ui.value.captures,
                            ),
                        )
                    }
                }
            }
            _ui.update { state ->
                state.copy(
                    savedPath = result.getOrNull()?.absolutePath,
                    saveError = result.exceptionOrNull()?.let { e ->
                        "${e.javaClass.simpleName}: ${e.message}"
                    },
                )
            }
        }
    }

    fun clearCaptures() {
        recorder.clear()
        _ui.update { it.copy(summary = null, savedPath = null, saveError = null) }
    }

    private companion object {
        const val CAPTURE_DIR = "api-captures"
        const val UNKNOWN_TRANSACTION_ID = "probe-not-a-real-id"
        const val UPLOAD_WINDOW_SECONDS = 180L
        val SKEW: Duration = Duration.ofMinutes(10)
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}

// ---- outcome → words ----------------------------------------------------------------------
//
// Pure functions, so the judgement each probe makes is testable without a ViewModel, a server or a
// device. Failures share one mapper: what an ApiError means does not change with the endpoint, and
// two copies of that reasoning would be two things to keep true.

private fun notReadySummary(missing: String): ProbeSummary = ProbeSummary(
    tone = ProbeTone.Caution,
    headline = "Nothing sent",
    detail = "This probe needs $missing first. Nothing left the device.",
)

internal fun ApiResult<PumpConfigResponse>.toConfigSummary(): ProbeSummary = when (this) {
    is ApiResult.Success -> ProbeSummary(
        tone = ProbeTone.Success,
        headline = "200 OK — ${data.fuelType.name} at ${data.pricePerUnit}",
        detail = "station: ${data.stationName}\npump: ${data.pumpId}\n" +
            "price/L: ${data.pricePerUnit} (naira — inferred from magnitude, never stated; #18c)\n" +
            "updated: ${data.updatedAt}\n\n" +
            "A 200 here also proves GET signing: we sign timestamp + \".\" + \"\" for a body-less " +
            "request and the server accepted it.",
    )

    is ApiResult.Failure -> error.toSummary()
}

internal fun ApiResult<TransactionStatusResponse>.toStatusSummary(id: String): ProbeSummary =
    when (this) {
        is ApiResult.Success -> ProbeSummary(
            tone = ProbeTone.Success,
            headline = "200 OK — status ${data.status}",
            detail = "transaction: ${data.transactionId}\n" +
                "paymentReference: ${data.paymentReference ?: "(absent)"}\n\n" +
                "Every status string observed here belongs in #18d — the real set has only ever " +
                "been guessed at (\"PENDING_PAYMENT\", \"PAID\", \"DISPENSED\").",
        )

        is ApiResult.Failure -> error.toSummary().let {
            it.copy(detail = "asked about: $id\n\n${it.detail}")
        }
    }

/**
 * A stale-timestamp probe reads backwards from every other one: a **refusal is the success**. A 200
 * means the server did not mind a request signed ten minutes ago, which says the freshness window
 * TODO #15 is built around may not exist at all.
 */
internal fun ApiResult<PumpConfigResponse>.toSkewSummary(): ProbeSummary = when (this) {
    is ApiResult.Success -> ProbeSummary(
        tone = ProbeTone.Caution,
        headline = "Accepted a request signed 10 minutes ago",
        detail = "No freshness window, or one wider than ten minutes. #15's mapping is built " +
            "around \"Request timestamp is not fresh\" — a string we have never observed and now " +
            "have reason to doubt applies here.",
    )

    is ApiResult.Failure -> when (val e = error) {
        is ApiError.Business -> ProbeSummary(
            tone = ProbeTone.Success,
            headline = "Refused, as a stale timestamp should be",
            detail = "This is the observation #15 has been waiting for since August. Copy it " +
                "verbatim into the error mapping:\nmessage: ${e.message ?: "(none)"}\n" +
                "code: ${e.code ?: "(absent)"}\nhttp: ${e.httpCode ?: "(none)"}",
        )

        else -> error.toSummary()
    }
}

internal fun ApiResult<AuthoriseResponse>.toAuthoriseSummary(
    variant: AuthoriseVariant,
): ProbeSummary = when (this) {
    is ApiResult.Success -> ProbeSummary(
        tone = if (variant == AuthoriseVariant.Happy) ProbeTone.Success else ProbeTone.Caution,
        headline = when (variant) {
            AuthoriseVariant.Happy -> "200 OK — ${data.status}"
            AuthoriseVariant.Mismatch -> "ACCEPTED a deliberately wrong amount"
            AuthoriseVariant.Decimal -> "ACCEPTED a decimal amount — #18c answered: yes"
            AuthoriseVariant.Precision ->
                "ACCEPTED 4dp litres — pre-pay can quote finely, shortfall under a kobo"
        },
        detail = "transaction: ${data.transactionId}\nreference: ${data.paymentReference}\n" +
            "expires: ${data.expiresAt}\nauthorizationUrl: ${data.authorizationUrl}\n\n" +
            when (variant) {
                AuthoriseVariant.Happy ->
                    "A real Paystack initialisation now exists. Do not scan the QR."
                AuthoriseVariant.Mismatch ->
                    "The server was expected to REFUSE this — the amount was one naira out. That " +
                        "it did not means the amount == litres x price check is not enforced the " +
                        "way the Reference describes, which is a finding worth more than the test."
                AuthoriseVariant.Decimal ->
                    "A fractional amount is accepted, so fill-ups are authorisable without " +
                        "constraining station prices to whole naira. Change amount to a decimal " +
                        "type before the payment flows are built (#8)."
                AuthoriseVariant.Precision ->
                    "Four decimal places of litres are accepted and the exact check passed on the " +
                        "4dp product. 10c should quote pre-pay litres at 4dp: the customer's " +
                        "shortfall drops from up to 0.01 x price (about 15 naira at this price) to " +
                        "under one kobo. Nothing here changes what the pump physically stops at."
            },
    )

    is ApiResult.Failure -> when {
        // A refusal here is NOT the hoped-for outcome, it is the other half of the answer: 4dp is
        // rejected, so 10c must quote pre-pay litres at 2dp and accept the shortfall (or re-price).
        // Reported as a caution rather than a success precisely so it does not read as a passing test.
        variant == AuthoriseVariant.Precision && error is ApiError.Business -> ProbeSummary(
            tone = ProbeTone.Caution,
            headline = "REFUSED 4dp litres — pre-pay must quote at 2dp",
            detail = "message: ${(error as ApiError.Business).message ?: "(none)"}\n" +
                "code: ${(error as ApiError.Business).code ?: "(absent)"}\n\n" +
                "The server will not take four decimal places. 10c therefore quotes pre-pay litres " +
                "at 2dp and the customer's shortfall is up to 0.01 x price. Worth trying 3dp before " +
                "settling — and worth telling the backend, because this is the difference between " +
                "spending a customer's money and keeping a bit of it.",
        )

        // The refusal we were hoping for. Its `code` is the whole question behind #18f.
        variant != AuthoriseVariant.Happy && error is ApiError.Business -> ProbeSummary(
            tone = ProbeTone.Success,
            headline = "Refused, as intended",
            detail = "message: ${(error as ApiError.Business).message ?: "(none)"}\n" +
                "code: ${(error as ApiError.Business).code ?: "(absent — #18f: matching the prose is all we would have)"}\n\n" +
                "Copy the message verbatim into the fixtures; it is interpolated prose and will " +
                "not survive a reword, which is exactly why a stable code was asked for.",
        )

        else -> error.toSummary()
    }
}

internal fun ApiResult<UploadTransactionResponse>.toUploadSummary(): ProbeSummary = when (this) {
    is ApiResult.Success -> ProbeSummary(
        tone = ProbeTone.Success,
        headline = "200 OK — ${data.status}",
        detail = "transaction: ${data.transactionId}\nreference: ${data.paymentReference}\n\n" +
            "The ingest endpoint works, which is what the upload job (7e) was waiting to be told.",
    )

    is ApiResult.Failure -> error.toSummary()
}

/** One mapper for every failure — an ApiError means the same thing whichever endpoint returned it. */
internal fun ApiError.toSummary(): ProbeSummary = when (this) {
    is ApiError.Business -> ProbeSummary(
        tone = ProbeTone.Failure,
        headline = "Refused" + (httpCode?.let { " ($it)" } ?: ""),
        detail = "The server answered and said no.\nmessage: ${message ?: "(none)"}\n" +
            "code: ${code ?: "(absent — as on every 401 we have captured, #18f)"}",
    )

    is ApiError.Http -> ProbeSummary(
        tone = ProbeTone.Failure,
        headline = "HTTP $code, not an envelope",
        detail = "The body was not the status/message/data envelope — an HTML 404 from a wrong " +
            "base URL, or a proxy error.\n" + (body?.take(ERROR_BODY_CHARS) ?: "(empty body)"),
    )

    ApiError.NotActivated -> ProbeSummary(
        tone = ProbeTone.Caution,
        headline = "Not activated",
        detail = "The signing interceptor had no credentials, so the call never left the device. " +
            "Redeem an activation code in the panel above first.",
    )

    is ApiError.Serialization -> ProbeSummary(
        tone = ProbeTone.Failure,
        headline = "Answered, but unparseable",
        detail = "This is the finding the gate exists to produce: the server's shape and our DTO " +
            "disagree. The raw body below is what the fixture must be rebuilt from.\n" +
            "${cause.javaClass.simpleName}: ${cause.message}",
    )

    is ApiError.Network -> ProbeSummary(
        tone = ProbeTone.Caution,
        headline = "No answer",
        detail = "Nothing came back: ${cause.javaClass.simpleName}: ${cause.message}",
    )

    is ApiError.Unknown -> ProbeSummary(
        tone = ProbeTone.Failure,
        headline = "Unknown failure",
        detail = "${cause.javaClass.simpleName}: ${cause.message}",
    )
}

private const val ERROR_BODY_CHARS = 400
