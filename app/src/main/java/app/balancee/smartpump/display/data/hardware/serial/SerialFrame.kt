// Typed result of parsing one line of the Arduino pulse-adapter serial protocol.
//
// The protocol is specified in docs/serial-protocol.md (revision 2, Phase 11) — that document is
// the authority. Device → app frames:
//   PULSE:<count>*<cs>        a fuel pulse; <count> is the adapter's free-running lifetime count
//   HB:<count>*<cs>           ~2s keep-alive when idle
//   BOOT:<count>*<cs>         sent once on adapter power-up (EEPROM-restored count)
//   ARM:<tag>:<start>*<cs>    the sale's session is open or held; sale pulses = count − start
//   STOP:<tag>:<cut>*<cs>     the adapter cut the relay at the limit; cut == start + limit
//   ERR:<code>*<cs>           fault or refusal (CMD, NOSESSION, CSUM, NOCS, WDOG, PWR)
//   <cs> = XOR-8 of the ASCII bytes BEFORE the '*', two hex digits (see SerialFrameParser.xor8).
//
// The cumulative count is the robustness win: downstream takes the delta between frames, so a
// dropped line self-heals on the next PULSE. Frame numbers are Long (the adapter's are unsigned
// 32-bit, which Int cannot hold); the session pulse count the ViewModel sees stays Int
// (PulseMessage.Pulse.count). App → device frames are built by SerialCommands.
package app.balancee.smartpump.display.data.hardware.serial

sealed interface SerialFrame {

    /** A fuel-flow pulse. [cumulative] is the adapter's running count (not session-relative). */
    data class Pulse(val cumulative: Long) : SerialFrame

    /** Keep-alive; carries the current cumulative for reference but is not itself fuel. */
    data class Heartbeat(val cumulative: Long) : SerialFrame

    /** Adapter (re)booted; its counter now reads [cumulative] (typically 0). */
    data class Boot(val cumulative: Long) : SerialFrame

    /**
     * The adapter holds session [tag], open or held, which started at lifetime count [start].
     * The reply to `RLY:1`, `RES` and `SES?` (spec §3.2). The sale's pulses are `count − start`
     * for any later count — both numbers the board's own, so nothing can fall between them (#36).
     */
    data class Arm(val tag: Long, val start: Long) : SerialFrame

    /**
     * Session [tag] reached its limit and the adapter cut the relay at lifetime count [cut]
     * (`cut − start == limit`). Sent unsolicited when it happens, and again in reply to `RES` /
     * `SES?` for a finished session.
     */
    data class Stop(val tag: Long, val cut: Long) : SerialFrame

    /** Adapter-reported fault. [code] is the opaque error code from the frame payload. */
    data class Error(val code: String) : SerialFrame

    /** Line could not be parsed (bad checksum, malformed, non-numeric count). [raw] is the input. */
    data class Invalid(val raw: String, val reason: String) : SerialFrame
}
