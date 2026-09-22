// docs/journal/closed/ERROR_COPY_DRAFT.md Catalogue A, wired (TODO #14's copy half, 10e 2/2).
//
// **Matching is on the server's `code`, and on the 401 where there is no code. Never on a string
// nobody has seen.** That is not caution for its own sake: the Reference PDF quotes "Amount mismatch
// for PETROL…", and what production actually returns for that same `AMOUNT_MISMATCH` is "The sale
// amount does not match the current station price for this fuel type. Refresh the pump price and
// try again." (docs/api-probes/2026-09-16-prod-gate/). The one Reference string this project has
// been able to compare against the wire had already been reworded. A table keyed on the other
// eleven would mostly not fire, and would fail silently on the day it stopped.
//
// So the rows that can be keyed are keyed, and everything else takes the last row of Catalogue A —
// the one the draft says must exist: the customer gets the same plain line, and the attendant gets
// the server's own words verbatim plus the HTTP status. An unrecognised error degrades to showing
// an attendant exactly what came back rather than being swallowed.
//
// Catalogue A's remaining rows (out of stock, invalid station price, fuel type not sold here) stay
// drafted and unwired, each waiting on one observation of the code it arrives with — the same rule
// PumpErrorCodes.NOT_YET follows, and for the same reason: a guess is copy nobody agreed, aimed at
// a string nobody has seen.
//
// The single exception is the clock-skew 401, which HAS been observed and which the credentials
// line would actively mislead an attendant about — see `unauthorisedCopy`.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.domain.model.FailureCopy
import app.balancee.smartpump.display.domain.model.FailureCopy.Companion.PAYMENT_NOT_CONFIRMED
import app.balancee.smartpump.display.domain.model.FailureCopy.Companion.SEE_ATTENDANT

/**
 * What the customer reads and what the attendant reads, for a failure that came off the wire.
 *
 * [context] is a short lowercase phrase naming what the app was doing — "could not start the sale",
 * "could not read the price". It never reaches the customer; it opens the attendant's line on the
 * paths where the failure itself does not say what was being attempted.
 */
fun ApiError.toFailureCopy(context: String): FailureCopy = when (this) {
    is ApiError.Business -> businessCopy(context)

    // Catalogue B's connectivity row. Recoverable: the station's internet comes back.
    is ApiError.Network -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = "The pump cannot reach Balanceè. Check the station's internet, then retry.",
        recoverable = true,
    )

    is ApiError.NotActivated -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = "This pump is not activated. It cannot sell until it is activated in Pump settings.",
        recoverable = false,
    )

    // Not a refusal from this API at all — an HTML 404, a proxy's plain-text 502, a captive portal.
    // The body is kept because it is the only thing that will tell anyone what answered.
    is ApiError.Http -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = context.opening() + " — HTTP " + code + " from the server" +
            (body?.takeIf { it.isNotBlank() }?.let { ": " + it.trim().ellipsised() } ?: ".") +
            if (code in SERVER_ERRORS) " The server is having trouble; retrying may work." else "",
        // A 5xx passes on its own; a 4xx that is not a business refusal will not.
        recoverable = code in SERVER_ERRORS,
    )

    is ApiError.Serialization -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = context.opening() +
            " — the server's reply could not be read. Software fault; report it.",
        recoverable = false,
    )

    is ApiError.Unknown -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = context.opening() + " — unexpected fault" +
            (cause.message?.takeIf { it.isNotBlank() }?.let { ": " + it.ellipsised() } ?: ".") +
            " Report it.",
        recoverable = false,
    )
}

/**
 * The keyed half of Catalogue A.
 *
 * The `recoverable` column is authored per row rather than derived from [retryPolicy], because the
 * two answer different questions — see [FailureCopy.recoverable]. Where they meet is
 * [PumpErrorCodes.PAYMENT_NOT_CONFIRMED]: it is `RETRY_LATER`, so it must not read to an attendant
 * as a failed sale, which is the one line the taxonomy half reaches into this one. The draft's
 * table wrote that row as not recoverable; it was written before #45 existed, and #45 is right.
 */
