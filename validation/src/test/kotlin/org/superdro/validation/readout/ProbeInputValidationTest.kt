package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeContactType
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.readout.input.ProbeSettings

/**
 * The probe input stage and the latch.
 */
class ProbeInputValidationTest {

    private fun probeStage(contactType: ProbeContactType = ProbeContactType.Auto, debounceMs: Long = 0L): InputStage {
        val profile = mill()
        val adapter = adapter().copy(probeContactType = contactType, probeDebounceMs = debounceMs)
        return InputStage(InputConfiguration.from(profile, adapter))
    }

    private fun InputStage.active(): Boolean = snapshot.probes.getValue('p')

    /** Sends the same count on X, Y, and Z at [timeMs]. */
    private fun InputStage.xyz(x: Long, y: Long, z: Long, timeMs: Long, events: MutableList<ProbeEvent>) {
        count('x', x, timeMs, events)
        count('y', y, timeMs, events)
        count('z', z, timeMs, events)
    }

    // The active raw value.

    @Test
    fun active_raw_value_is_1() {
        val stage = probeStage()
        stage.onBinaryInput('p', 0, 0L)
        assertFalse(stage.active())
        stage.onBinaryInput('p', 1, 10L)
        assertTrue(stage.active())
    }

    @Test
    fun normally_closed_contact_makes_the_active_raw_value_0() {
        val stage = probeStage(ProbeContactType.NormallyClosed)
        stage.onBinaryInput('p', 1, 0L)
        assertFalse(stage.active())
        stage.onBinaryInput('p', 0, 10L)
        assertTrue(stage.active())
        assertEquals(0, ProbeSettings(ProbeContactType.NormallyClosed).activeValue)
        assertEquals(1, ProbeSettings(ProbeContactType.NormallyOpen).activeValue)
        assertEquals(1, ProbeSettings(ProbeContactType.Auto).activeValue)
    }

    @Test
    fun ports_are_p_and_q_and_r_s_are_ignored() {
        val stage = probeStage()
        assertEquals(setOf('p', 'q'), stage.snapshot.probes.keys)
        assertTrue(stage.onBinaryInput('r', 1, 0L).isEmpty())
        assertTrue(stage.onBinaryInput('s', 1, 0L).isEmpty())
        assertEquals(0L, stage.snapshot.probeSequence)
        stage.onBinaryInput('Q', 1, 5L)
        assertTrue(stage.snapshot.probes.getValue('q'))
    }

    // The debounce.

    @Test
    fun debounce_rejects_an_activation_before_the_debounce_time_passed() {
        val stage = probeStage(debounceMs = 200L)
        stage.onBinaryInput('p', 1, 0L)
        assertEquals(1L, stage.snapshot.probeSequence)
        stage.onBinaryInput('p', 0, 50L)
        stage.onBinaryInput('p', 1, 100L)
        assertFalse(stage.active())
        assertEquals(1L, stage.snapshot.probeSequence)
        stage.onBinaryInput('p', 0, 150L)
        stage.onBinaryInput('p', 1, 250L)
        assertTrue(stage.active())
        assertEquals(2L, stage.snapshot.probeSequence)
    }

    @Test
    fun debounce_0_accepts_each_activation() {
        val stage = probeStage(debounceMs = 0L)
        stage.onBinaryInput('p', 1, 0L)
        stage.onBinaryInput('p', 0, 1L)
        stage.onBinaryInput('p', 1, 2L)
        assertTrue(stage.active())
        assertEquals(2L, stage.snapshot.probeSequence)
    }

    @Test
    fun default_debounce_is_0_2_s() {
        assertEquals(200L, AdapterProfile().probeDebounceMs)
        assertEquals(200L, ProbeSettings().debounceMs)
    }

    // A change to not active.

    @Test
    fun change_to_not_active_is_accepted_at_once() {
        val stage = probeStage(debounceMs = 200L)
        stage.onBinaryInput('p', 1, 0L)
        stage.onBinaryInput('p', 0, 10L)
        assertFalse(stage.active())
    }

    // The watchdog.

