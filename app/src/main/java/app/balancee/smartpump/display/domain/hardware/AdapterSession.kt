// The adapter-held sale session (Phase 11, docs/serial-protocol.md §2.1). The adapter latches its
// lifetime count as the session's `start` when it opens the relay, holds a pulse limit, and cuts
// the relay itself when `count − start` reaches it. The app's view of the sale's pulses is
// `count − start` — both numbers the adapter's own, so nothing can fall between them (#36).
package app.balancee.smartpump.display.domain.hardware

import kotlin.math.floor
import kotlin.random.Random

/** A session the adapter acknowledged: sale [tag], started at lifetime count [start]. */
data class AdapterSession(val tag: Long, val start: Long)

/**
 * The session a sale was last armed under, as this app persists it (Phase 11e) — so a restart can
 * ask the adapter whether it still holds it, and resume it instead of arming a new one.
 *
 * @param transactionRef the sale it belongs to. A saved session is only ever reused for the same
 *                       sale; the tag of an earlier sale must never open fuel for a later one.
 * @param tag            the tag the adapter was armed with. Persisted **before** the arm is sent,
 *                       so a crash between the two still leaves the app able to ask for it.
 * @param basePulses     the sale's pulses from earlier sessions, when this one was armed. The sale's
 *                       pulses are `basePulses + (count − start)`. Non-zero only after the adapter
 *                       lost a session mid-sale and the sale was re-armed for what was left.
 */
data class SaleSession(val transactionRef: String, val tag: Long, val basePulses: Int)

/** The adapter's answer to arm / resume / query (spec §3.2). */
sealed interface SessionReply {
    /** The session is open (fuel authorised) or held (resumable). */
    data class Armed(val session: AdapterSession) : SessionReply

    /** Session [tag] reached its limit; the relay was cut at lifetime count [cut]. Never re-opens. */
    data class Stopped(val tag: Long, val cut: Long) : SessionReply

    /** The adapter holds no session with that tag — it rebooted, or the tag is not its. */
    data object NoSession : SessionReply

    /** The adapter refused the frame (`ERR:CMD`) — e.g. firmware older than Phase 11. No fuel. */
    data class Refused(val code: String) : SessionReply

    /** Nothing came back in time — the link is down or the frame was lost. */
    data object NoReply : SessionReply
}

/**
 * The largest limit the adapter accepts (`MAX_LIMIT` in the firmware, spec §7). A sanity bound on a
 * garbled frame, not a business rule: 10 000 L at 100 pulses/L.
 */
const val MAX_LIMIT_PULSES: Long = 1_000_000L

/**
 * Fill-up ceiling (spec D4): the pulse limit an open-ended fill-up is armed with. A runaway
 * backstop, not a cutoff — fill-ups end on nozzle idle, in the app. It only matters if the app is
 * alive (still PINGing) but has stopped acting on that shutoff.
 *
 * **200 L is a guess** confirmed by nobody who has seen the forecourt: it must exceed the largest
 * single fill the station serves. If trucks or buses take more, raise it.
 */
const val FILLUP_CEILING_LITRES = 200.0

/**
 * The pulse limit for [litres], rounded **down** — the adapter must never deliver more than was
 * paid for.
 *
 * The epsilon is load-bearing. Litre figures are floored to 0.01, and few are exact in binary:
 * 1.15 × 100.0 is 114.99999999999999, which a bare floor turns into 114 — so do 137 of the 2 000
 * figures from 0.01 L to 20 L. The adapter would then stop one pulse short, the app's litre figure
 * would sit just under its cutoff, and the sale would wait for a completion that never comes. A
 * millionth of a pulse cannot round a real shortfall up.
 */
fun litresToLimitPulses(litres: Double, pulsesPerLitre: Double = PULSES_PER_LITRE): Long =
    floor(litres * pulsesPerLitre + LIMIT_EPSILON_PULSES).toLong()

private const val LIMIT_EPSILON_PULSES = 1e-6

/**
 * A fresh tag for a new sale: random, non-zero, unsigned 32-bit (spec D2). Random rather than a
 * counter because a counter restarts on reinstall and could match a stale session still held on
 * the adapter; a random tag collides about once in four billion sales.
 */
fun newSessionTag(random: Random = Random.Default): Long {
    while (true) {
        val tag = random.nextInt().toLong() and 0xFFFF_FFFFL
        if (tag != 0L) return tag
    }
}
