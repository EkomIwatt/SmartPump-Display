// Typed result of parsing one line of the Arduino pulse-adapter serial protocol.
//
// Wire framing (Phase 7a — proposed to Olonade, see docs/journal/PHASE_7_PLAN.md):
//   device → app:  PULSE:<cum>*<cs>\n   a fuel pulse; <cum> is the adapter's free-running count
//                  HB:<cum>*<cs>\n      ~2s keep-alive when idle
//                  BOOT:<cum>*<cs>\n    sent once on adapter power-up (counter (re)starts)
//                  ERR:<code>*<cs>\n    fault report
//   <cs> = XOR-8 of the ASCII bytes BEFORE the '*', two hex digits (see SerialFrameParser.xor8).
//
// The cumulative count is the robustness win: downstream takes the delta between frames, so a
// dropped line self-heals on the next PULSE. Frame cumulatives are Long; the session pulse count
// the ViewModel sees stays Int (PulseMessage.Pulse.count).
package app.balancee.smartpump.display.data.hardware.serial

sealed interface SerialFrame {

    /** A fuel-flow pulse. [cumulative] is the adapter's running count (not session-relative). */
    data class Pulse(val cumulative: Long) : SerialFrame

    /** Keep-alive; carries the current cumulative for reference but is not itself fuel. */
    data class Heartbeat(val cumulative: Long) : SerialFrame

    /** Adapter (re)booted; its counter now reads [cumulative] (typically 0). */
    data class Boot(val cumulative: Long) : SerialFrame

    /** Adapter-reported fault. [code] is the opaque error code from the frame payload. */
    data class Error(val code: String) : SerialFrame

    /** Line could not be parsed (bad checksum, malformed, non-numeric count). [raw] is the input. */
    data class Invalid(val raw: String, val reason: String) : SerialFrame
}
