// Backing VM for the debug-only API probe panel — the thing that makes TODO #32 runnable.
//
// #32 insists the gate sequence be driven through PumpApiClient rather than curl, because what is
// under test is our signing, our envelope parsing and our credential store; a curl script would
// test a second implementation we do not ship. But until now activate() was the only client method
// with a caller anywhere in ui/, so steps 2-7 had no button to press. This is the button.
//
// Stage 9d-1 covers step 2 only (GET /config, read-only). The transaction-creating steps are
// deliberately absent until there is a server it is acceptable to dirty.
package app.balancee.smartpump.display.ui.probe

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.ProbeCapture
import app.balancee.smartpump.display.data.network.ProbeCaptureFormat
import app.balancee.smartpump.display.data.network.ProbeResponseRecorder
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
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
import java.io.File
import java.time.Clock
import javax.inject.Inject

enum class ProbeTone { Success, Caution, Failure }

/** What one probe call is reported as. Kept out of the composable so it can be unit-tested. */
data class ProbeSummary(
    val tone: ProbeTone,
    val headline: String,
    val detail: String,
)

data class ApiProbeUiState(
    val baseUrl: String = BuildConfig.PUMP_API_BASE_URL,
    val activated: Boolean = false,
    val pumpId: String? = null,
    val running: Boolean = false,
    val summary: ProbeSummary? = null,
    val captures: List<ProbeCapture> = emptyList(),
    val savedPath: String? = null,
    val saveError: String? = null,
) {
    /**
     * True when this build points at the live backend. Surfaced loudly in the panel: the only
     * reason a debuggable build can reach production at all is the debugProd variant, and a screen
     * that does not say which server it is talking to is how a test transaction ends up in a real
     * station's records.
     */
    val productionServer: Boolean get() = baseUrl.contains("//api.balancee.app")

    val canProbe: Boolean get() = !running && activated
}

@HiltViewModel
class ApiProbeViewModel @Inject constructor(
    private val client: PumpApiClient,
    private val recorder: ProbeResponseRecorder,
    private val credentials: PumpCredentialsStore,
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

    /** #32 step 2. Read-only: the one step of the gate that is safe against any server. */
    fun probeConfig() {
        if (!_ui.value.canProbe) return
        _ui.update { it.copy(running = true, summary = null, savedPath = null, saveError = null) }
        viewModelScope.launch {
            val summary = client.config().toConfigSummary()
            // Credentials may have arrived since construction — the activation panel sits directly
            // above this one, and an operator will use them in that order.
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
    }
}

/**
 * The parsed outcome, in words. A pure function so the branch that matters can be tested without a
 * ViewModel, a server or a device.
 *
 * The case worth the most: a success carrying zero prices. PumpConfigResponse.prices defaults to an
 * empty map, so if the server names that field anything other than "prices", parsing succeeds and
 * the app quietly believes this pump sells nothing. That is defect #11's exact shape — a green
 * result agreeing with our own restatement — so it is reported as a caution, not a success, and it
 * points at the raw bytes.
 */
internal fun ApiResult<PumpConfigResponse>.toConfigSummary(): ProbeSummary = when (this) {
    is ApiResult.Success -> when {
        data.prices.isEmpty() -> ProbeSummary(
            tone = ProbeTone.Caution,
            headline = "200 OK, but zero prices parsed",
            detail = "The envelope unwrapped and the body parsed, yet prices came out empty. " +
                "Either this pump genuinely has no prices set, or the server's field name is not " +
                "\"prices\" and our DTO defaulted it away. Read the raw body below before " +
                "believing either.",
        )

        else -> ProbeSummary(
            tone = ProbeTone.Success,
            headline = "200 OK — ${data.prices.size} price(s)",
            detail = data.prices.entries.joinToString("   ") { (fuel, price) ->
                "${fuel.name} $price"
            } + "\n\nPrinted exactly as parsed, with no unit applied — whether these are kobo or " +
                "naira is still open (#18c).",
        )
    }

    is ApiResult.Failure -> when (val e = error) {
        is ApiError.Business -> ProbeSummary(
            tone = ProbeTone.Failure,
            headline = "Refused" + (e.httpCode?.let { " ($it)" } ?: ""),
            detail = "The server answered and said no.\nmessage: ${e.message ?: "(none)"}\n" +
                "code: ${e.code ?: "(absent — as on every 401 we have captured, #18f)"}",
        )

        is ApiError.Http -> ProbeSummary(
            tone = ProbeTone.Failure,
            headline = "HTTP ${e.code}, not an envelope",
            detail = "The body was not the status/message/data envelope — an HTML 404 from a wrong " +
                "base URL, or a proxy error.\n" +
                (e.body?.take(ERROR_BODY_CHARS) ?: "(empty body)"),
        )

        ApiError.NotActivated -> ProbeSummary(
            tone = ProbeTone.Caution,
            headline = "Not activated",
            detail = "The signing interceptor had no credentials, so the call never left the " +
                "device. Redeem an activation code in the panel above first.",
        )

        is ApiError.Serialization -> ProbeSummary(
            tone = ProbeTone.Failure,
            headline = "Answered, but unparseable",
            detail = "This is the finding the gate exists to produce: the server's shape and our " +
                "DTO disagree. The raw body below is what the fixture must be rebuilt from.\n" +
                "${e.cause.javaClass.simpleName}: ${e.cause.message}",
        )

        is ApiError.Network -> ProbeSummary(
            tone = ProbeTone.Caution,
            headline = "No answer",
            detail = "Nothing came back: ${e.cause.javaClass.simpleName}: ${e.cause.message}",
        )

        is ApiError.Unknown -> ProbeSummary(
            tone = ProbeTone.Failure,
            headline = "Unknown failure",
            detail = "${e.cause.javaClass.simpleName}: ${e.cause.message}",
        )
    }
}

private const val ERROR_BODY_CHARS = 400
