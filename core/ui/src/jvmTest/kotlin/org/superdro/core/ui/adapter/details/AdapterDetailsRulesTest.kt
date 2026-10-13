package org.superdro.core.ui.adapter.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.DeclaredInput
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.CommandFailure
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.RawView
import org.superdro.core.readout.input.ChannelStatus
import org.superdro.core.readout.input.ChannelValue
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_command_cancelled
import org.superdro.core.resources.adapter_command_not_sent
import org.superdro.core.resources.adapter_failure_clear
import org.superdro.core.resources.adapter_failure_mode
import org.superdro.core.resources.adapter_failure_reference
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.text.UiText

/** The rules of the Adapter Details screen. */
class AdapterDetailsRulesTest {

    private fun values(vararg answers: Pair<Int, String>): AdapterValues =
        answers.fold(AdapterValues()) { values, (field, text) -> values.withInfo(field, text, 0L) }

    @Test
    fun aFieldThatIsNotValidShowsThreeQuestionMarks() {
        assertEquals("???", AdapterDetailsRules.fieldText(null))
        assertEquals("???", AdapterDetailsRules.fieldText(""))
        assertEquals("tda-example", AdapterDetailsRules.fieldText("tda-example"))
        // A MAC that is not 12 hex digits is dropped: the field stays not valid.
        assertEquals("???", AdapterDetailsRules.fieldText(AdapterDetailsRules.bluetoothId(values(0x05 to "12345"), null)))
    }

    @Test
    fun theFirmwareDateShowsInLocalTimeAsYearMonthDayHoursMinutes() {
        val seconds = values(0x08 to "67df4f00").date.value
        assertEquals(1_742_688_000L, seconds)
        assertEquals("2025-03-23 00:00", AdapterDetailsRules.dateText(seconds, TimeZone.UTC))
        assertEquals("2025-03-22 17:00", AdapterDetailsRules.dateText(seconds, TimeZone.of("America/Los_Angeles")))
        assertEquals("2025-03-23 01:00", AdapterDetailsRules.dateText(seconds, TimeZone.of("Europe/Amsterdam")))
        assertEquals("???", AdapterDetailsRules.dateText(null, TimeZone.UTC))
    }

    @Test
    fun theBluetoothIdIsTheMacPlusTwoWithTheCarry() {
        assertEquals("24:6f:28:a1:b2:c5", AdapterDetailsRules.bluetoothId(values(0x05 to "246f28a1b2c3"), null))
        // A sum above 0xFF in the last byte also changes the byte before it.
        assertEquals("24:6f:28:a1:b3:00", AdapterDetailsRules.bluetoothId(values(0x05 to "246f28a1b2fe"), null))
        assertEquals("24:6f:28:a1:b3:01", AdapterDetailsRules.bluetoothId(values(0x05 to "246F28A1B2FF"), null))
        assertEquals("00:00:00:00:00:01", AdapterDetailsRules.bluetoothId(values(0x05 to "ffffffffffff"), null))
        // With no MAC, the identity of the adapter profile shows.
        assertEquals("aa:bb:cc:dd:ee:ff", AdapterDetailsRules.bluetoothId(AdapterValues(), AdapterProfile(identity = "aa:bb:cc:dd:ee:ff")))
    }

    @Test
    fun theModelComesFromTheAdapterElseFromTheProfile() {
        assertEquals("tda-x", AdapterDetailsRules.model(values(0x04 to "tda-x"), AdapterProfile(model = "old")))
        assertEquals("old", AdapterDetailsRules.model(AdapterValues(), AdapterProfile(model = "old")))
        assertNull(AdapterDetailsRules.model(AdapterValues(), null))
    }

