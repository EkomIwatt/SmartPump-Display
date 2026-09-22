// TODO #45 — the third outcome.
//
// The taxonomy had two words for failure and needed three. `PAYMENT_NOT_CONFIRMED` arrives as a 409
// with a well-formed envelope, so it parsed as `ApiError.Business`, and every `Business` was
// terminal on the grounds that it is "a considered refusal". This one is a *not yet*: the server is
// telling the pump it has not seen payment land, which it may do a minute later.
//
// The consequence was not a wrong screen. An upload job reading that as final discards the record,
// and a dispense that never reaches the backend is the one outcome the upload job exists to
// prevent — so these tests are about 10f before 10f exists.
package app.balancee.smartpump.display.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    // ---- the case #45 was raised for --------------------------------------------

    /** Observed on production, 2026-09-16: POST /transactions/upload → 409. */
    @Test
    fun `PAYMENT_NOT_CONFIRMED is retry-later, not terminal`() {
        val error = ApiError.Business(
            message = "Payment has not been confirmed for this transaction. " +
                "Do not dispense until payment is confirmed.",
            code = PumpErrorCodes.PAYMENT_NOT_CONFIRMED,
            httpCode = 409,
        )

        assertEquals(RetryPolicy.RETRY_LATER, error.retryPolicy)
    }

    /**
     * And it must NOT be retried in-flight. Three attempts over a second and a half will not
     * outlast a payment confirming, so burning them here would hand the durable queue a call that
     * has already given up.
     */
    @Test
    fun `retry-later is not retried inside the call window`() {
        val error = ApiError.Business(code = PumpErrorCodes.PAYMENT_NOT_CONFIRMED, message = null)

        assertFalse(error.isRetryable)
    }

    /** Matched on the code, never on the prose — the messages interpolate values (#18f). */
    @Test
    fun `the same wording without the code is terminal`() {
        val error = ApiError.Business(
            message = "Payment has not been confirmed for this transaction.",
            code = null,
        )

        assertEquals(RetryPolicy.TERMINAL, error.retryPolicy)
    }

    // ---- the refusals that really are refusals ------------------------------------

    /** Asking again with the same body gets the same answer. */
    @Test
    fun `the other observed business codes stay terminal`() {
        val codes = listOf(
            PumpErrorCodes.AMOUNT_MISMATCH,
            PumpErrorCodes.TRANSACTION_NOT_FOUND,
            PumpErrorCodes.INVALID_REQUEST,
        )

        for (code in codes) {
            val error = ApiError.Business(message = "refused", code = code)
            assertEquals(code, RetryPolicy.TERMINAL, error.retryPolicy)
        }
    }

    /**
     * A refusal the app has never seen is terminal, which is the safe reading: it cannot tell a
     * temporary one from a permanent one without being told, and retrying forever is worse than
     * surfacing it to a person once.
     */
    @Test
    fun `an unrecognised business code is terminal`() {
        val error = ApiError.Business(message = "something new", code = "PUMP_ON_FIRE")

        assertEquals(RetryPolicy.TERMINAL, error.retryPolicy)
    }

    // ---- the statuses that are not a verdict (2026-09-20 review, finding 1) ---------

    /** The exact bytes production returns when this tablet's clock has drifted (#32 gate). */
    private val clockSkew401 = ApiError.Business(
        message = "Request timestamp is not fresh",
        code = null,
        httpCode = 401,
    )

    /**
     * The finding. A code-less 401 fell through to "unrecognised refusal, therefore final", and
     * the upload queue acted on that by writing `uploadError` — which `getPendingSync` filters on
     * and nothing clears. A clock drifting overnight condemned every queued dispense permanently,
     * and correcting the time did not bring them back.
     */
    @Test
    fun `a code-less 401 is retry-later, not terminal`() {
        assertEquals(RetryPolicy.RETRY_LATER, clockSkew401.retryPolicy)
    }

    /**
     * The contradiction that proves it was wrong rather than merely strict: the copy layer has
     * always told an attendant this one is fixable, while the taxonomy was discarding the record.
     */
    @Test
    fun `the taxonomy now agrees with the copy layer about the clock-skew 401`() {
        assertTrue(clockSkew401.toFailureCopy("could not report this dispense").recoverable)
        assertTrue(clockSkew401.retryPolicy != RetryPolicy.TERMINAL)
    }

    /** A 401 whose body did not parse as an envelope takes the same reading. */
    @Test
    fun `a bare 401 is retry-later too`() {
        assertEquals(RetryPolicy.RETRY_LATER, ApiError.Http(401, "<html>401</html>").retryPolicy)
    }

    /** A timeout and a rate limit are statements about this attempt, not about the sale. */
    @Test
    fun `408 and 429 are retry-later`() {
        assertEquals(RetryPolicy.RETRY_LATER, ApiError.Http(408, null).retryPolicy)
        assertEquals(RetryPolicy.RETRY_LATER, ApiError.Http(429, null).retryPolicy)
        assertEquals(
            RetryPolicy.RETRY_LATER,
            ApiError.Business(message = "slow down", code = null, httpCode = 429).retryPolicy,
        )
    }

    /**
     * 403 is deliberately NOT in the set. It is a considered refusal to serve this caller, and the
     * set exists to avoid handing out "retry forever" on anything that merely looks like auth.
     */
    @Test
    fun `403 stays terminal`() {
        assertEquals(RetryPolicy.TERMINAL, ApiError.Http(403, null).retryPolicy)
    }

    /**
     * The status is read before the no-code rule, not instead of it. An envelope-level failure on a
     * 2xx has no `httpCode` at all and must still be terminal, or every unclassifiable refusal in
     * the app quietly becomes an infinite retry.
     */
    @Test
    fun `a code-less refusal with no status is still terminal`() {
        assertEquals(
            RetryPolicy.TERMINAL,
            ApiError.Business(message = "refused", code = null, httpCode = null).retryPolicy,
        )
        assertEquals(
            RetryPolicy.TERMINAL,
            ApiError.Business(message = "refused", code = null, httpCode = 400).retryPolicy,
        )
    }

    /** None of this reaches the in-flight loop: a clock is not corrected in 1.5 seconds. */
    @Test
    fun `a non-verdict status is not retried inside the call window`() {
        assertFalse(clockSkew401.isRetryable)
        assertFalse(ApiError.Http(429, null).isRetryable)
    }

    // ---- nothing else moved --------------------------------------------------------

    /** The whole point of keeping `isRetryable`: existing backoff behaviour is untouched. */
    @Test
    fun `transport failures are still retry-now`() {
        assertEquals(RetryPolicy.RETRY_NOW, ApiError.Network(IOException()).retryPolicy)
        assertEquals(RetryPolicy.RETRY_NOW, ApiError.Http(500, null).retryPolicy)
        assertEquals(RetryPolicy.RETRY_NOW, ApiError.Http(503, null).retryPolicy)

        assertTrue(ApiError.Network(IOException()).isRetryable)
        assertTrue(ApiError.Http(500, null).isRetryable)
    }

    @Test
    fun `the terminal cases are unchanged`() {
        assertEquals(RetryPolicy.TERMINAL, ApiError.Http(404, null).retryPolicy)
        assertEquals(RetryPolicy.TERMINAL, ApiError.NotActivated.retryPolicy)
        assertEquals(RetryPolicy.TERMINAL, ApiError.Serialization(IOException()).retryPolicy)
        assertEquals(RetryPolicy.TERMINAL, ApiError.Unknown(IOException()).retryPolicy)

        assertFalse(ApiError.Http(404, null).isRetryable)
        assertFalse(ApiError.NotActivated.isRetryable)
    }

    /** `isRetryable` means RETRY_NOW and only RETRY_NOW, for every member of the taxonomy. */
    @Test
    fun `isRetryable agrees with the policy everywhere`() {
        val errors = listOf(
            ApiError.Network(IOException()),
            ApiError.Http(500, null),
            ApiError.Http(404, null),
            ApiError.Business(message = null, code = PumpErrorCodes.PAYMENT_NOT_CONFIRMED),
            ApiError.Business(message = null, code = PumpErrorCodes.AMOUNT_MISMATCH),
            ApiError.NotActivated,
            ApiError.Serialization(IOException()),
            ApiError.Unknown(IOException()),
        )

        for (error in errors) {
            assertEquals(
                error.toString(),
                error.retryPolicy == RetryPolicy.RETRY_NOW,
                error.isRetryable,
            )
        }
    }
}
