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
sealed interface AmountPlan {
    data class Exact(val naira: Long) : AmountPlan
    data class Fractional(val naira: Double) : AmountPlan
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
            AuthoriseVariant.Happy -> when (plan) {
                is AmountPlan.Exact -> client.authorise(
                    AuthoriseRequest(
                        pumpId = config.pumpId,
                        transactionId = transactionId,
                        amount = plan.naira,
                        expectedLitres = litres,
                        fuelType = config.fuelType,
                    ),
                )
                // Not a failure to report as an error: it is the answer to #18c, arrived at before
                // sending anything. These litres cannot be expressed in whole naira, so the happy
                // path IS the decimal case.
                is AmountPlan.Fractional -> return@probe fractionalSummary(plan, litres, config)
            }

            AuthoriseVariant.Mismatch -> {
                val base = when (plan) {
                    is AmountPlan.Exact -> plan.naira
                    is AmountPlan.Fractional -> Math.round(plan.naira)
                }
                client.authorise(
                    AuthoriseRequest(
                        pumpId = config.pumpId,
                        transactionId = transactionId,
                        amount = base + 1,
                        expectedLitres = litres,
                        fuelType = config.fuelType,
                    ),
                )
            }

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
        val now = clock.instant()

        client.uploadTransaction(
            UploadTransactionRequest(
                pumpId = state.config?.pumpId ?: state.pumpId.orEmpty(),
                transactionId = authorised.transactionId,
                paymentReference = authorised.paymentReference,
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

private fun fractionalSummary(
    plan: AmountPlan.Fractional,
    litres: Double,
    config: PumpConfigResponse,
): ProbeSummary = ProbeSummary(
    tone = ProbeTone.Caution,
    headline = "Cannot be expressed in whole naira — #18c, answered by arithmetic",
    detail = "$litres L x ${config.pricePerUnit} = ${plan.naira}, which `amount: Long` cannot " +
        "carry. The server checks amount == expectedLitres x pricePerUnit exactly, so a rounded " +
        "figure is refused rather than accepted a few kobo out.\n\nNothing was sent. Use the " +
        "decimal probe to find out whether the server takes a fractional amount — if it does not, " +
        "every fill-up whose litres do not land on a whole naira is unauthorisable, and station " +
        "pricing has to be constrained to make that impossible.",
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
            },
    )

    is ApiResult.Failure -> when {
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
