// Catalogue A, as wired (10e 2/2).
//
// Two kinds of assertion live here, and the second kind is the one that matters. The per-row tests
// pin *which* row fires, keyed on the server's code — those will grow as codes are observed. The
// invariants pin the properties the copy exists to guarantee: nothing the server said ever reaches
// the customer-facing display, and a *not yet* is never presented as a dead end. Rows get reworded;
// those two must not break.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.domain.model.FailureCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiErrorCopyTest {

    private val context = "could not start the sale"

    private fun business(message: String?, code: String? = null, httpCode: Int? = 400) =
        ApiError.Business(message, code, httpCode).toFailureCopy(context)

    // ---- the keyed rows -------------------------------------------------------------------------

    @Test
    fun `an amount mismatch points the attendant at the price and stays recoverable`() {
        // The message is production's, not the Reference's — they differ, which is why nothing
        // here keys on prose.
        val copy = business(
            "The sale amount does not match the current station price for this fuel type. " +
                "Refresh the pump price and try again.",
            code = PumpErrorCodes.AMOUNT_MISMATCH,
        )

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("Pump settings"))
        assertTrue("an attendant who fixes the price can sell", copy.recoverable)
    }

    @Test
    fun `an unconfirmed payment reads as not yet, to both of them`() {
        val copy = business(
            "Payment has not been confirmed for this transaction. Do not dispense until payment " +
                "is confirmed.",
            code = PumpErrorCodes.PAYMENT_NOT_CONFIRMED,
        )

        // Not PAYMENT_NOT_COMPLETED: telling a customer their payment failed would send them away
        // from a sale that is about to confirm.
        assertEquals(FailureCopy.PAYMENT_NOT_CONFIRMED, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("DO NOT DISPENSE"))
    }

    @Test
    fun `an unknown transaction says nothing was charged`() {
        val copy = business("No transaction was found for this id.", PumpErrorCodes.TRANSACTION_NOT_FOUND)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("nothing was charged"))
        assertTrue(copy.recoverable)
    }

    /** INVALID_REQUEST covers many causes, so the server's own sentence is the only thing that says which. */
    @Test
    fun `a malformed request is reported as a software fault, quoting the server`() {
        val copy = business("An activation code is required.", PumpErrorCodes.INVALID_REQUEST)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("An activation code is required."))
        assertFalse("a software fault will not fix itself", copy.recoverable)
    }

    /**
     * TODO #18f's rule: business refusals carry a code, authentication failures carry none and are
     * identified by their 401. Both observed 401 bodies look exactly like this.
     */
    @Test
    fun `a 401 with no code reads as rejected credentials`() {
        val copy = business("Invalid API key", code = null, httpCode = 401)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("re-activating"))
        assertTrue(copy.attendantDetail.contains("Invalid API key"))
        assertFalse(copy.recoverable)
    }

    /**
     * The other condition in the 401 bucket, and the reason the bucket is not one row. A clock more
     * than five minutes off server UTC returns this — no code, observed twice on production at the
     * #32 gate. "It may need re-activating" would send an attendant to the one screen that cannot
     * fix it. TODO #15's mapping half, closed here.
     */
    @Test
    fun `a stale-timestamp 401 sends the attendant to the clock, not to re-activation`() {
        val copy = business("Request timestamp is not fresh", code = null, httpCode = 401)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("automatic date and time"))
        assertFalse("re-activating a pump will not fix its clock", copy.attendantDetail.contains("re-activating"))
        assertTrue("an attendant can fix this one from the tablet", copy.recoverable)
    }

    /** If the backend rewords it, the match is lost — and what is left must still be safe. */
    @Test
    fun `a reworded stale-timestamp 401 degrades to the credentials line`() {
        val copy = business("Your request timestamp has gone off", code = null, httpCode = 401)

        assertTrue(copy.attendantDetail!!.contains("rejected (401)"))
        assertFalse(copy.recoverable)
    }

    // ---- the last row, the one that must exist --------------------------------------------------

    /**
     * The Reference's list will not stay complete. An error this build has never been told about
     * degrades to showing the attendant exactly what came back — code, status and all — rather than
     * being swallowed into a sentence that says nothing.
     */
    @Test
    fun `an unrecognised code is quoted verbatim to the attendant`() {
        val copy = business("PETROL is currently out of stock", code = "OUT_OF_STOCK", httpCode = 409)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        val detail = copy.attendantDetail!!
        assertTrue(detail.contains("PETROL is currently out of stock"))
        assertTrue("the code is what gets read down a phone", detail.contains("OUT_OF_STOCK"))
        assertTrue(detail.contains("409"))
        assertFalse("unknown means unknown, not retry", copy.recoverable)
    }

    @Test
    fun `a refusal with no code and no 401 also falls to the last row`() {
        val copy = business("Something the server has not explained", code = null, httpCode = 422)

        assertTrue(copy.attendantDetail!!.contains("Something the server has not explained"))
        assertFalse(copy.recoverable)
    }

    @Test
    fun `a refusal with no message at all still produces a usable line`() {
        val copy = business(null, code = null, httpCode = 422)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertNotNull(copy.attendantDetail)
        assertTrue(copy.attendantDetail!!.startsWith("Could not start the sale"))
    }

    /** The banner is a flat strip above the three action cards; an unbounded string pushes them off it. */
    @Test
    fun `an absurdly long server message is cut rather than left to run`() {
        val copy = business("x".repeat(500), code = null, httpCode = 422)

        assertTrue(copy.attendantDetail!!.length < 300)
        assertTrue(copy.attendantDetail.contains("…"))
    }

    // ---- the transport rows ---------------------------------------------------------------------

    @Test
    fun `no connectivity names the station's internet, and can be retried`() {
        val copy = ApiError.Network(IOException("no route to host")).toFailureCopy(context)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("internet"))
        assertTrue(copy.recoverable)
    }

    @Test
    fun `an unactivated pump says so to the attendant and not to the customer`() {
        val copy = ApiError.NotActivated.toFailureCopy(context)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertTrue(copy.attendantDetail!!.contains("not activated"))
        assertFalse(copy.recoverable)
    }

    /** A 5xx passes on its own; a 4xx that is not a business refusal will not. */
    @Test
    fun `a 5xx invites a retry and a 404 does not`() {
        assertTrue(ApiError.Http(503, "upstream down").toFailureCopy(context).recoverable)
        assertFalse(ApiError.Http(404, "<html>Cannot GET</html>").toFailureCopy(context).recoverable)
    }

    @Test
    fun `a non-envelope body is kept, because it is the only clue to what answered`() {
        val copy = ApiError.Http(502, "<html>502 Bad Gateway</html>").toFailureCopy(context)

        assertTrue(copy.attendantDetail!!.contains("502 Bad Gateway"))
    }

    @Test
    fun `an unreadable reply is a software fault, not a retry`() {
        val copy = ApiError.Serialization(RuntimeException("Unexpected JSON token")).toFailureCopy(context)

        assertEquals(FailureCopy.SEE_ATTENDANT, copy.customerMessage)
        assertFalse(copy.recoverable)
    }

    // ---- the invariants -------------------------------------------------------------------------

    /**
     * The whole principle in one assertion. A customer can act on "see attendant" and cannot act on
     * a station's stock level, an API key or an HTTP status — so no matter what comes off the wire,
     * the customer-facing display shows one of the approved lines and nothing else.
     */
    @Test
    fun `nothing the server said ever reaches the customer line`() {
        val poison = "Invalid API key for pump SN-TEST-001 — signingSecret rejected"
        val approved = setOf(
            FailureCopy.SEE_ATTENDANT,
            FailureCopy.PAYMENT_NOT_COMPLETED,
            FailureCopy.PAYMENT_NOT_CONFIRMED,
            FailureCopy.AMOUNT_TOO_SMALL,
        )

        val everyShape = listOf(
            ApiError.Business(poison, null, 401),
            ApiError.Business(poison, "OUT_OF_STOCK", 409),
            ApiError.Business(poison, PumpErrorCodes.INVALID_REQUEST, 400),
            ApiError.Business(poison, PumpErrorCodes.AMOUNT_MISMATCH, 400),
            ApiError.Business(poison, PumpErrorCodes.PAYMENT_NOT_CONFIRMED, 409),
            ApiError.Business(poison, PumpErrorCodes.TRANSACTION_NOT_FOUND, 404),
            ApiError.Http(500, poison),
            ApiError.Network(IOException(poison)),
            ApiError.Serialization(RuntimeException(poison)),
            ApiError.Unknown(RuntimeException(poison)),
            ApiError.NotActivated,
        )

        everyShape.forEach { error ->
            val copy = error.toFailureCopy(context)
            assertTrue("$error leaked a server string", approved.contains(copy.customerMessage))
        }
    }

    /**
     * The one point where #45's taxonomy reaches into the copy. A `RETRY_LATER` failure is the
     * server saying *not yet*, and a screen that paints it red tells an attendant a sale is dead
     * when it is seconds from confirming.
     */
    @Test
    fun `a retry-later refusal is never shown as a dead end`() {
        PumpErrorCodes.NOT_YET.forEach { code ->
            val error = ApiError.Business("whatever the server says", code, 409)
            assertEquals(RetryPolicy.RETRY_LATER, error.retryPolicy)
            assertTrue("$code must not read as a failed sale", error.toFailureCopy(context).recoverable)
        }
    }

    /** Every row has somewhere for the attendant to look. A blank banner is worse than no banner. */
    @Test
    fun `every failure carries an attendant line`() {
        val everyShape = listOf(
            ApiError.Business("x", PumpErrorCodes.AMOUNT_MISMATCH, 400),
            ApiError.Business(null, null, null),
            ApiError.Http(500, null),
            ApiError.Network(IOException()),
            ApiError.Serialization(RuntimeException()),
            ApiError.Unknown(RuntimeException()),
            ApiError.NotActivated,
        )

        everyShape.forEach { error ->
            val detail = error.toFailureCopy(context).attendantDetail
            assertNotNull("$error had nothing for the attendant", detail)
            assertTrue("$error had an empty line", detail!!.isNotBlank())
        }
    }
}
