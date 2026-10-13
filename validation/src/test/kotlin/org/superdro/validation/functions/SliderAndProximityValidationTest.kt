package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.PositionSlider
import org.superdro.core.functions.graphics.Rect
import org.superdro.core.functions.graphics.Segment
import org.superdro.core.functions.proximity.ProximityState
import org.superdro.core.functions.proximity.ProximityTone
import org.superdro.core.functions.proximity.ProximityWarning
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType

/**
 * The position slider and the proximity warning levels. The slider is 1000 px × 40 px.
 */
class SliderAndProximityValidationTest {

    private val w = 1000.0
    private val h = 40.0

    @Test
    fun base_line_center_tick_and_metric_ticks() {
        val g = PositionSlider.geometry(w, h, 0.0, 0.0, 0.0, metric = true)
        assertEquals(Segment(25.0, 20.0, 975.0, 20.0), g.baseLine, "from 0.025 W to 0.975 W at y = H/2")
        assertEquals(Segment(500.0, 20.0, 500.0, 40.0), g.centerTick, "x = W/2 from H/2 to H")
        // Ticks at ±10, 20, 30, 40 mm. The tick at ±50 mm (x = 0 and x = 1000) is outside the base line.
        assertEquals(listOf(100.0, 200.0, 300.0, 400.0, 600.0, 700.0, 800.0, 900.0), g.ticks.map { it.x1 }.sorted())
        assertTrue(g.ticks.all { it.y1 == 20.0 && it.y2 == 30.0 }, "length 0.25 H")
        assertEquals(500.0, g.markerX)
        assertNull(g.toolBar, "no tool bar when the tool size is 0")
    }

    @Test
    fun inch_ticks_each_12700_um() {
        val g = PositionSlider.geometry(w, h, 0.0, 0.0, 0.0, metric = false)
        assertEquals(listOf(500.0 - 381.0, 500.0 - 254.0, 500.0 - 127.0, 500.0 + 127.0, 500.0 + 254.0, 500.0 + 381.0), g.ticks.map { it.x1 }.sorted())
        assertEquals(10_000.0, PositionSlider.tickIntervalUm(true))
        assertEquals(12_700.0, PositionSlider.tickIntervalUm(false))
        assertEquals(50_000.0, PositionSlider.RANGE_UM)
    }

    @Test
    fun marker_positions_and_the_clamp_at_the_limits() {
        assertEquals(750.0, PositionSlider.geometry(w, h, 25_000.0, 0.0, 0.0, true).markerX)
        assertEquals(1000.0, PositionSlider.geometry(w, h, 50_000.0, 0.0, 0.0, true).markerX)
        assertEquals(1000.0, PositionSlider.geometry(w, h, 60_000.0, 0.0, 0.0, true).markerX)
        assertEquals(0.0, PositionSlider.geometry(w, h, -60_000.0, 0.0, 0.0, true).markerX)
        assertEquals(50_000.0, PositionSlider.clampPosition(75_000.0))
        val g = PositionSlider.geometry(w, h, 0.0, 0.0, 0.0, true)
        assertTrue(g.markerLeft.a.y > 20.0 && g.markerRight.a.y > 20.0, "the triangles are below the base line")
    }

    @Test
    fun tool_bar_width_center_and_the_limits() {
        // D = 10 mm: tool size 5000 µm, width (5000 / 50000) × W = 100 px.
        val size = PositionSlider.toolSizeUm(AxisId.X, MachineType.VerticalMill, 10_000.0)
        assertEquals(5_000.0, size)
        val g = PositionSlider.geometry(w, h, 20_000.0, size, 5_000.0, true)
        // Center x = W/2 + (W/2) × (pos − toolOffset) / range = 500 + 500 × 15000 / 50000 = 650.
        assertEquals(Rect(600.0, 12.0, 700.0, 18.0), g.toolBar, "from y = H/2 − 8 to H/2 − 2")
        val atLimit = PositionSlider.geometry(w, h, 50_000.0, size, -10_000.0, true)
        assertEquals(1000.0, atLimit.toolBar!!.centerX, "the clamp of the tool bar center")
        assertEquals(0.0, PositionSlider.toolSizeUm(AxisId.Z, MachineType.VerticalMill, 10_000.0))
        assertEquals(0.0, PositionSlider.toolSizeUm(AxisId.X, MachineType.Lathe, 10_000.0))
        assertEquals(5_000.0, PositionSlider.toolSizeUm(AxisId.Y, MachineType.HorizontalMill, 10_000.0))
    }

