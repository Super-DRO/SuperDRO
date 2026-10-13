package org.superdro.core.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineType

/** The unit converters and the post-processors. */
class ConvertersTest {

    @Test
    fun theLinearConverters() {
        assertEquals(2.0, UnitConverter.Inch.toDisplay(50800.0))
        assertEquals(44450.0, UnitConverter.Inch.toInternal(1.75))
        assertEquals(61.725, UnitConverter.Millimeter.toDisplay(61725.0))
        assertEquals(25000.0, UnitConverter.Millimeter.toInternal(25.0))
        assertEquals(1200.0, UnitConverter.Rpm.toDisplay(1200.0))
    }

    @Test
    fun theDegreesConverter() {
        assertEquals(62.5, UnitConverter.Degrees.toDisplay(225000.0))
        assertEquals(335.0, UnitConverter.Degrees.toDisplay(-90000.0))
        assertEquals(270000.0, UnitConverter.Degrees.toInternal(75.0))
        assertEquals(1206000.0, UnitConverter.Degrees.toInternal(-25.0))
        assertEquals(0.0, UnitConverter.Degrees.toInternal(360.0))
    }

    @Test
    fun theDivisionsConverter() {
        val divisions = UnitConverter.Divisions(24)
        assertEquals(15.0, divisions.degreesPerDivision)
        assertEquals("4.17", ReadoutPatterns.ANGULAR.format(divisions.toDisplay(225000.0)))
        assertEquals("22.33", ReadoutPatterns.ANGULAR.format(divisions.toDisplay(-90000.0)))
        assertEquals(270000.0, divisions.toInternal(5.0))
        assertFailsWith<IllegalArgumentException> { UnitConverter.Divisions(0) }
    }

    @Test
    fun theLatheDiameterPostProcessor() {
        val post = PostProcessor.of(AxisId.X, AxisState(diameterMode = true), MachineType.Lathe)
        assertEquals(PostProcessor.Diameter, post)
        assertEquals(25400.0, post.toShown(12700.0))
        assertEquals(15875.0, post.toInternal(31750.0))
        assertEquals(PostProcessor.Identity, PostProcessor.of(AxisId.X, AxisState(diameterMode = true), MachineType.VerticalMill))
        assertEquals(PostProcessor.Identity, PostProcessor.of(AxisId.X, AxisState(diameterMode = false), MachineType.Lathe))
    }

    @Test
    fun theAngularWrapKeepsTheSign() {
        val post = PostProcessor.of(AxisId.Angular, AxisState(), MachineType.VerticalMill)
        assertEquals(PostProcessor.AngularWrap, post)
        assertEquals(100.0, post.toShown(1296100.0))
        assertEquals(-100.0, post.toShown(-1296100.0))
        assertEquals(PostProcessor.Identity, PostProcessor.of(AxisId.Z, AxisState(), MachineType.Lathe))
    }
}
