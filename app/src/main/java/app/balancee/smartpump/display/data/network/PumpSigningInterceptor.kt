// OkHttp interceptor that signs every outbound Pump API request per the reference:
// injects X-Api-Key / X-Device-Id / X-Timestamp / X-Signature. Endpoints annotated @Unsigned
// (the public /activate call) pass through untouched.
//
// Runs on OkHttp's dispatcher thread, so it reads credentials synchronously via
// PumpCredentialsStore.current(). It signs the ALREADY-serialized request body (buffered straight
// off the request) and only adds headers — so the bytes on the wire are exactly what was signed,
// honouring the "do not re-serialize after signing" rule.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import retrofit2.Invocation
import java.io.IOException
import java.time.Clock

class PumpSigningInterceptor(
    private val credentialsStore: PumpCredentialsStore,
    private val clock: Clock,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // @Unsigned endpoints (activation) run before credentials exist — pass through.
        val unsigned = request.tag(Invocation::class.java)
            ?.method()
            ?.isAnnotationPresent(Unsigned::class.java) == true
        if (unsigned) return chain.proceed(request)

        val credentials = credentialsStore.current()
            ?: throw IOException("Pump API request requires signing but the device is not activated")

        val timestamp = PumpRequestSigner.timestamp(clock.instant())
        val bodyString = request.body?.let { body ->
            Buffer().use { buffer ->
                body.writeTo(buffer)
                val charset = body.contentType()?.charset() ?: Charsets.UTF_8
                buffer.readString(charset)
            }
        } ?: ""

        val signature = PumpRequestSigner.signature(credentials.signingSecret, timestamp, bodyString)

        val signed = request.newBuilder()
            .header("X-Api-Key", credentials.apiKey)
            .header("X-Device-Id", credentials.deviceId)
            .header("X-Timestamp", timestamp)
            .header("X-Signature", signature)
            .build()

        return chain.proceed(signed)
    }
}