    private fun approach(from: Double, to: Double, target: Double = 0.0): ProximityState =
        ProximityWarning.start(target).update(from, 0L).update(to, 100L)

    @Test
    fun level_0_65_at_1000_um_while_the_axis_moves_closer() {
        assertEquals(0.65, approach(1_100.0, 1_000.0).level, 1e-12)
        assertFalse(approach(1_100.0, 1_000.0).passed)
    }

    @Test
    fun all_rows_of_the_level_rules() {
        // value == target: 0, passed.
        val at = approach(500.0, 0.0)
        assertEquals(0.0, at.level)
        assertTrue(at.passed)
        // The value went across the target: 0, passed.
        val across = approach(100.0, -100.0)
        assertEquals(0.0, across.level)
        assertTrue(across.passed)
        // The axis moves away: 0.
        assertEquals(0.0, approach(1_000.0, 1_100.0).level)
        // Passed and d <= Z0: 0.
        assertEquals(0.0, across.update(-50.0, 200L).level)
        // d > Z1: 0.
        assertEquals(0.0, approach(3_000.0, 2_541.0).level)
        // d <= Z0: 1.0 (continuous tone).
        assertEquals(1.0, approach(200.0, 127.0).level)
        // The axis moves to the target in the near zone: the step formula.
        assertEquals(0.95, approach(200.0, 128.0).level, 1e-12, "floor(1 / 2540 × 20) = 0")
        assertEquals(0.0, approach(2_600.0, 2_540.0).level, 1e-12, "floor(2413 / 2540 × 20) = 19")
        // No motion: the level does not change.
        val before = approach(1_100.0, 1_000.0)
        assertEquals(before.level, before.update(1_000.0, 200L).level)
        // A negative side approach uses |target − value|.
        assertEquals(0.65, approach(-1_100.0, -1_000.0).level, 1e-12)
        assertEquals(2_540.0, ProximityWarning.NEAR_ZONE_UM)
        assertEquals(127.0, ProximityWarning.CONTACT_ZONE_UM)
        assertEquals(20, ProximityWarning.STEPS)
    }

    @Test
    fun tone_period_silence_and_the_measurement_mode() {
        val state = approach(1_100.0, 1_000.0)
        assertEquals(ProximityTone.Beep(100.0 / 0.65), state.tone(200L))
        assertEquals(2_000.0, ProximityWarning.beepPeriodMs(0.05), 1e-9, "max 2 s")
        assertEquals(100.0 / 0.95, ProximityWarning.beepPeriodMs(0.95), 1e-9, "0.1 s / level")
        assertEquals(100.0, ProximityWarning.beepPeriodMs(2.0), 1e-9, "the formula has a minimum of 0.1 s")
        assertEquals(ProximityTone.Continuous, ProximityWarning.tone(1.0))
        assertEquals(ProximityTone.Off, ProximityWarning.tone(0.0))
        assertEquals(0.0, state.levelAt(100L + 5_000L), "silence after 5000 ms with no new value")
        assertEquals(0.65, state.levelAt(100L + 4_999L), 1e-12)
        assertEquals(ProximityTone.Off, state.tone(200L, MachineMode.Measurement), "the measurement mode stops the warning")
        assertEquals(800, ProximityWarning.TONE_HZ)
    }
}
