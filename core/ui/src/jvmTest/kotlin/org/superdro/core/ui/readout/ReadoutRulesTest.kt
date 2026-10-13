package org.superdro.core.ui.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AngularAxisConfig
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.CaliperConfig
import org.superdro.core.model.FunctionButtonSettings
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TachometerConfig
import org.superdro.core.model.Workspace
import org.superdro.core.ui.navigation.ArbitraryHoleCircleRoute
import org.superdro.core.ui.navigation.GraphicalViewRoute
import org.superdro.core.ui.navigation.HoleCircleRoute
import org.superdro.core.ui.navigation.ZReferenceRoute
import org.superdro.core.ui.readout.FunctionButton as B

/** The pure rules of the readout screen: the rows, the geometry, the function strip, and the setup reminder. */
class ReadoutRulesTest {

    private val mill = MachineProfile.create(MachineType.VerticalMill)
    private val horizontal = MachineProfile.create(MachineType.HorizontalMill)
    private val lathe = MachineProfile.create(MachineType.Lathe)

    @Test
    fun theRowOrderOfAMillAndALathe() {
        val all = mill.copy(angular = AngularAxisConfig(enabled = true), tachometer = TachometerConfig(enabled = true))
        assertEquals(listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular, AxisId.Tachometer), ReadoutRows.rows(all, collapsed = false).map { it.axis })
        val latheAll = lathe.copy(y = lathe.y.copy(enabled = true), angular = AngularAxisConfig(enabled = true), tachometer = TachometerConfig(enabled = true))
        val rows = ReadoutRows.rows(latheAll, collapsed = false)
        assertEquals(listOf(AxisId.X, AxisId.Z, AxisId.Y, AxisId.Angular, AxisId.Tachometer), rows.map { it.axis })
        assertTrue(rows.first { it.axis == AxisId.Y }.auxiliary, "The lathe Y row is the auxiliary readout.")
        val noY = mill.copy(y = mill.y.copy(enabled = false))
        assertEquals(listOf(AxisId.X, AxisId.Z), ReadoutRows.rows(noY, collapsed = false).map { it.axis })
        assertEquals(RowKind.Tachometer, ReadoutRows.rows(all, false).last().kind)
        assertEquals(5, ReadoutRows.count(all))
    }

    @Test
    fun theActionButtonsFollowShowExpandedAndShowCollapsed() {
        // The defaults: modes 0, 1, 2; expanded true, true, true; collapsed true, false, false.
        assertEquals(listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits), ReadoutRows.rows(mill, false).first().buttons)
        assertEquals(listOf(AxisButtonMode.SetZero), ReadoutRows.rows(mill, true).first().buttons)
        val custom = mill.copy(
            x = mill.x.copy(
                buttons = listOf(
                    AxisButtonConfig(AxisButtonMode.Hold, showExpanded = false, showCollapsed = true),
                    AxisButtonConfig(AxisButtonMode.DivideCircle, showExpanded = true, showCollapsed = true),
                    AxisButtonConfig(AxisButtonMode.SetCenter, showExpanded = true, showCollapsed = false),
                ),
            ),
        )
        assertEquals(listOf(AxisButtonMode.SetCenter), ReadoutRows.rows(custom, false).first().buttons, "Mode 4 is not on a linear axis.")
        assertEquals(listOf(AxisButtonMode.Hold), ReadoutRows.rows(custom, true).first().buttons)
    }

    @Test
    fun eachRowGetsOneOverMaxThreeAndCountOfThePane() {
        assertEquals(200f, ReadoutGeometry.rowHeight(600f, 1))
        assertEquals(200f, ReadoutGeometry.rowHeight(600f, 3))
        assertEquals(150f, ReadoutGeometry.rowHeight(600f, 4))
        assertEquals(120f, ReadoutGeometry.rowHeight(600f, 5))
        assertEquals(3, ReadoutGeometry.slots(2))
        assertEquals(5, ReadoutGeometry.slots(5))
    }

    @Test
    fun theTextSizeFitsTheRowHeightAndTheWidth() {
        // The template is narrow enough: the height sets the size.
        assertEquals(100f, ReadoutGeometry.textSize(height = 100f, width = 600f, templateWidthAtHeight = 500f))
        // The template at 100 px is 800 px wide in a box of 600 px: size = 600 × 100 / 800 = 75.
        assertEquals(75f, ReadoutGeometry.textSize(height = 100f, width = 600f, templateWidthAtHeight = 800f))
        assertEquals(0f, ReadoutGeometry.textSize(height = 0f, width = 600f, templateWidthAtHeight = 800f))
    }

    @Test
    fun theMillOrderOnAVerticalAndAHorizontalMill() {
        val expected = listOf(
            B.ZReference, B.RadiusCompensation, B.HeightCompensation, B.EdgeFinder, B.HoleCircle, B.ArbitraryHoleCircle,
            B.ArcCut, B.HoleGrid, B.EquidistantArray, B.AngleMeasure, B.GraphicalView, B.RotatePart,
        )
        assertEquals(expected, FunctionStripRules.buttons(mill, null).map { it.button })
        assertEquals(expected, FunctionStripRules.buttons(horizontal, null).map { it.button }, "A horizontal mill has the mill buttons.")
        val withCaliper = mill.copy(caliper = CaliperConfig(enabled = true))
        assertTrue(B.DimensionMeasure in FunctionStripRules.buttons(withCaliper, null).map { it.button })
        val withQuill = mill.copy(z = mill.z.copy(secondary = InputConfig('w')))
        val buttons = FunctionStripRules.buttons(withQuill, null).map { it.button }
        assertEquals(listOf(B.GraphicalView, B.HeadTiltCompensation, B.RotatePart), buttons.takeLast(3))
    }

    @Test
    fun theLatheOrder() {
        val expected = listOf(
            B.RadiusDiameter, B.LatheToolOffset, B.ArcCut, B.AngleMeasure, B.EquidistantArray, B.HoleGrid, B.HoleCircle,
            B.GraphicalView, B.ThreadSetup,
        )
        assertEquals(expected, FunctionStripRules.buttons(lathe, null).map { it.button })
        val full = lathe.copy(
            caliper = CaliperConfig(enabled = true),
            y = lathe.y.copy(enabled = true),
            auxiliary = AuxiliaryConfig(type = AuxiliaryType.CompoundSlide),
        )
        val buttons = FunctionStripRules.buttons(full, null).map { it.button }
        assertEquals(B.DimensionMeasure, buttons[2], "The dimension measure is the third button of a lathe.")
        assertEquals(B.CompoundCompensation, buttons.last())
        val tailstock = full.copy(auxiliary = AuxiliaryConfig(type = AuxiliaryType.Tailstock))
        assertFalse(B.CompoundCompensation in FunctionStripRules.buttons(tailstock, null).map { it.button })
    }

    @Test
    fun eachShowSwitchHidesItsButton() {
        val switches: List<Pair<B, (FunctionButtonSettings) -> FunctionButtonSettings>> = listOf(
            B.ZReference to { s -> s.copy(zReference = false) },
            B.RadiusCompensation to { s -> s.copy(radiusCompensation = false) },
            B.HeightCompensation to { s -> s.copy(heightCompensation = false) },
            B.EdgeFinder to { s -> s.copy(edgeFinder = false) },
            B.HoleCircle to { s -> s.copy(holeCircle = false) },
            B.ArbitraryHoleCircle to { s -> s.copy(arbitraryHoleCircle = false) },
            B.ArcCut to { s -> s.copy(arcCut = false) },
            B.HoleGrid to { s -> s.copy(holeGrid = false) },
            B.EquidistantArray to { s -> s.copy(equidistantArray = false) },
            B.DimensionMeasure to { s -> s.copy(dimensionMeasure = false) },
            B.AngleMeasure to { s -> s.copy(angleMeasure = false) },
            B.HeadTiltCompensation to { s -> s.copy(headTilt = false) },
        )
        val full = mill.copy(caliper = CaliperConfig(enabled = true), z = mill.z.copy(secondary = InputConfig('w')))
        switches.forEach { (button, off) ->
            assertTrue(button in FunctionStripRules.buttons(full, null).map { it.button }, "$button shows when its switch is on.")
            val hidden = full.copy(functionButtons = off(full.functionButtons))
            assertFalse(button in FunctionStripRules.buttons(hidden, null).map { it.button }, "$button hides when its switch is off.")
        }
        val latheFull = lathe.copy(y = lathe.y.copy(enabled = true), auxiliary = AuxiliaryConfig(type = AuxiliaryType.CompoundSlide))
        assertTrue(B.CompoundCompensation in FunctionStripRules.buttons(latheFull, null).map { it.button })
        val noCompound = latheFull.copy(functionButtons = latheFull.functionButtons.copy(compoundAngle = false))
        assertFalse(B.CompoundCompensation in FunctionStripRules.buttons(noCompound, null).map { it.button })
        // The buttons with "always" have no switch.
        val allOff = FunctionButtonSettings(false, false, false, false, false, false, false, false, false, false, false, false, false)
        assertEquals(listOf(B.GraphicalView, B.RotatePart), FunctionStripRules.buttons(mill.copy(functionButtons = allOff), null).map { it.button })
        assertEquals(listOf(B.RadiusDiameter, B.LatheToolOffset, B.GraphicalView, B.ThreadSetup), FunctionStripRules.buttons(lathe.copy(functionButtons = allOff), null).map { it.button })
    }

    @Test
    fun theMeasurementModeHidesTheCompensationAndShowsTheMeasurementButtons() {
        val measuring = mill.copy(machineMode = MachineMode.Measurement)
        val buttons = FunctionStripRules.buttons(measuring, null).map { it.button }
        assertFalse(B.ZReference in buttons)
        assertFalse(B.RadiusCompensation in buttons)
        assertFalse(B.HeightCompensation in buttons)
        assertEquals(listOf(B.RotatePart, B.AlignAxes, B.ReferenceInstrument), buttons.takeLast(3))
        val horizontalMeasuring = horizontal.copy(machineMode = MachineMode.Measurement)
        assertEquals(buttons, FunctionStripRules.buttons(horizontalMeasuring, null).map { it.button }, "Both mill types.")
        val latheMeasuring = lathe.copy(machineMode = MachineMode.Measurement)
        assertEquals(FunctionStripRules.buttons(lathe, null), FunctionStripRules.buttons(latheMeasuring, null), "No measurement mode on a lathe.")
    }

    @Test
    fun theCompensationButtonsShowTheActiveState() {
        val workspace = Workspace().let { base ->
            base.copy(axisStates = base.axisStates + (AxisId.Z to AxisState(toolOffset = -12_500.0, toolOffsetActive = true)))
        }
        val buttons = FunctionStripRules.buttons(mill, workspace).associateBy { it.button }
        assertTrue(buttons.getValue(B.HeightCompensation).active)
        assertFalse(buttons.getValue(B.RadiusCompensation).active)
        val diameter = Workspace(toolXUm = 10_000.0)
        assertTrue(FunctionStripRules.buttons(mill, diameter).first { it.button == B.RadiusCompensation }.active, "A stored tool diameter makes radius compensation active.")
        val latheWorkspace = Workspace.forMachine(MachineType.Lathe)
        val latheButtons = FunctionStripRules.buttons(lathe, latheWorkspace).associateBy { it.button }
        assertTrue(latheButtons.getValue(B.RadiusDiameter).toggled, "A new lathe workspace shows the diameter.")
        assertFalse(latheButtons.getValue(B.LatheToolOffset).active)
    }

    @Test
    fun eachButtonOpensTheRouteOfItsArea() {
        assertNull(FunctionStripRules.route(B.RadiusDiameter))
        assertEquals(ZReferenceRoute, FunctionStripRules.route(B.ZReference))
        assertEquals(HoleCircleRoute, FunctionStripRules.route(B.HoleCircle))
        assertEquals(GraphicalViewRoute, FunctionStripRules.route(B.GraphicalView))
        assertEquals(ArbitraryHoleCircleRoute(advanced = true), FunctionStripRules.longPressRoute(B.ArbitraryHoleCircle))
        B.entries.filter { it != B.RadiusDiameter }.forEach { button -> assertTrue(FunctionStripRules.route(button) != null, "$button has a route.") }
    }

    @Test
    fun theSetupReminderChecksThePrimaryAndTheSecondaryInputs() {
        val calibrated = org.superdro.core.model.AdapterProfile(alias = "A").let { adapter ->
            adapter.copy(encoders = adapter.encoders.mapValues { (_, encoder) ->
                encoder.copy(calibration = org.superdro.core.model.Calibration(org.superdro.core.model.CalibrationStatus.Calibrated))
            })
        }
        assertNull(SetupReminder.axisNeedingSetup(mill, calibrated))
        assertEquals(AxisId.X, SetupReminder.axisNeedingSetup(mill, null))
        val notCalibratedW = calibrated.copy(encoders = calibrated.encoders + ('w' to org.superdro.core.model.EncoderProfile.defaultFor('w')))
        assertNull(SetupReminder.axisNeedingSetup(mill, notCalibratedW), "No enabled axis uses w.")
        val quill = mill.copy(z = mill.z.copy(secondary = InputConfig('w')))
        assertEquals(AxisId.Z, SetupReminder.axisNeedingSetup(quill, notCalibratedW), "The secondary input counts.")
    }
}