    @Test
    fun theRefreshRateIsOneThousandDividedByTheIntervalWithTwoDecimals() {
        assertEquals("50.00 Hz", AdapterDetailsRules.refreshRate(20L))
        assertEquals("333.33 Hz", AdapterDetailsRules.refreshRate(3L))
        assertEquals("1000.00 Hz", AdapterDetailsRules.refreshRate(1L))
        assertEquals("0.10 Hz", AdapterDetailsRules.refreshRate(9_999L))
        // 0 Hz when dt < 1 ms or dt >= 10000 ms.
        assertEquals("0 Hz", AdapterDetailsRules.refreshRate(0L))
        assertEquals("0 Hz", AdapterDetailsRules.refreshRate(10_000L))
        assertEquals("0 Hz", AdapterDetailsRules.refreshRate(null))
    }

    @Test
    fun aChannelThatStoppedForTenSecondsGivesZeroHertz() {
        assertEquals(20L, AdapterDetailsRules.interval(previousMs = 980L, newestMs = 1_000L, nowMs = 1_500L))
        assertEquals(10_000L, AdapterDetailsRules.interval(previousMs = 980L, newestMs = 1_000L, nowMs = 11_000L))
        assertEquals("0 Hz", AdapterDetailsRules.refreshRate(AdapterDetailsRules.interval(980L, 1_000L, 11_000L)))
        assertNull(AdapterDetailsRules.interval(previousMs = null, newestMs = 1_000L, nowMs = 1_500L))
        assertNull(AdapterDetailsRules.interval(previousMs = null, newestMs = null, nowMs = 1_500L))
    }

    @Test
    fun theRawValueIsTheCountTheTachometerValueOrTheProbeForm() {
        val snapshot = InputSnapshot(
            channels = mapOf(
                'x' to ChannelValue('x', 1234L, 1234L, 1234.0, 10L, ChannelStatus.Ok),
                't' to ChannelValue('t', 4800L, 4800L, 4800.0, 10L, ChannelStatus.Ok),
            ),
            probes = mapOf('p' to true, 'q' to false),
        )
        assertEquals("1234", AdapterDetailsRules.rawValue(AdapterDetailsRules.rawView(InputMode.QuadratureAB), InputChannel.X, snapshot))
        assertEquals("4800", AdapterDetailsRules.rawValue(AdapterDetailsRules.rawView(InputMode.Tachometer), InputChannel.X, snapshot))
        assertEquals("p: 1, q: 0", AdapterDetailsRules.rawValue(AdapterDetailsRules.rawView(InputMode.DigitalProbe), InputChannel.X, snapshot))
        assertEquals("p: 1, q: 0", AdapterDetailsRules.rawValue(AdapterDetailsRules.rawView(InputMode.Unknown), InputChannel.X, snapshot))
        // BIN6 and Mitutoyo SPC show the count of the channel; a count that did not come shows ???.
        assertEquals("???", AdapterDetailsRules.rawValue(AdapterDetailsRules.rawView(InputMode.Bin6), InputChannel.Y, snapshot))
        assertEquals(RawView.ChannelCount, AdapterDetailsRules.rawView(InputMode.MitutoyoSpc))
        // The probe form has no refresh rate: "N/A".
        assertNull(AdapterDetailsRules.rateInput(RawView.ProbeForm, InputChannel.X))
        assertEquals('t', AdapterDetailsRules.rateInput(RawView.TachometerValue, InputChannel.X))
        assertEquals('y', AdapterDetailsRules.rateInput(RawView.ChannelCount, InputChannel.Y))
    }

