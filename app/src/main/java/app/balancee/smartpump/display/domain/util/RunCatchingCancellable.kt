// `runCatching`, minus the one thing it must never catch.
//
// Kotlin's `runCatching` catches `Throwable`, and inside a coroutine that includes the
// `CancellationException` the machinery throws to unwind a cancelled job. Swallowing it does not
// stop the cancellation — it stops this coroutine *noticing*, so the lines after the guarded call
// run anyway, in a job everything else believes is dead.
//
// On this app's money paths that is not academic. `expiryJob` is cancelled the instant a payment
// succeeds; the expiry coroutine may at that moment be suspended inside a Room write on the IO
// dispatcher. With a plain `runCatching` the cancellation is absorbed and the very next statement
// — `setState(Idle)` — runs, wiping a sale that was just paid for.
//
// The rethrow-then-catch shape is `safeApiCall`'s, which has had it since the network layer was
// built. This is the same rule for the side effects that are not network calls.
package app.balancee.smartpump.display.domain.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Run [block], returning its failure as a [Result] instead of throwing — except for cancellation,
 * which is rethrown so the calling coroutine still unwinds.
 *
 * Use it for a side effect the caller is willing to lose: an audit row, a cached copy, a
 * best-effort write. Not for anything whose failure the caller has to act on, which wants a real
 * branch rather than a swallowed exception.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
