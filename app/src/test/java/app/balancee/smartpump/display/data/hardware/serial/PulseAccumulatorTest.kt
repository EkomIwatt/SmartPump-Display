package app.balancee.smartpump.display.data.hardware.serial

import org.junit.Assert.assertEquals
import org.junit.Test

class PulseAccumulatorTest {

    @Test fun boot_then_sequential_pulses_count_each_one() {
        val acc = PulseAccumulator()
        acc.onBoot(0)
        assertEquals(1, acc.onPulse(1))
        assertEquals(1, acc.onPulse(2))
        assertEquals(1, acc.onPulse(3))
    }

    @Test fun dropped_line_self_heals_on_next_cumulative() {
        val acc = PulseAccumulator()
        acc.onBoot(0)
        assertEquals(100, acc.onPulse(100))
        // The frame for 150 never arrived; the next PULSE carries the higher cumulative and the
        // delta spans the gap — no pulses lost.
        assertEquals(100, acc.onPulse(200))
    }

    @Test fun mid_stream_connect_does_not_emit_free_running_count_as_fuel() {
        val acc = PulseAccumulator()
        // No BOOT seen — adapter has been running; first PULSE just establishes the baseline.
        assertEquals(0, acc.onPulse(42817))
        assertEquals(3, acc.onPulse(42820))
    }

    @Test fun boot_resets_baseline_after_counting() {
        val acc = PulseAccumulator()
        acc.onBoot(0)
        assertEquals(500, acc.onPulse(500))
        // Adapter power-cycled: counter restarts at 0, the first pulse after is counted in full.
        acc.onBoot(0)
        assertEquals(1, acc.onPulse(1))
    }

    @Test fun backward_jump_without_boot_resyncs_to_zero() {
        val acc = PulseAccumulator()
        acc.onBoot(0)
        assertEquals(10, acc.onPulse(10))
        // Glitch / missed BOOT — never emit a negative delta; re-baseline silently.
        assertEquals(0, acc.onPulse(4))
        assertEquals(2, acc.onPulse(6))
    }

    @Test fun reset_forgets_baseline() {
        val acc = PulseAccumulator()
        acc.onBoot(0)
        assertEquals(10, acc.onPulse(10))
        acc.reset()
        // After a disconnect the next frame re-establishes the reference (contributes 0).
        assertEquals(0, acc.onPulse(99))
        assertEquals(1, acc.onPulse(100))
    }
}