    @Test
    fun theGearOpensTheEncoderSettingsTheTachPulsesOrTheProbeInput() {
        assertEquals(GearTarget.Encoder('x'), AdapterDetailsRules.gear(InputChannel.X, InputMode.QuadratureAB))
        assertEquals(GearTarget.Encoder('u'), AdapterDetailsRules.gear(InputChannel.U, InputMode.MitutoyoSpc))
        assertEquals(GearTarget.Encoder('z'), AdapterDetailsRules.gear(InputChannel.Z, InputMode.Unknown))
        assertEquals(GearTarget.Tach, AdapterDetailsRules.gear(InputChannel.W, InputMode.Tachometer))
        assertEquals(GearTarget.Probe, AdapterDetailsRules.gear(InputChannel.V, InputMode.DigitalProbe))
        assertNull(AdapterDetailsRules.gear(InputChannel.X, InputMode.BinarySwitch))
        assertNull(AdapterDetailsRules.gear(InputChannel.X, InputMode.LimitXY))
        assertNull(AdapterDetailsRules.gear(InputChannel.X, InputMode.Disabled))
        assertEquals(GearTarget.Probe, AdapterDetailsRules.declaredGear(InputChannel.U))
        assertEquals(GearTarget.Tach, AdapterDetailsRules.declaredGear(InputChannel.V))
        assertEquals(GearTarget.Encoder('w'), AdapterDetailsRules.declaredGear(InputChannel.W))
    }

    @Test
    fun theModeAnswersFillTheDeclaredInputsOfATwoWayAdapter() {
        val partial = values(0x01 to "x000f0002")
        assertNull(AdapterDetailsRules.declaredFromModes(partial), "A channel with no answer gives no result.")
        val all = values(
            0x01 to "x000f0002",
            0x01 to "y00030002",
            0x01 to "z00010001",
            0x01 to "w00050004",
            0x01 to "u00090008",
            0x01 to "v00010001",
        )
        assertEquals(
            setOf(DeclaredInput.X, DeclaredInput.Y, DeclaredInput.W, DeclaredInput.Probe, DeclaredInput.Tach),
            AdapterDetailsRules.declaredFromModes(all),
        )
    }

    @Test
    fun theDeclaredRowsShowOnlyWhenTheAdapterReportsNoMode() {
        val legacy = AdapterProfile(legacy = true, declaredInputs = setOf(DeclaredInput.X, DeclaredInput.Probe))
        assertTrue(AdapterDetailsRules.showDeclared(AdapterValues(), legacy))
        assertFalse(AdapterDetailsRules.showDeclared(values(0x01 to "x000f0002"), legacy))
        assertFalse(AdapterDetailsRules.showDeclared(AdapterValues(), AdapterProfile()))
        assertFalse(AdapterDetailsRules.showDeclared(AdapterValues(), null))
    }

    @Test
    fun theFailureTextsOfTheCommands() {
        val failed = CommandResult.Failed(CommandFailure.of(6, 3))
        assertEquals(UiText.Plain("Command failed: Invalid argument (6:3)"), AdapterDetailsRules.failureText(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer), failed))
        assertEquals(UiText.Plain("Command failed: Bad data (10:33)"), AdapterDetailsRules.failureText(AdapterCommand.ClearCounter(InputChannel.X), CommandResult.Failed(CommandFailure.of(10, 33))))
        val mode = AdapterDetailsRules.failureText(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer), CommandResult.NotConfirmed)
        assertEquals(UiText.of(Res.string.adapter_failure_mode), mode)
        assertEquals("The adapter did not confirm the input mode change.", mode!!.english())
        assertEquals(UiText.of(Res.string.adapter_failure_reference), AdapterDetailsRules.failureText(AdapterCommand.RefAxis(InputChannel.X), CommandResult.NotConfirmed))
        assertEquals(UiText.of(Res.string.adapter_failure_clear), AdapterDetailsRules.failureText(AdapterCommand.ClearCounter(InputChannel.X), CommandResult.NotConfirmed))
        assertEquals(UiText.of(Res.string.adapter_command_not_sent), AdapterDetailsRules.failureText(AdapterCommand.Reboot, CommandResult.NotSent))
        assertEquals(UiText.of(Res.string.adapter_command_cancelled), AdapterDetailsRules.failureText(AdapterCommand.Reboot, CommandResult.Cancelled))
        assertNull(AdapterDetailsRules.failureText(AdapterCommand.Reboot, CommandResult.Completed))
    }
}
