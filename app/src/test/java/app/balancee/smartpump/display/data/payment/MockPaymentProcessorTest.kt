// Phase 10a — the mock processor fills in the fields the real one will.
//
// Why bother testing a mock: the debug path and every ViewModel test run through it, so if it
// stopped shaping its Pending like the real thing, the screens built in 10c would be exercised
// against something the backend never sends. The 20-minute expiry in particular is the mock
// carrying TODO #43's measured figure rather than the 5 minutes the app used to assume.
package app.balancee.smartpump.display.data.payment

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.SaleBasis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class MockPaymentProcessorTest {

    private val now: Instant = Instant.parse("2026-09-17T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun processor() = MockPaymentProcessor(clock)

    private val request = PaymentRequest(
        method = PaymentMethod.BALANCEE_APP,
        amountKobo = 500_000,
        expectedLitres = 5.0,
        basis = SaleBasis.Tender,
    )

    @Test
    fun `pending carries a checkout url, an expiry and a payment reference`() = runTest {
        val pending = processor().process(request).first() as PaymentResult.Pending

        assertNotNull(pending.checkoutUrl)
        assertNotNull(pending.expiresAt)
        assertNotNull(pending.paymentReference)
        assertEquals(PaymentMethod.BALANCEE_APP, pending.method)
    }

    /**
     * 20 minutes, measured five times on production (TODO #43). Asserted off an injected fixed
     * clock so the figure is checked rather than waited for.
     */
    @Test
    fun `mock expiry is the 20 minutes production actually gives, not 5`() = runTest {
        val pending = processor().process(request).first() as PaymentResult.Pending

        assertEquals(now.plus(Duration.ofMinutes(20)), pending.expiresAt)
    }

    /**
     * A mock QR must not be payable. If it pointed at a reachable host, a demo build's QR scanned
     * by accident on a forecourt would be a real checkout page.
     */
    @Test
    fun `mock checkout url points at an unresolvable host`() = runTest {
        val pending = processor().process(request).first() as PaymentResult.Pending

        assertTrue(
            "mock checkout url must not be reachable: ${pending.checkoutUrl}",
            pending.checkoutUrl!!.contains(".invalid/"),
        )
    }

    /**
     * The upload job (10f) quotes the payment reference, so Success has to carry it — a dispense
     * cannot be reported without one.
     */
    @Test
    fun `success carries the same payment reference as the pending it resolves`() = runTest {
        val p = processor()
        p.setPendingDelayMs(0)

        val results = p.process(request).toList()
        val pending = results.first() as PaymentResult.Pending
        val success = results.last() as PaymentResult.Success

        assertEquals(pending.paymentReference, success.paymentReference)
        assertEquals(pending.transactionRef, success.transactionRef)
        assertEquals(500_000L, success.amountKobo)
    }

    @Test
    fun `a declined payment still reports the ref it was declining`() = runTest {
        val p = processor()
        p.setPendingDelayMs(0)
        p.setAutoApprove(false)

        val results = p.process(request).toList()
        val pending = results.first() as PaymentResult.Pending
        val failed = results.last() as PaymentResult.Failed

        assertEquals(pending.transactionRef, failed.transactionRef)
    }
}
