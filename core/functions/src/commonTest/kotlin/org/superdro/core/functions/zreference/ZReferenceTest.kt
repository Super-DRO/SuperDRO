package org.superdro.core.functions.zreference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType

/**
 * "Set Z Reference".
 */
class ZReferenceTest {

    @Test
    fun theZAbsoluteValueIsAPlusB() {
        val a = ZReference.toMicrometers(2.0, metric = false)
        val b = ZReference.toMicrometers(1.5, metric = true)
        assertEquals(50800.0, a)
        assertEquals(1500.0, b)
        val result = assertIs<ZReferenceResult.Applied>(ZReference.apply(a, b, zEnabled = true, connected = true))
        assertMm(52.300, result.zAbsoluteUm)
        assertEquals(50800.0, result.setterHeightUm)
        assertNull(result.offsetDeltaUm)
    }

    @Test
    fun anEmptyBIsZero() {
        val result = assertIs<ZReferenceResult.Applied>(ZReference.apply(25000.0, null, zEnabled = true, connected = true))
        assertEquals(25000.0, result.zAbsoluteUm)
    }

    @Test
    fun aSetterHeightOf0IsRefused() {
        listOf(0.0, -1.0, null, Double.NaN).forEach { a ->
            val failure = assertIs<ZReferenceResult.Failure>(ZReference.apply(a, 1000.0, zEnabled = true, connected = true))
            assertEquals("Tool setter height must be a positive number", failure.message.text, "$a")
            assertEquals(FunctionSound.Error, failure.sound)
        }
    }

    @Test
    fun theManualAndTheAutomaticModeDoTheSameChecks() {
        assertEquals("Z readout must be enabled", ZReference.block(zEnabled = false, connected = true)?.text)
        assertEquals("The adapter must be connected", ZReference.block(zEnabled = true, connected = false)?.text)
        assertNull(ZReference.block(zEnabled = true, connected = true))
        val manual = assertIs<ZReferenceResult.Failure>(ZReference.apply(1000.0, 0.0, zEnabled = false, connected = true))
        assertEquals("Z readout must be enabled", manual.message.text)
        val auto = assertIs<ZReferenceResult.Failure>(
            ZReference.applyAtTrigger(1000.0, 0.0, zEnabled = true, connected = false, latchedAbsoluteZUm = 5.0),
        )
        assertEquals("The adapter must be connected", auto.message.text)
    }

    @Test
    fun theAutomaticModeSetsZAtTheMomentOfTheContact() {
        val result = assertIs<ZReferenceResult.Applied>(
            ZReference.applyAtTrigger(30000.0, 2000.0, zEnabled = true, connected = true, latchedAbsoluteZUm = -12000.0),
        )
        assertEquals(32000.0, result.zAbsoluteUm)
        assertEquals(44000.0, result.offsetDeltaUm)
        assertEquals(FunctionSound.Short, result.sound)
        val unknown = assertIs<ZReferenceResult.Failure>(
            ZReference.applyAtTrigger(30000.0, 2000.0, zEnabled = true, connected = true, latchedAbsoluteZUm = null),
        )
        assertEquals("Could not read the Z position at the moment of contact. Try again.", unknown.message.text)
        assertTrue(ZReference.autoVisible(ProbeConfig(enabled = true, toolSetterEnabled = true)))
        assertFalse(ZReference.autoVisible(ProbeConfig(enabled = true, toolSetterEnabled = false)))
        assertFalse(ZReference.autoVisible(ProbeConfig(enabled = false, toolSetterEnabled = true)))
    }

    @Test
    fun theSetterHeightComesFromTheZOffsetOfTheProfile() {
        val setter = ProbeProfile(type = ProbeType.ToolHeightSetter, zOffsetUm = 50000.0)
        assertEquals(50000.0, ZReference.setterHeightFromProfile(setter))
        assertEquals(1234.0, ZReference.setterHeightFromProfile(ProbeProfile(type = ProbeType.TouchProbe, zOffsetUm = 1234.0)))
        assertNull(ZReference.setterHeightFromProfile(ProbeProfile(type = ProbeType.EdgeFinder2D, zOffsetUm = 1234.0)))
        assertNull(ZReference.setterHeightFromProfile(null))
        val fromProfile = assertIs<ZReferenceResult.Applied>(
            ZReference.apply(ZReference.setterHeightFromProfile(setter), 0.0, zEnabled = true, connected = true, fromProfile = true),
        )
        assertEquals(50000.0, fromProfile.zAbsoluteUm)
        assertNull(fromProfile.setterHeightUm)
        val negative = setter.copy(zOffsetUm = -10.0)
        val refused = assertIs<ZReferenceResult.Failure>(
            ZReference.apply(ZReference.setterHeightFromProfile(negative), 0.0, zEnabled = true, connected = true, fromProfile = true),
        )
        assertEquals("Tool setter height must be a positive number", refused.message.text)
    }

    @Test
    fun theSetterHeightAndItsUnitGoIntoTheMachineProfile() {
        val profile = ZReference.storeSetterHeight(MachineProfile.create(), 50800.0, metric = false)
        assertEquals(50800.0, profile.zSetterHeightUm)
        assertEquals(false, profile.zSetterMetric)
        assertEquals("Set Z Reference", ZReferenceTexts.TITLE.message().text)
    }
}
