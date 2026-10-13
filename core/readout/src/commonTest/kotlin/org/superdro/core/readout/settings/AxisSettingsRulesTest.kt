package org.superdro.core.readout.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.InputConfig
import org.superdro.core.model.TachometerMode
import org.superdro.core.readout.Fixtures

/** The validation rules of the axis settings. */
class AxisSettingsRulesTest {

    @Test
    fun primaryAndSecondaryInputsCannotUseTheSamePrefix() {
        val x = Fixtures.mill().x.copy(secondary = InputConfig('w'))
        val refused = assertIs<SettingsResult.Refused>(AxisSettingsRules.setSecondaryChannel(x, 'X'))
        assertEquals("Primary and secondary inputs can't use the same prefix", refused.message.english)
        assertIs<SettingsResult.Refused>(AxisSettingsRules.setPrimaryChannel(x, 'W'))
        val accepted = assertIs<SettingsResult.Accepted<*>>(AxisSettingsRules.setSecondaryChannel(x, 'U'))
        assertEquals(InputConfig('u'), (accepted.value as org.superdro.core.model.LinearAxisConfig).secondary)
    }

    @Test
    fun theLastOutputAxisCannotBeDisabled() {
        val onlyX = Fixtures.onlyX()
        val refused = assertIs<SettingsResult.Refused>(AxisSettingsRules.setAxisEnabled(onlyX, AxisId.X, false))
        assertEquals("At least one output axis must be enabled", refused.message.english)
        assertIs<SettingsResult.Accepted<*>>(AxisSettingsRules.setAxisEnabled(Fixtures.mill(), AxisId.X, false))
        val withAngular = onlyX.copy(angular = onlyX.angular.copy(enabled = true))
        assertIs<SettingsResult.Accepted<*>>(AxisSettingsRules.setAxisEnabled(withAngular, AxisId.X, false))
        assertIs<SettingsResult.Accepted<*>>(AxisSettingsRules.setAxisEnabled(onlyX, AxisId.Caliper, false), "the caliper is not an output axis of the rule")
    }

    @Test
    fun theSecondaryInputOnUsesWOrTheDefaultPrimaryChannel() {
        val mill = Fixtures.mill()
        assertEquals(InputConfig('w'), AxisSettingsRules.setSecondaryEnabled(AxisId.X, mill.x, true).secondary)
        val primaryW = mill.y.copy(primary = InputConfig('w'))
        assertEquals(InputConfig('y'), AxisSettingsRules.setSecondaryEnabled(AxisId.Y, primaryW, true).secondary)
        val off = AxisSettingsRules.setSecondaryEnabled(AxisId.Z, mill.z.copy(secondary = InputConfig('u', sign = -1)), false)
        assertNull(off.secondary, "secondary channel none, invert false")
    }

    @Test
    fun theEdgeFinderSampleCountIsOneToTen() {
        listOf(0, 11, null).forEach { count ->
            val refused = assertIs<SettingsResult.Refused>(AxisSettingsRules.setEdgeFinderSampleCount(count))
            assertEquals("Number of edge finder samples must be a number between 1 and 10 (inclusive)", refused.message.english)
        }
        assertEquals(SettingsResult.Accepted(1), AxisSettingsRules.setEdgeFinderSampleCount(1))
        assertEquals(SettingsResult.Accepted(10), AxisSettingsRules.setEdgeFinderSampleCount(10))
    }

    @Test
    fun theTachometerRules() {
        val workspace = Fixtures.withState(Fixtures.workspace(Fixtures.mill()), AxisId.Tachometer, AxisState(absoluteOffset = 105.0))
        assertEquals(AxisState(), AxisSettingsRules.onTachometerPulsesChanged(workspace, 1.0, 4.0).axisStates.getValue(AxisId.Tachometer))
        assertEquals(workspace, AxisSettingsRules.onTachometerPulsesChanged(workspace, 1.0, 0.0))
        assertEquals(TachometerFields(showPulses = true, showPresets = false), AxisSettingsRules.tachometerFields(TachometerMode.Sensor))
        assertEquals(TachometerFields(showPulses = false, showPresets = true), AxisSettingsRules.tachometerFields(TachometerMode.Preset))
        assertEquals(TachometerFields(showPulses = false, showPresets = false), AxisSettingsRules.tachometerFields(TachometerMode.Direct))
    }

    @Test
    fun theProbeSwitchShowsTheEdgeFinderSettings() {
        assertTrue(AxisSettingsRules.edgeFinderSettingsVisible(true))
        assertFalse(AxisSettingsRules.edgeFinderSettingsVisible(false))
    }

    @Test
    fun theInputConflictCheck() {
        val mill = Fixtures.mill()
        assertEquals(emptyList(), InputConflicts.check(mill))
        val shared = mill.copy(y = mill.y.copy(primary = InputConfig('x')))
        assertEquals(listOf<InputConflict>(InputConflict.SharedWithinType('x', listOf(AxisId.X, AxisId.Y))), InputConflicts.check(shared))
        val angular = mill.copy(angular = mill.angular.copy(enabled = true, primary = InputConfig('z')))
        assertEquals(listOf<InputConflict>(InputConflict.SharedAcrossTypes('z', listOf(AxisId.Z, AxisId.Angular))), InputConflicts.check(angular))
        val lathe = Fixtures.lathe().let { profile ->
            profile.copy(auxiliary = AuxiliaryConfig(AuxiliaryType.CompoundSlide, vectoring = true), x = profile.x.copy(secondary = InputConfig('y')))
        }
        assertTrue(InputConflict.CompoundProjectionBlocked('y') in InputConflicts.check(lathe))
        val quill = mill.copy(z = mill.z.copy(secondary = InputConfig('x')))
        assertTrue(InputConflict.HeadTiltBlocked('x') in InputConflicts.check(quill))
    }
}
