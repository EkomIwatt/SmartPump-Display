// Real RelayController — drives the Arduino relay over the shared USB-serial link.
// Replaces MockRelayController behind the RelayController interface; selected by HardwareModule
// when MOCK_HARDWARE == false.
//
// Phase 11 (docs/serial-protocol.md): fuel only ever opens under a pulse limit the ADAPTER
// enforces, inside a session tagged by the app. Every command is a request with a reply:
//   RLY:1:<limit>:<tag> → ARM:<tag>:<start>   (or STOP / ERR:NOSESSION / ERR:CMD)
//   RES:<tag>           → ARM / STOP / ERR:NOSESSION
//   SES?                → ARM / STOP / ERR:NOSESSION
//   RLY:0               → (no reply; the adapter HOLDS the session)
// A lost reply is recovered by sending the same frame again: the adapter treats a same-tag RLY:1
// as a resume, so a retry can never grant a second allowance (spec §4, rule 1).
//
// Automatic resume (spec §5, D5): while a sale is active, the controller sends RES:<tag> — never a
// fresh RLY:1 — when the link comes back, when the adapter reports its watchdog tripped, and when
// the adapter rebooted. The adapter re-opens only what is left under the limit it already holds.
// (Before Phase 11 the link-up re-assert sent a bare RLY:1, which with a limit attached would have
// handed a sale that stopped at 9 of 10 L a fresh 10 L.)
package app.balancee.smartpump.display.data.hardware

