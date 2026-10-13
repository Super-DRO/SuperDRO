package org.superdro.core.ui.adapter.calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.ui.navigation.InputCalibrationRoute
import org.superdro.core.ui.tabs.english

/** The start rules of the calibration wizard. */
class InputCalibrationStartTest {

    private val mill = MachineProfile.create()

    @Test
    fun thePrimaryInputOfAnAxisStartsTheWizardOfItsLetter() {
        val start = assertIs<InputCalibrationStart.AxisStart.Ready>(InputCalibrationStart.forAxis(mill, AxisId.X))
        assertEquals(InputCalibrationRoute(Channels.X), start.route)
        assertEquals(InputCalibrationRoute(Channels.W), assertIs<InputCalibrationStart.AxisStart.Ready>(InputCalibrationStart.forAxis(mill, AxisId.Angular)).route)
    }

    @Test
    fun aSecondaryInputNeedsItsSwitch() {
        val refused = assertIs<InputCalibrationStart.AxisStart.Refused>(InputCalibrationStart.forAxis(mill, AxisId.X, secondary = true))
        assertEquals("Secondary Input Disabled", refused.message.english())
        val withSecondary = mill.copy(x = mill.x.copy(secondary = InputConfig(Channels.W)))
        val ready = assertIs<InputCalibrationStart.AxisStart.Ready>(InputCalibrationStart.forAxis(withSecondary, AxisId.X, secondary = true))
        assertEquals(InputCalibrationRoute(Channels.W), ready.route)
    }

    @Test
    fun eachInputNeedsAChannel() {
        val probeChannel = mill.copy(y = mill.y.copy(primary = InputConfig(Channels.P)))
        val refused = assertIs<InputCalibrationStart.AxisStart.Refused>(InputCalibrationStart.forAxis(probeChannel, AxisId.Y))
        assertEquals("Input Prefix Missing", refused.message.english())
        assertEquals("Input Prefix Missing", InputCalibrationStart.inputRefusal('q', null)?.english())
    }

    @Test
    fun theTachometerHasNoCalibration() {
        val refused = assertIs<InputCalibrationStart.AxisStart.Refused>(InputCalibrationStart.forAxis(mill, AxisId.Tachometer))
        assertEquals("The tachometer input has no calibration. Set its pulses for each revolution in the adapter details.", refused.message.english())
        assertEquals(refused.message, InputCalibrationStart.inputRefusal(Channels.T, EncoderProfile.defaultFor(Channels.T)))
    }

    @Test
    fun aDisabledEncoderProfileStopsTheWizard() {
        val disabled = EncoderProfile.defaultFor(Channels.X).copy(kind = EncoderKind.Disabled)
        assertEquals(
            "The kind of this input is Disabled. Select Linear or Angular in the encoder settings first.",
            InputCalibrationStart.inputRefusal(Channels.X, disabled)?.english(),
        )
        assertNull(InputCalibrationStart.inputRefusal(Channels.X, EncoderProfile.defaultFor(Channels.X)))
        assertNull(InputCalibrationStart.inputRefusal('X', null), "The letter is not case-sensitive, and no profile uses the default.")
    }
}
