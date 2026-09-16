// A deliberately wrong clock, for one question nobody can answer from outside: what does the server
// say when a signed request's timestamp is stale?
//
// TODO #15 has been half-built since August. The mapping half distinguishes "Request timestamp is
// not fresh" from "Invalid request timestamp" — two different causes — but those strings are our
// reading of the Reference, never observed, and the dev probe proved they are unreachable without
// credentials: the server validates the API key first, so a two-hour-old timestamp and a missing
// signature both come back as `Invalid API key`. With credentials, the question becomes answerable
// by sending one request from the past.
//
// Scope is kept as narrow as it can be. The offset applies ONLY to the clock the signing
// interceptor reads, never to the app's clock: audit rows, receipts and the fuel log must not move
// because someone pressed a probe button. And [ProbeClockOffset.set] is inert outside debug builds,
// so a release build cannot be talked into signing with a false time.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.BuildConfig
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** How far request signing should be shifted. Zero in every build that is not being probed. */
@Singleton
class ProbeClockOffset @Inject constructor() {

    private val offset = AtomicReference(Duration.ZERO)

    val current: Duration get() = offset.get()

    /** No-op outside debug builds — the release app has no way to sign with a false time. */
    fun set(value: Duration) {
        if (BuildConfig.DEBUG) offset.set(value)
    }

    fun clear() = offset.set(Duration.ZERO)

    /** Run [block] with the signing clock shifted, then put it back even if [block] throws. */
    suspend fun <T> shiftedBy(value: Duration, block: suspend () -> T): T {
        set(value)
        return try {
            block()
        } finally {
            clear()
        }
    }
}

/**
 * [delegate] plus whatever [offset] currently holds, read per call rather than at construction —
 * the offset changes while this clock is alive, which a `Clock.offset(...)` snapshot could not do.
 */
class ProbeClock(
    private val delegate: Clock,
    private val offset: ProbeClockOffset,
) : Clock() {

    override fun getZone(): ZoneId = delegate.zone

    override fun withZone(zone: ZoneId): Clock = ProbeClock(delegate.withZone(zone), offset)

    override fun instant(): Instant = delegate.instant().plus(offset.current)
}