    @Test
    fun watchdog_period_is_max_of_5_times_debounce_and_1000_ms() {
        assertEquals(1000L, ProbeSettings(debounceMs = 0L).watchdogPeriodMs)
        assertEquals(1000L, ProbeSettings(debounceMs = 200L).watchdogPeriodMs)
        assertEquals(1500L, ProbeSettings(debounceMs = 300L).watchdogPeriodMs)
    }

    @Test
    fun watchdog_clears_the_state_between_T_and_2T_after_the_last_P1() {
        listOf(0L, 300L, 999L).forEach { start ->
            val stage = probeStage(debounceMs = 0L)
            stage.onTick(0L)
            stage.onBinaryInput('p', 1, start)
            stage.onTick(start + 999L)
            assertTrue(stage.active(), "start $start: active before T")
            stage.onTick(start + 2001L)
            assertFalse(stage.active(), "start $start: not active after 2T")
        }
    }

    @Test
    fun watchdog_with_debounce_300_ms_uses_T_1500_ms() {
        val stage = probeStage(debounceMs = 300L)
        stage.onTick(0L)
        stage.onBinaryInput('p', 1, 0L)
        stage.onTick(1499L)
        assertTrue(stage.active())
        stage.onTick(3001L)
        assertFalse(stage.active())
    }

    @Test
    fun repeated_P1_in_each_period_keeps_the_state_active() {
        val stage = probeStage(debounceMs = 0L)
        var time = 0L
        while (time <= 5000L) {
            stage.onBinaryInput('p', 1, time)
            time += 500L
            stage.onTick(time - 1L)
            assertTrue(stage.active(), "time $time")
        }
        assertEquals(1L, stage.snapshot.probeSequence)
    }

    // The latch.

    @Test
    fun probe_at_t_between_two_samples_gives_the_interpolated_position() {
        val stage = probeStage()
        val events = ArrayList<ProbeEvent>()
        stage.xyz(0L, 500L, 0L, 0L, events)
        events += stage.onBinaryInput('p', 1, 40L)
        stage.xyz(1000L, 500L, 0L, 100L, events)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(1L, event.sequence)
        assertEquals('p', event.port)
        assertEquals(40L, event.timeMs)
        assertEquals(400.0, event.axisPositions.getValue(AxisId.X), 1e-9)
        assertEquals(500.0, event.axisPositions.getValue(AxisId.Y), 1e-9)
        assertEquals(0.0, event.axisPositions.getValue(AxisId.Z), 1e-9)
        assertTrue(event.complete)
    }

    @Test
    fun gap_of_more_than_500_ms_with_a_count_change_gives_not_complete() {
        val stage = probeStage()
        val events = ArrayList<ProbeEvent>()
        stage.xyz(0L, 0L, 0L, 0L, events)
        stage.count('y', 0L, 350L, events)
        stage.count('z', 0L, 350L, events)
        events += stage.onBinaryInput('p', 1, 400L)
        stage.count('y', 0L, 450L, events)
        stage.count('z', 0L, 450L, events)
        // The X samples around t are 600 ms apart, and the count changes.
        stage.count('x', 1000L, 600L, events)
        assertEquals(1, events.size)
        val event = events.single()
        assertFalse(event.complete)
        assertEquals(setOf(AxisId.X), event.incompleteAxes)
        // The last sample at or before t (count 0), never NaN.
        assertEquals(0.0, event.axisPositions.getValue(AxisId.X), 1e-9)
    }

    @Test
    fun gap_of_more_than_500_ms_with_no_count_change_is_complete() {
        val stage = probeStage()
        val events = ArrayList<ProbeEvent>()
        stage.xyz(250L, 0L, 0L, 0L, events)
        stage.count('y', 0L, 350L, events)
        stage.count('z', 0L, 350L, events)
        events += stage.onBinaryInput('p', 1, 400L)
        stage.count('y', 0L, 450L, events)
        stage.count('z', 0L, 450L, events)
        stage.count('x', 250L, 600L, events)
        val event = events.single()
        assertTrue(event.complete)
        assertEquals(250.0, event.axisPositions.getValue(AxisId.X), 1e-9)
    }

