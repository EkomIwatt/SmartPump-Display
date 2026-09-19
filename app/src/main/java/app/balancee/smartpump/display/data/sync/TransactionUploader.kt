// Phase 10f. The thing that finally reports a dispense to the backend, and the thing that finally
// sets `syncedAt`.
//
// It is a plain class rather than a Worker on purpose: WorkManager decides *when* this runs and
// what to do when it asks to be run again, and that is all the Android in the job. Everything that
// can be wrong — which records are eligible, what a refusal means, when a record may be considered
// closed — lives here, where a JVM test can put it through a server that says no in six different
// ways without an emulator.
//
// The one outcome this exists to prevent is a dispense that never reaches the backend. Everything
// below is arranged around that: nothing is deleted, nothing is marked sent that was not, and the
// only record ever abandoned is one the server has refused for a reason that will not change — and
// even then the row stays, carrying the reason.
package app.balancee.smartpump.display.data.sync

import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.RetryPolicy
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.data.network.retryPolicy
import app.balancee.smartpump.display.data.network.toFailureCopy
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import app.balancee.smartpump.display.domain.repository.EventRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** What the caller should do next. The worker maps these onto WorkManager's three results. */
enum class UploadRun {
    /** Every eligible record is now on the backend, or there were none. */
    SETTLED,

    /**
     * At least one record could not be sent for a reason that may pass — no signal, a 500, a
     * payment the backend has not confirmed yet, or a pump with no credentials to sign with.
     * Ask again later. Nothing was marked.
     */
    RETRY,
}

@Singleton
class TransactionUploader @Inject constructor(
    private val transactions: TransactionRepository,
    private val credentials: PumpCredentialsStore,
    private val client: PumpApiClient,
    private val events: EventRepository,
    private val clock: Clock,
) {

    /**
     * Send every record that is still eligible, oldest first.
     *
     * Oldest first, and it keeps going after a failure rather than stopping at the first one: a
     * single record the server refuses for good must not hold the rest of the day's sales behind
     * it. The run is only [UploadRun.RETRY] if something *transient* happened.
     */
    suspend fun uploadPending(): UploadRun {
        val pending = transactions.getPendingSync()
        if (pending.isEmpty()) return UploadRun.SETTLED

        // No credentials means this device cannot sign anything, which says nothing about the
        // records themselves. They wait. Condemning them here would throw away a day of real
        // dispenses over a state that a re-activation fixes.
        val pumpId = credentials.current()?.pumpId ?: return UploadRun.RETRY

        var retry = false
        for (record in pending) {
            when (upload(record, pumpId)) {
                UploadRun.RETRY -> retry = true
                UploadRun.SETTLED -> Unit
            }
        }
        return if (retry) UploadRun.RETRY else UploadRun.SETTLED
    }

    private suspend fun upload(record: Transaction, pumpId: String): UploadRun {
        // The query already excludes these, so reaching here means the query and this agree no
        // longer. Treat it as ineligible rather than sending a request that cannot be honoured.
        val reference = record.paymentReference?.takeIf { it.isNotBlank() } ?: return UploadRun.SETTLED

        val completedAt = record.createdAt
        // A record written before 10f has no start time. The window is then zero-length rather
        // than invented, and the event trail says which records were reported that way.
        val startedAt = record.startedAt ?: completedAt

        val result = client.uploadTransaction(
            UploadTransactionRequest(
                pumpId = pumpId,
                transactionId = record.id,
                paymentReference = reference,
                // What actually flowed, not what was authorised. #47 confirmed the backend accepts
                // the difference, so a partial dispense, an attendant's early end (OQ #22) and 7h's
                // recovered pulses are all reported honestly instead of being rounded to the quote.
                actualLitresDispensed = record.litresDispensed,
                startedAt = ISO_8601.format(Instant.ofEpochMilli(startedAt)),
                completedAt = ISO_8601.format(Instant.ofEpochMilli(completedAt)),
            ),
        )

        return when (result) {
            is ApiResult.Success -> {
                // **#48.** The first write is the only one that counts: a second upload carrying a
                // corrected figure returns `200 Transaction recorded` and changes nothing. So this
                // mark is what closes the record for good, and nothing in the app re-sends after it.
                transactions.markSynced(record.id, clock.millis())
                UploadRun.SETTLED
            }

            is ApiResult.Failure -> handle(record, result.error)
        }
    }

    /**
     * What a refusal means for this record, decided by the #45 taxonomy and never by the prose.
     *
     * `RETRY_LATER` is the case that taxonomy exists for. `PAYMENT_NOT_CONFIRMED` is a 409 that
     * parses as a considered refusal and is not one — the backend has not seen the money land yet,
     * which it may do a minute later. Reading it as final here is the precise way this job would
     * destroy the record it exists to protect.
     */
    private suspend fun handle(record: Transaction, error: ApiError): UploadRun = when {
        // Credentials disappeared mid-run (a reinstall, a revoke). A device state, not a verdict
        // on this sale, so it waits — even though the shared taxonomy calls NotActivated terminal.
        error is ApiError.NotActivated -> UploadRun.RETRY

        error.retryPolicy != RetryPolicy.TERMINAL -> UploadRun.RETRY

        else -> {
            // Abandoned, but never silently. The row keeps its place in the audit log, stops being
            // offered to this job, and carries the attendant-facing reason 10e already wrote for
            // exactly this failure — so a person reading the pump log sees what the server said
            // rather than a record that simply stopped trying.
            val reason = error.toFailureCopy("could not report this dispense")
                .attendantDetail
                ?: "The server refused this upload and gave no reason."
            transactions.markUploadFailed(record.id, reason)
            events.record(
                type = EventType.DISPENSE_UPLOAD_FAILED,
                transactionRef = record.transactionRef,
                detail = "${"%.2f".format(record.litresDispensed)} L was dispensed and the station's " +
                    "record of it was refused. $reason",
            )
            UploadRun.SETTLED
        }
    }

    private companion object {
        /** The format every observed request and response uses. UTC, because the server's is. */
        val ISO_8601: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}
