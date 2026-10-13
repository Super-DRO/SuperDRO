package org.superdro.core.ui.readout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AngularAxisConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.resources.Res
import org.superdro.core.resources.keypad_divisions_invalid
import org.superdro.core.ui.readout.details.DivisionsPresenter
import org.superdro.core.ui.readout.details.FeedTexts
import org.superdro.core.ui.readout.keypad.KeypadKey
import org.superdro.core.ui.readout.keypad.KeypadPresenter
import org.superdro.core.ui.text.UiText

/** The presenters of the keypad and the divisions sheet, and the feed values of the axis details. */
class ReadoutSheetsTest {

    private val scope = TestScope()
    private val harness = ReadoutHarness(scope)

    @AfterTest
    fun close() = harness.close()

    @Test
    fun theKeysOfTheKeypadMakeTheExpression() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 10_000.0)
        val keypad = KeypadPresenter(AxisId.X, harness.presenter)
        listOf(KeypadKey.OpenParenthesis, KeypadKey.Digit2, KeypadKey.Plus, KeypadKey.Digit3, KeypadKey.CloseParenthesis, KeypadKey.Times, KeypadKey.Digit4)
            .forEach(keypad::onKey)
        assertEquals("(2+3)*4", keypad.uiState.value.text)
        assertEquals("20.000", keypad.uiState.value.result)
        keypad.onKey(KeypadKey.Divide)
        keypad.onKey(KeypadKey.Digit8)
        assertEquals("(2+3)*4/8", keypad.uiState.value.text)
        assertEquals("2.500", keypad.uiState.value.result)
        keypad.onKey(KeypadKey.Backspace)
        assertEquals("(2+3)*4/", keypad.uiState.value.text)
        assertNull(keypad.uiState.value.result)
        assertEquals(UiText.Key("expression_error_expected_expression_after", "Expected expression after '/'", listOf("/")), keypad.uiState.value.error)
        keypad.onKey(KeypadKey.Clear)
        assertEquals("", keypad.uiState.value.text)
        keypad.onKey(KeypadKey.Minus)
        keypad.onKey(KeypadKey.Digit1)
        assertEquals("-1", keypad.uiState.value.text)
    }

    @Test
    fun theRelativeKeysToggleThePrefix() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 10_000.0)
        val keypad = KeypadPresenter(AxisId.X, harness.presenter)
        keypad.onKey(KeypadKey.Digit5)
        keypad.onKey(KeypadKey.RelativeAdd)
        assertEquals("++5", keypad.uiState.value.text)
        assertEquals("15.000", keypad.uiState.value.result)
        keypad.onKey(KeypadKey.RelativeSubtract)
        assertEquals("--5", keypad.uiState.value.text)
        assertEquals("5.000", keypad.uiState.value.result)
        keypad.onKey(KeypadKey.RelativeSubtract)
        assertEquals("5", keypad.uiState.value.text)
        assertTrue(keypad.onSet())
        runCurrent()
        assertEquals("5.000", harness.text(AxisId.X))
    }

    @Test
    fun theAngularAxisDetailsShowThePositionTheUnitAndTheButtons() = scope.runTest {
        val machine = MachineProfile.create(name = "Rotary").let { base -> base.copy(angular = base.angular.copy(enabled = true)) }
        harness.start(machine = machine, states = mapOf(AxisId.Angular to AxisState(divisionsPerRev = 24)))
        // Angular ppu 1440, count 250: R = 225000″.
        harness.inputs(AxisId.Angular to 225_000.0)
        assertEquals("62.50", harness.text(AxisId.Angular))
        assertEquals(DisplayUnit.Degrees, harness.presenter.axisValue(AxisId.Angular).value.unit)
        // The units button: degrees or divisions; N = 24 gives "4.17" divisions.
        harness.presenter.onActionTap(AxisId.Angular, AxisButtonMode.ToggleUnits)
        advanceUntilIdle()
        assertEquals("4.17", harness.text(AxisId.Angular))
        assertEquals(DisplayUnit.Divisions, harness.presenter.axisValue(AxisId.Angular).value.unit)
        harness.presenter.onActionTap(AxisId.Angular, AxisButtonMode.ToggleUnits)
        advanceUntilIdle()
        // Half: i(shown / 2) gives 31.25°.
        harness.presenter.onActionTap(AxisId.Angular, AxisButtonMode.SetCenter)
        advanceUntilIdle()
        assertEquals("31.25", harness.text(AxisId.Angular))
        // Zero: i(0).
        harness.presenter.onActionTap(AxisId.Angular, AxisButtonMode.SetZero)
        advanceUntilIdle()
        assertEquals("0.00", harness.text(AxisId.Angular))
    }

    @Test
    fun theDivisionsKeypadNeedsAWholeNumberOfOneOrMore() = scope.runTest {
        harness.start(MachineProfile.create().copy(angular = AngularAxisConfig(enabled = true)))
        harness.inputs(AxisId.Angular to 225_000.0)
        val divisions = DivisionsPresenter(harness.presenter)
        assertEquals("", divisions.uiState.value.text, "N is not set.")
        divisions.onKey(KeypadKey.Digit0)
        assertFalse(divisions.onSet())
        assertEquals(UiText.of(Res.string.keypad_divisions_invalid), divisions.uiState.value.error)
        divisions.onKey(KeypadKey.Clear)
        divisions.onKey(KeypadKey.Digit2)
        divisions.onKey(KeypadKey.Digit4)
        divisions.onKey(KeypadKey.Dot)
        assertEquals("24", divisions.uiState.value.text, "Only the digits go into the field.")
        assertTrue(divisions.onSet())
        advanceUntilIdle()
        assertEquals("4.17", harness.text(AxisId.Angular))
        assertEquals("24", DivisionsPresenter(harness.presenter).uiState.value.text, "The sheet shows N.")
    }

    @Test
    fun theFeedValuesOfTheWorkedExample() {
        // The slide moves 250 µm each 50 ms: 300000 µm/min. At 1200 RPM, fpr = 250 µm. With 4 teeth.
        val metric = FeedTexts.of(300_000.0, 1_200.0, 4, metric = true, format = ReadoutFormat.Format3, showsThread = true)
        assertEquals("300.000", metric.rate)
        assertEquals("0.063", metric.chipLoad)
        assertEquals("0.25", metric.thread)
        val inch = FeedTexts.of(300_000.0, 1_200.0, 4, metric = false, format = ReadoutFormat.Format3, showsThread = true)
        assertEquals("11.8110", inch.rate)
        assertEquals("0.0025", inch.chipLoad)
        assertEquals("101.6", inch.thread)
        val noRpm = FeedTexts.of(300_000.0, 0.0, 4, metric = true, format = ReadoutFormat.Format3, showsThread = false)
        assertEquals("0.000", noRpm.chipLoad, "fpr = 0 when RPM = 0.")
        assertNull(noRpm.thread, "The thread shows only on the Z axis of a lathe.")
    }
}