private fun ApiError.Business.businessCopy(context: String): FailureCopy = when (code) {

    PumpErrorCodes.AMOUNT_MISMATCH -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = "The price on this pump does not match the station's. " +
            "Open Pump settings, re-check the price, then start the sale again.",
        recoverable = true,
    )

    PumpErrorCodes.PAYMENT_NOT_CONFIRMED -> FailureCopy(
        customerMessage = PAYMENT_NOT_CONFIRMED,
        attendantDetail = "Balanceè has not seen this payment yet — DO NOT DISPENSE. " +
            "It may confirm on its own, so wait and then retry. Refund the customer if it never does.",
        // Not a dead end: the server's answer changes on its own (RETRY_LATER).
        recoverable = true,
    )

    // **Do not tell an attendant nothing was charged.** The draft did, and the 10g gate showed
    // why that is unsafe (2026-09-19): this code came back on a fill-up the customer had *just
    // paid* ₦149 for, because the upload quoted an id the server had never issued. The money was
    // real; only our reference was wrong. A refusal to recognise a reference says nothing
    // whatsoever about whether a payment happened, and the attendant is the one person who can
    // still check before the customer leaves.
    PumpErrorCodes.TRANSACTION_NOT_FOUND -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = "Balanceè does not recognise this sale's reference. That does not mean " +
            "the customer was not charged — check the payment before starting a new sale.",
        recoverable = true,
    )

    // INVALID_REQUEST covers everything the server found malformed, so the server's own sentence is
    // the only thing that says which — it is carried rather than paraphrased.
    PumpErrorCodes.INVALID_REQUEST -> FailureCopy(
        customerMessage = SEE_ATTENDANT,
        attendantDetail = "The pump sent a request the server rejected as malformed. " +
            "Software fault; report it" + serverSaid(),
        recoverable = false,
    )

    // No code at all. Per TODO #18f that is the signature of an authentication failure, and the 401
    // confirms it — every observed 401 body carries a message and no code, and every observed
    // business refusal carries a code.
    null -> if (httpCode == HTTP_UNAUTHORIZED) unauthorisedCopy() else unrecognised(context)

    // A code this build has never been told about. It is quoted rather than interpreted: an
    // attendant reading it out to whoever is on the phone is worth more than a sentence invented
    // here for a refusal nobody has seen.
    else -> unrecognised(context)
}

/**
 * The 401 bucket, which holds two conditions the server gives no way to tell apart but a string.
 *
 * **This is the one prose match in the file, and it earns its exception by observation.** A clock
 * more than five minutes off server UTC returns `401 {"status":false,"message":"Request timestamp
 * is not fresh"}` — no code, captured twice on production at the #32 gate
 * (`docs/api-probes/2026-09-16-prod-gate/`) — and it is indistinguishable from a rejected API key
 * by status alone. Telling an attendant to re-activate a pump whose clock is wrong sends them to
 * the one screen that cannot fix it, so TODO #15 asked for these to read differently and they do.
 *
 * The rule the rest of the file follows is not "never match prose", it is **"never match a string
 * nobody has seen"**. This one has been seen on this API's wire, unlike the Reference's rewritten
 * catalogue. And if the backend rewords it, this degrades to the credentials line below: still
 * terminal, still pointing at a person, never wrong about whether the sale can proceed.
 */
private fun ApiError.Business.unauthorisedCopy(): FailureCopy =
    if (message?.contains(STALE_TIMESTAMP, ignoreCase = true) == true) {
        FailureCopy(
            customerMessage = SEE_ATTENDANT,
            attendantDetail = "This tablet's clock is wrong, so the server rejected the request. " +
                "Turn on automatic date and time in Android settings, then try the sale again.",
            // The one 401 an attendant can fix from the tablet.
            recoverable = true,
        )
    } else {
        FailureCopy(
            customerMessage = SEE_ATTENDANT,
            attendantDetail = "This pump's credentials were rejected (401). " +
                "It may need re-activating" + serverSaid(),
            recoverable = false,
        )
    }

/**
 * Catalogue A's last row, and the one that must exist. The Reference's list will not stay complete,
 * and an unrecognised refusal has to degrade to showing the attendant exactly what came back.
 *
 * Terminal on purpose: the app cannot tell a temporary refusal from a permanent one without being
 * told, and inviting a retry it has no basis for wastes a customer's time standing at the pump.
 */
private fun ApiError.Business.unrecognised(context: String) = FailureCopy(
    customerMessage = SEE_ATTENDANT,
    attendantDetail = context.opening() + " — the server declined it" +
        (httpCode?.let { " (HTTP $it)" } ?: "") +
        (code?.let { " [$it]" } ?: "") +
        serverSaid(),
    recoverable = false,
)

/** The server's own sentence, verbatim and quoted so it reads as theirs rather than as ours. */
private fun ApiError.Business.serverSaid(): String =
    message?.takeIf { it.isNotBlank() }
        ?.let { ". Server said: \"" + it.trim().ellipsised() + "\"" }
        ?: "."

/** "could not start the sale" → "Could not start the sale". */
private fun String.opening(): String = replaceFirstChar { it.uppercase() }

/**
 * A server message has been one sentence on every path observed, but the attendant banner is a flat
 * strip on a tablet and an unbounded string would push the three action cards off it.
 */
private fun String.ellipsised(limit: Int = 160): String =
    if (length <= limit) this else take(limit - 1).trimEnd() + "…"

private const val HTTP_UNAUTHORIZED = 401

/** The stable fragment of the observed clock-skew 401. Short on purpose: values get interpolated. */
private const val STALE_TIMESTAMP = "timestamp is not fresh"
private val SERVER_ERRORS = 500..599