    @Test
    fun no_sample_after_t_in_500_ms_uses_the_last_sample() {
        val stage = probeStage()
        val events = ArrayList<ProbeEvent>()
        stage.xyz(0L, 0L, 0L, 0L, events)
        stage.xyz(300L, 70L, 9L, 100L, events)
        events += stage.onBinaryInput('p', 1, 150L)
        events += stage.onTick(600L)
        assertTrue(events.isEmpty(), "the latch waits up to 500 ms")
        events += stage.onTick(651L)
        val event = events.single()
        assertTrue(event.complete)
        assertEquals(300.0, event.axisPositions.getValue(AxisId.X), 1e-9)
        assertEquals(70.0, event.axisPositions.getValue(AxisId.Y), 1e-9)
        assertEquals(9.0, event.axisPositions.getValue(AxisId.Z), 1e-9)
    }

    @Test
    fun short_closure_is_not_lost() {
        val stage = probeStage()
        val events = ArrayList<ProbeEvent>()
        stage.xyz(0L, 0L, 0L, 0L, events)
        events += stage.onBinaryInput('p', 1, 40L)
        events += stage.onBinaryInput('p', 0, 41L)
        events += stage.onBinaryInput('p', 1, 42L)
        events += stage.onBinaryInput('p', 0, 43L)
        stage.xyz(1000L, 0L, 0L, 100L, events)
        assertEquals(listOf(1L, 2L), events.map { event -> event.sequence })
        assertEquals(400.0, events[0].axisPositions.getValue(AxisId.X), 1e-9)
        assertEquals(420.0, events[1].axisPositions.getValue(AxisId.X), 1e-9)
    }

    @Test
    fun latch_uses_the_axis_sum_without_offsets() {
        val base = mill()
        val profile = base.copy(x = base.x.copy(secondary = org.superdro.core.model.InputConfig('w')))
        val stage = InputStage(InputConfiguration.from(profile, adapter().copy(probeDebounceMs = 0L)))
        val events = ArrayList<ProbeEvent>()
        stage.xyz(0L, 0L, 0L, 0L, events)
        stage.count('w', 100L, 0L, events)
        events += stage.onBinaryInput('p', 1, 50L)
        stage.xyz(1000L, 0L, 0L, 100L, events)
        stage.count('w', 300L, 100L, events)
        val event = events.single()
        // x: 500, w: 200 at t = 50.
        assertEquals(700.0, event.axisPositions.getValue(AxisId.X), 1e-9)
        assertEquals(500.0, event.inputPositions.getValue('x'), 1e-9)
        assertEquals(200.0, event.inputPositions.getValue('w'), 1e-9)
    }

    @Test
    fun trigger_record_has_the_velocity_of_each_axis_over_1000_ms() {
        val stage = probeStage()
        val events = ArrayList<ProbeEvent>()
        var time = 0L
        while (time <= 1200L) {
            // X moves 1 µm each ms. Y and Z stay.
            stage.xyz(time, 0L, 0L, time, events)
            if (time == 1100L) events += stage.onBinaryInput('p', 1, 1100L)
            time += 100L
        }
        val event = events.single()
        assertEquals(1100.0, event.axisPositions.getValue(AxisId.X), 1e-9)
        // (pos(t) − pos(t − 1000)) / 1000 × 1000 µm/s.
        assertEquals(1000.0, event.axisVelocities.getValue(AxisId.X), 1e-9)
        assertEquals(0.0, event.axisVelocities.getValue(AxisId.Y), 1e-9)
    }

    // The values come from the adapter profile.

    @Test
    fun contact_type_and_debounce_come_from_the_adapter_profile() {
        val adapter = adapter().copy(probeContactType = ProbeContactType.NormallyClosed, probeDebounceMs = 50L)
        val probe = InputConfiguration.from(mill(), adapter).probe
        assertEquals(ProbeContactType.NormallyClosed, probe.contactType)
        assertEquals(50L, probe.debounceMs)
        assertEquals(0, probe.activeValue)
    }
}
