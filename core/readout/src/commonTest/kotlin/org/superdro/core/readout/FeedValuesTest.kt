package org.superdro.core.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat

/** The feed values and the tachometer. */
class FeedValuesTest {

    private val inch = ReadoutPatterns.linear(ReadoutFormat.Format3, metric = false)
    private val mm = ReadoutPatterns.linear(ReadoutFormat.Format3, metric = true)

    @Test
    fun theFeedRateTexts() {
        val rate = 300000.0
        assertEquals("300.000", mm.format(UnitConverter.Millimeter.toDisplay(rate)))
        assertEquals("11.8110", inch.format(UnitConverter.Inch.toDisplay(rate)))
    }

    @Test
    fun theFeedPerRevolutionAndTheChipLoad() {
        val fpr = FeedValues.feedPerRevolution(300000.0, 1200.0)
        assertEquals(250.0, fpr)
        assertEquals(0.0, FeedValues.feedPerRevolution(300000.0, 0.0))
        assertEquals(250.0, FeedValues.feedPerRevolution(-300000.0, -1200.0))
        val chip = FeedValues.chipLoad(fpr, FeedValues.teeth(MachineType.VerticalMill, 4))
        assertEquals(62.5, chip)
        assertEquals("0.0025", inch.format(UnitConverter.Inch.toDisplay(chip)))
        assertEquals("0.063", mm.format(UnitConverter.Millimeter.toDisplay(chip)))
        assertEquals(1, FeedValues.teeth(MachineType.Lathe, 4))
        assertEquals(0.0, FeedValues.chipLoad(fpr, 0))
    }

    @Test
    fun theThreadPitchAndTheThreadCount() {
        assertEquals("0.25", FeedValues.threadPitchText(250.0))
        assertEquals("101.6", FeedValues.threadsPerInchText(250.0))
        assertEquals("0.0", FeedValues.threadsPerInchText(0.04))
        assertNull(FeedValues.threadsPerInch(0.0))
        assertEquals("1.50", FeedValues.threadPitchText(1498.0), "rint(fpr × 20) / 20")
        assertEquals(true, FeedValues.showsThread(MachineType.Lathe, AxisId.Z))
        assertEquals(false, FeedValues.showsThread(MachineType.Lathe, AxisId.X))
        assertEquals(false, FeedValues.showsThread(MachineType.VerticalMill, AxisId.Z))
    }

    @Test
    fun theRpmOfTheSensor() {
        assertEquals(1200.0, Tachometer.rpm(4800L, 4.0, invert = false))
        assertEquals(-1200.0, Tachometer.rpm(4800L, 4.0, invert = true))
        assertEquals(0.0, Tachometer.rpm(4800L, 0.0, invert = false))
        assertEquals("1200", Tachometer.text(Tachometer.rpm(4800L, 4.0, invert = false)))
    }

    @Test
    fun theSurfaceSpeed() {
        assertEquals("157.08", SurfaceSpeed.text(SurfaceSpeed.speed(1200.0, 12700.0, metric = false)))
        assertEquals("31.42", SurfaceSpeed.text(SurfaceSpeed.speed(1000.0, 10000.0, metric = true)))
        assertEquals("261.80", SurfaceSpeed.text(SurfaceSpeed.speed(1000.0, 2.0 * 12700.0, metric = false)))
        assertEquals("157.08", SurfaceSpeed.text(SurfaceSpeed.speed(-1200.0, 12700.0, metric = false)))
    }
}
