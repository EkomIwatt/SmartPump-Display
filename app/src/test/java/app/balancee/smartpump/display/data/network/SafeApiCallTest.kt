package app.balancee.smartpump.display.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SafeApiCallTest {

    // ---- safeApiCall exception mapping ----

    @Test
    fun `success wraps the value`() = runTest {
        val result = safeApiCall { 42 }
        assertEquals(ApiResult.Success(42), result)
    }

    @Test
    fun `not-activated maps to NotActivated`() = runTest {
        val result = safeApiCall { throw PumpNotActivatedException() }
        assertEquals(ApiResult.Failure(ApiError.NotActivated), result)
    }

    @Test
    fun `IOException maps to Network`() = runTest {
        val boom = IOException("socket reset")
        val result = safeApiCall { throw boom }
        assertEquals(ApiResult.Failure(ApiError.Network(boom)), result)
    }

    @Test
    fun `SerializationException maps to Serialization`() = runTest {
        val boom = SerializationException("bad json")
        val result = safeApiCall<Int> { throw boom }
        assertEquals(ApiResult.Failure(ApiError.Serialization(boom)), result)
    }

    @Test
    fun `unexpected throwable maps to Unknown`() = runTest {
        val boom = IllegalStateException("nope")
        val result = safeApiCall<Int> { throw boom }
        assertEquals(ApiResult.Failure(ApiError.Unknown(boom)), result)
    }

    @Test
    fun `cancellation is never swallowed`() = runTest {
        try {
            safeApiCall<Int> { throw CancellationException("cancelled") }
            fail("CancellationException must propagate, not become an ApiError")
        } catch (_: CancellationException) {
            // expected
        }
    }

    // ---- retryingApiCall ----

    @Test
    fun `succeeds first try without retrying`() = runTest {
        var calls = 0
        val result = retryingApiCall {
            calls++
            ApiResult.Success("ok")
        }
        assertEquals(ApiResult.Success("ok"), result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries a retryable failure then succeeds`() = runTest {
        var calls = 0
        val result = retryingApiCall {
            calls++
            if (calls < 2) ApiResult.Failure(ApiError.Network(IOException())) else ApiResult.Success("ok")
        }
        assertEquals(ApiResult.Success("ok"), result)
        assertEquals(2, calls)
    }

    @Test
    fun `does not retry a non-retryable failure`() = runTest {
        var calls = 0
        val result = retryingApiCall {
            calls++
            ApiResult.Failure(ApiError.Http(400, "bad request"))
        }
        assertEquals(ApiResult.Failure(ApiError.Http(400, "bad request")), result)
        assertEquals(1, calls)
    }

    @Test
    fun `gives up after maxAttempts on a persistent retryable failure`() = runTest {
        var calls = 0
        val failure = ApiResult.Failure(ApiError.Http(503, null))
        val result = retryingApiCall(maxAttempts = 3) {
            calls++
            failure
        }
        assertEquals(failure, result)
        assertEquals(3, calls)
    }

    @Test
    fun `5xx is retryable but 4xx is not`() {
        assertTrue(ApiError.Http(500, null).isRetryable)
        assertTrue(ApiError.Http(503, null).isRetryable)
        assertTrue(ApiError.Network(IOException()).isRetryable)
        assertTrue(!ApiError.Http(404, null).isRetryable)
        assertTrue(!ApiError.NotActivated.isRetryable)
        assertTrue(!ApiError.Serialization(IOException()).isRetryable)
    }
}