import android.util.Log
import app.balancee.smartpump.display.data.hardware.serial.SerialCommands
import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import app.balancee.smartpump.display.domain.hardware.AdapterSession
import app.balancee.smartpump.display.domain.hardware.MAX_LIMIT_PULSES
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.hardware.SessionReply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbSerialRelayController internal constructor(
    private val link: SerialLink,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : RelayController {

    @Inject constructor(link: SerialLink) :
        this(link, CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO)

    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    private val _session = MutableStateFlow<AdapterSession?>(null)
    override val session: StateFlow<AdapterSession?> = _session.asStateFlow()

    /** The sale this app wants fuel for; null = none. What the automatic resume resumes. */
    @Volatile private var activeTag: Long? = null

    /** One request on the wire at a time, so a reply can only belong to the request waiting. */
    private val requests = Mutex()

    init {
        scope.launch {
            var wasUp = link.connected.value
            link.connected.collect { up ->
                if (up && !wasUp) autoResume("link back")
                wasUp = up
            }
        }
        scope.launch {
            link.frames.collect { frame ->
                when (frame) {
                    // The adapter cut the relay at the limit. The sale's fuel is done; the view
                    // model hears it from the pulse source.
                    is SerialFrame.Stop -> if (frame.tag == activeTag) {
                        activeTag = null
                        _isDispensing.value = false
                        Log.i(TAG, "Adapter stopped sale ${frame.tag} at its limit (count ${frame.cut})")
                    }
                    is SerialFrame.Error -> if (frame.code == "WDOG") autoResume("adapter watchdog tripped")
                    is SerialFrame.Boot -> autoResume("adapter rebooted")
                    else -> Unit
                }
            }
        }
    }

    override suspend fun startFuelFlow(limitPulses: Long, tag: Long): SessionReply {
        if (limitPulses !in 1..MAX_LIMIT_PULSES || tag !in 1..U32_MAX) {
            // Would only ever earn ERR:CMD from the adapter. Refuse here, loudly, with no fuel.
            Log.e(TAG, "Refusing to arm: limit $limitPulses / tag $tag out of range")
            return SessionReply.Refused(REFUSED_LOCALLY)
        }
        link.ensureStarted()
        activeTag = tag
        val line = SerialCommands.arm(limitPulses, tag)
        val reply = requests.withLock { requestWithRetries(line, tag) }
        settle(reply, tag)
        if (reply is SessionReply.NoReply) {
            // The adapter may have armed with every acknowledgement lost. Nothing here will count
            // that sale, so make sure the relay is off rather than trust that it never opened.
            if (activeTag == tag) activeTag = null
            writeRelayOff()
            Log.e(TAG, "No ARM for sale $tag after $ARM_ATTEMPTS attempts; fuel NOT authorised")
        }
        return reply
    }

    override suspend fun resumeFuelFlow(tag: Long): SessionReply {
        if (tag !in 1..U32_MAX) return SessionReply.Refused(REFUSED_LOCALLY)
        activeTag = tag                                   // an explicit resume claims the sale
        return resume(tag)
    }

    /**
     * Resume [tag] without claiming it. The automatic path uses this: it must only ever resume a
     * sale that is still active, and [requestWithRetries] sends nothing once [activeTag] has moved
     * on — so a stop that lands between the trigger and the send wins.
     */
    private suspend fun resume(tag: Long): SessionReply {
        link.ensureStarted()
        val reply = requests.withLock { requestWithRetries(SerialCommands.resume(tag), tag) }
        // NoReply leaves the intent standing: the link is probably down, and its return retries.
        settle(reply, tag)
        return reply
    }

    override suspend fun querySession(): SessionReply {
        link.ensureStarted()
        val reply = requests.withLock {
            toReply(request(SerialCommands.QUERY_SESSION) { it.isSessionReply(tag = null) })
        }
        if (reply is SessionReply.Armed && reply.session.tag == activeTag) _session.value = reply.session
        return reply
    }

    override suspend fun stopFuelFlow() {
        // Always clear local state even if the write fails — never strand the app thinking fuel is
        // still flowing. Deliberately NOT behind [requests]: a stop must not queue behind an arm
        // that is still retrying. The arm checks [activeTag] before each attempt and after its
        // reply, so it cannot re-open what this closed.
        val wasDispensing = _isDispensing.value
        activeTag = null
        _isDispensing.value = false
        val ok = writeRelayOff()
        if (wasDispensing) Log.i(TAG, if (ok) "RELAY OFF — fuel stopped" else "RELAY OFF write failed (link down)")
    }

    // ---- internals ------------------------------------------------------------------------

    private suspend fun requestWithRetries(line: String, tag: Long): SessionReply {
        var reply: SessionReply = SessionReply.NoReply
        for (attempt in 1..ARM_ATTEMPTS) {
            if (activeTag != tag) break                     // stopped while we were retrying
            reply = toReply(request(line) { it.isSessionReply(tag) })
            if (reply !is SessionReply.NoReply) break
        }
        return reply
    }

    /** Apply the adapter's answer for sale [tag] to local state. */
    private suspend fun settle(reply: SessionReply, tag: Long) {
        when (reply) {
            is SessionReply.Armed -> {
                _session.value = reply.session
                if (activeTag == tag) {
                    _isDispensing.value = true
                } else {
                    // Stopped while the arm was in flight, and the adapter opened anyway.
                    writeRelayOff()
                }
            }
            is SessionReply.Stopped, SessionReply.NoSession, is SessionReply.Refused -> {
                if (activeTag == tag) activeTag = null
                _isDispensing.value = false
            }
            SessionReply.NoReply -> Unit
        }
    }

    private fun autoResume(why: String) {
        val tag = activeTag ?: return
        scope.launch {
            val reply = resume(tag)
            Log.i(TAG, "$why — RES:$tag → $reply")
        }
    }

    /**
     * Write [line] and wait for the first frame [accept] takes. Subscribes BEFORE writing — the
     * frame stream has no replay, and a fast adapter can answer before a late subscriber exists.
     */
    private suspend fun request(line: String, accept: (SerialFrame) -> Boolean): SerialFrame? =
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) { link.frames.first(accept) }
            val written = withContext(io) { link.writeLine(line) }
            if (!written) {
                reply.cancel()
                return@coroutineScope null
            }
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }.also { if (it == null) reply.cancel() }
        }

    private fun toReply(frame: SerialFrame?): SessionReply = when (frame) {
        is SerialFrame.Arm -> SessionReply.Armed(AdapterSession(frame.tag, frame.start))
        is SerialFrame.Stop -> SessionReply.Stopped(frame.tag, frame.cut)
        is SerialFrame.Error ->
            if (frame.code == NOSESSION) SessionReply.NoSession else SessionReply.Refused(frame.code)
        else -> SessionReply.NoReply
    }

    /** A reply to a session request for [tag] (any tag when null). ERR:CMD answers a refused frame. */
    private fun SerialFrame.isSessionReply(tag: Long?): Boolean = when (this) {
        is SerialFrame.Arm -> tag == null || this.tag == tag
        is SerialFrame.Stop -> tag == null || this.tag == tag
        is SerialFrame.Error -> code == NOSESSION || code == "CMD"
        else -> false
    }

    private suspend fun writeRelayOff(): Boolean = withContext(io) { link.writeLine(SerialCommands.RELAY_OFF) }

    internal companion object {
        const val TAG = "UsbSerialRelay"
        const val NOSESSION = "NOSESSION"
        const val REFUSED_LOCALLY = "LOCAL"
        const val U32_MAX = 0xFFFF_FFFFL

        /** Wait for an acknowledgement before re-sending (spec §7). */
        const val REPLY_TIMEOUT_MS = 500L

        /** Sends before a sale is refused for want of an ARM (spec §7). */
        const val ARM_ATTEMPTS = 3
    }
}
