package org.superdro.core.ui.functions.patterns

import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.arc.AngleReference
import org.superdro.core.functions.arc.CutType
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.PartRotation
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.graphics.view.OperatorFrame

/**
 * The presenter of the pattern screens with the worked examples. The expected values are the texts
 * of the tables, in mm with 3 decimals.
 */
class PatternPresenterTest {

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.ENGLISH)
    }

    /** Selects the custom origin ([x], [y]) mm and turns Z off (a 2D pattern). */
    private fun TestScope.customOrigin(presenter: PatternPresenter, x: String, y: String) {
        presenter.onSourceChange(PositionSource.Custom)
        presenter.onAxisSwitch(AxisLetter.Z, false)
        type(presenter, PatternInput.CustomX, x)
        type(presenter, PatternInput.CustomY, y)
    }

    /** Selects the origin "Zero" and turns Z off. */
    private fun TestScope.zeroOrigin(presenter: PatternPresenter) {
        presenter.onSourceChange(PositionSource.Zero)
        presenter.onAxisSwitch(AxisLetter.Z, false)
        advanceUntilIdle()
    }

    private suspend fun TestScope.create(presenter: PatternPresenter, harness: FunctionsHarness): List<SubDatum> {
        val before = harness.readout.workspace().subDatums.map { it.id }.toSet()
        presenter.onCreate()
        advanceUntilIdle()
        return harness.readout.workspace().subDatums.filter { it.id !in before }
    }

    private fun lines(state: PatternUiState): List<String> = state.errors?.lines.orEmpty().map { line -> line.english }

    @Test
    fun theLegacyHoleCircleGivesTheExpectedTable() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        customOrigin(presenter, "100", "50")
        type(presenter, PatternInput.Radius, "50")
        type(presenter, PatternInput.HoleCount, "6")
        val state = presenter.uiState.value
        // The preview before "Create": the names and the coordinates of the 6 holes.
        assertEquals(6, state.preview.size)
        assertEquals(
            listOf(
                PointRowUiState("Circle - 1", "100.000", "100.000", ""),
                PointRowUiState("Circle - 2", "143.301", "75.000", ""),
                PointRowUiState("Circle - 3", "143.301", "25.000", ""),
                PointRowUiState("Circle - 4", "100.000", "0.000", ""),
                PointRowUiState("Circle - 5", "56.699", "25.000", ""),
                PointRowUiState("Circle - 6", "56.699", "75.000", ""),
            ),
            state.pointRows,
        )
        val points = create(presenter, harness)
        assertEquals(6, presenter.uiState.value.created)
        assertEquals(listOf("Circle - 1", "Circle - 2", "Circle - 3", "Circle - 4", "Circle - 5", "Circle - 6"), points.map { it.name })
        assertEquals(listOf("100.000" to "100.000", "143.301" to "75.000", "143.301" to "25.000", "100.000" to "0.000", "56.699" to "25.000", "56.699" to "75.000"), points.map(::xy))
        assertTrue(points.all { it.z == null })
        // One group: one group ID, the kind Pattern, and the tag of the prefix.
        assertEquals(1, points.map { it.groupId }.distinct().size)
        assertTrue(points.all { it.groupKind == SubDatumGroupKind.Pattern && it.tags == listOf("Circle") })
        // The full value, not the text of the display format: 50 + 50 × cos(30°) mm.
        assertEquals(143_301.270189, points[1].x!!, 1e-5)
        assertEquals(6, harness.readout.workspace().lastOrdinal)
    }

    @Test
    fun theStandardHoleCircleStartsAtThreeOClock() = withPattern(PatternMachines.standardMill(), PatternKind.HoleCircle) { presenter, harness ->
        customOrigin(presenter, "100", "50")
        type(presenter, PatternInput.Radius, "50")
        type(presenter, PatternInput.HoleCount, "6")
        val points = create(presenter, harness)
        assertEquals("150.000" to "50.000", xy(points[0]))
        assertEquals("125.000" to "93.301", xy(points[1]))
    }

    @Test
    fun aSweepOf90WithFourHolesGivesTheAngles0To90() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        customOrigin(presenter, "100", "50")
        type(presenter, PatternInput.Radius, "50")
        type(presenter, PatternInput.HoleCount, "4")
        type(presenter, PatternInput.SweepAngle, "90")
        val points = create(presenter, harness)
        // The angles 0, 30, 60, 90: X = 100 + 50 sin a, Y = 50 + 50 cos a.
        assertEquals(listOf("100.000" to "100.000", "125.000" to "93.301", "143.301" to "75.000", "150.000" to "50.000"), points.map(::xy))
    }

    @Test
    fun aSweepOfMinus360IsAFullCircle() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        zeroOrigin(presenter)
        type(presenter, PatternInput.Radius, "10")
        type(presenter, PatternInput.HoleCount, "4")
        type(presenter, PatternInput.SweepAngle, "-360")
        val points = create(presenter, harness)
        // The last hole is not on the first hole.
        assertEquals(4, points.map(::xy).distinct().size)
    }

    @Test
    fun theReferenceFirstHolePutsHoleOneAtTheOrigin() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        customOrigin(presenter, "100", "50")
        presenter.onReferenceChange(PositionReference.FirstPoint)
        type(presenter, PatternInput.Radius, "50")
        type(presenter, PatternInput.HoleCount, "6")
        assertEquals("First Hole Position", presenter.uiState.value.positionTitle!!.english)
        val points = create(presenter, harness)
        assertEquals("100.000" to "50.000", xy(points[0]))
        assertEquals("143.301" to "25.000", xy(points[1]))
    }

    @Test
    fun theCurrentPositionIsTheOriginAndNeedsAConnectedSession() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        harness.inputs(AxisId.X to 100_000.0, AxisId.Y to 50_000.0, AxisId.Z to -2_000.0)
        type(presenter, PatternInput.Radius, "50")
        type(presenter, PatternInput.HoleCount, "6")
        val points = create(presenter, harness)
        assertEquals("100.000" to "100.000", xy(points[0]))
        // Z is on (3D sub-datums): each hole has the current Z.
        assertTrue(points.all { mm(it.z) == "-2.000" })
        // Without a connected session, "Current" gives the position text of a disconnected session and no point.
        harness.readout.session.state.value = SessionState.Disconnected
        advanceUntilIdle()
        val before = harness.readout.workspace().subDatums.size
        presenter.onCreate()
        advanceUntilIdle()
        val state = presenter.uiState.value
        assertEquals(listOf(harness.presenter.positionUnavailable()!!.english), lines(state))
        assertNotNull(state.sourceError)
        assertEquals(before, harness.readout.workspace().subDatums.size)
    }

    @Test
    fun aPositionFieldTakesTheRelativeEntryAndTheSubDatumList() = withPattern(
        PatternMachines.legacyMill { it.copy(subDatums = listOf(SubDatum(id = "a", name = "Point 1", x = 12_000.0, y = 34_000.0, z = -1_000.0))) },
        PatternKind.HoleCircle,
    ) { presenter, harness ->
        harness.inputs(AxisId.X to 10_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        presenter.onSourceChange(PositionSource.Custom)
        advanceUntilIdle()
        // "Custom" starts with the current position.
        assertEquals("10", presenter.uiState.value.positionRows.first { it.axis == AxisLetter.X }.field.text)
        type(presenter, PatternInput.CustomX, "++5")
        assertEquals(15_000.0, presenter.uiState.value.positionRows.first { it.axis == AxisLetter.X }.field.valueUm!!, 1e-9)
        presenter.onSubDatumSelected("a")
        advanceUntilIdle()
        val rows = presenter.uiState.value.positionRows.associateBy { it.axis }
        assertEquals(12_000.0, rows.getValue(AxisLetter.X).field.valueUm!!, 1e-9)
        assertEquals(34_000.0, rows.getValue(AxisLetter.Y).field.valueUm!!, 1e-9)
        assertEquals(-1_000.0, rows.getValue(AxisLetter.Z).field.valueUm!!, 1e-9)
        assertEquals(listOf(SubDatumChoice("a", "Point 1")), presenter.uiState.value.subDatums)
    }

    @Test
    fun aLengthFieldChangesItsUnitAndKeepsTheValue() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, _ ->
        type(presenter, PatternInput.Radius, "50.8")
        presenter.onUnit(PatternInput.Radius, false)
        advanceUntilIdle()
        val field = presenter.uiState.value.field(PatternInput.Radius)!!.length!!
        assertFalse(field.metric)
        assertEquals("2", field.text)
        assertEquals(50_800.0, field.valueUm!!, 1e-9)
    }

    @Test
    fun theErrorTextsOfTheHoleCircle() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        zeroOrigin(presenter)
        presenter.onPrefixChange("ab")
        presenter.onCreate()
        advanceUntilIdle()
        var state = presenter.uiState.value
        assertEquals("This operation could not be completed. Please fix the errors below:", state.errors!!.header!!.english)
        assertEquals(
            listOf("Sub-Datum Prefix must be at least 3 characters long.", "Circle Radius value is required", "Hole Count value is required"),
            lines(state),
        )
        presenter.onPrefixChange("A very long prefix text")
        type(presenter, PatternInput.Radius, "2+")
        type(presenter, PatternInput.HoleCount, "101")
        presenter.onCreate()
        advanceUntilIdle()
        state = presenter.uiState.value
        assertEquals(
            listOf("Circle Radius value could not be converted to a number", "Sub-Datum Prefix can't be longer than 16 characters.", "Hole Count value is too large"),
            lines(state),
        )
        assertEquals(0, harness.readout.workspace().subDatums.size)
        // A change of a field removes the error box.
        type(presenter, PatternInput.HoleCount, "6")
        assertNull(presenter.uiState.value.errors)
    }

    @Test
    fun twoGroupsHaveTheNextNumbersAndTheirOwnTags() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        zeroOrigin(presenter)
        type(presenter, PatternInput.Radius, "10")
        type(presenter, PatternInput.HoleCount, "3")
        val first = create(presenter, harness)
        val second = create(presenter, harness)
        assertEquals(listOf("Circle - 1", "Circle - 2", "Circle - 3"), first.map { it.name })
        assertEquals(listOf("Circle - 4", "Circle - 5", "Circle - 6"), second.map { it.name })
        assertEquals(6, harness.readout.workspace().lastOrdinal)
        assertEquals(listOf("Circle"), first.flatMap { it.tags }.distinct())
        assertEquals(listOf("Circle_2"), second.flatMap { it.tags }.distinct())
    }

    @Test
    fun aLatheHoleCircleUsesThePlaneZxAndTheNamesWithTheSuffix() = withPattern(PatternMachines.lathe(diameterMode = false), PatternKind.HoleCircle) { presenter, harness ->
        presenter.onSourceChange(PositionSource.Zero)
        advanceUntilIdle()
        var state = presenter.uiState.value
        assertEquals("Circular Point Array", state.title.english)
        assertEquals(Plane.ZX, state.plane)
        assertEquals(listOf(Plane.ZX), state.planes)
        assertFalse(state.planeEnabled)
        // A lathe has no Y row.
        assertEquals(listOf(AxisLetter.X, AxisLetter.Z), state.positionRows.map { it.axis })
        presenter.onPlaneChange(Plane.XY)
        advanceUntilIdle()
        state = presenter.uiState.value
        assertEquals(Plane.ZX, state.plane)
        type(presenter, PatternInput.Radius, "10")
        type(presenter, PatternInput.HoleCount, "4")
        val points = create(presenter, harness)
        assertEquals("Circle (ZX) - 1", points.first().name)
        // LEGACY on a lathe: px = R sin a goes to X, py = R cos a goes to Z.
        assertEquals("0.000", mm(points[0].x))
        assertEquals("10.000", mm(points[0].z))
        assertNull(points[0].y)
    }

    @Test
    fun aMillPatternInThePlaneXzGetsTheSuffixAndTheMap() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleCircle) { presenter, harness ->
        presenter.onPlaneChange(Plane.XZ)
        presenter.onSourceChange(PositionSource.Zero)
        advanceUntilIdle()
        // XZ: X and Z are on, Y follows the 3D sub-datum switch.
        val rows = presenter.uiState.value.positionRows.associateBy { it.axis }
        assertTrue(rows.getValue(AxisLetter.X).on && rows.getValue(AxisLetter.Z).on && rows.getValue(AxisLetter.Y).on)
        presenter.onAxisSwitch(AxisLetter.Y, false)
        type(presenter, PatternInput.Radius, "10")
        type(presenter, PatternInput.HoleCount, "4")
        val points = create(presenter, harness)
        assertEquals("Circle (XZ) - 1", points.first().name)
        assertEquals("0.000", mm(points[0].x))
        assertEquals("10.000", mm(points[0].z))
        assertNull(points[0].y)
    }

    @Test
    fun theArbitraryHoleCircleGivesTheExpectedPoints() = withPattern(
        PatternMachines.legacyMill().copy(metricDefault = false),
        PatternKind.ArbitraryHoleCircle,
    ) { presenter, harness ->
        zeroOrigin(presenter)
        type(presenter, PatternInput.Radius, "1")
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("At least two hole angles are required."), lines(presenter.uiState.value))
        listOf("10", "100", "100", "200").forEach { angle ->
            type(presenter, PatternInput.AngleEntry, angle)
            presenter.onAddAngle()
            advanceUntilIdle()
        }
        // A duplicate angle does not go into the list; the entry field is empty after an add.
        assertEquals(listOf(10.0, 100.0, 200.0), presenter.uiState.value.angles)
        assertEquals("", presenter.uiState.value.field(PatternInput.AngleEntry)!!.number!!.text)
        val points = create(presenter, harness)
        assertEquals(listOf("4.411" to "25.014", "25.014" to "-4.411", "-8.687" to "-23.868"), points.map(::xy))
        assertEquals("Arbitrary Hole Circle", presenter.uiState.value.title.english)
    }

    @Test
    fun theAngleListRoundsAndRemovesAngles() = withPattern(PatternMachines.legacyMill(), PatternKind.ArbitraryHoleCircle) { presenter, _ ->
        type(presenter, PatternInput.AngleEntry, "10.123456")
        presenter.onAddAngle()
        type(presenter, PatternInput.AngleEntry, "-90")
        presenter.onAddAngle()
        advanceUntilIdle()
        assertEquals(listOf(10.1235, 270.0), presenter.uiState.value.angles.map { Math.round(it * 10_000) / 10_000.0 })
        type(presenter, PatternInput.AngleEntry, "400")
        presenter.onAddAngle()
        advanceUntilIdle()
        assertEquals("Hole Angle value is too large", presenter.uiState.value.angleError!!.english)
        presenter.onRemoveAngle(0)
        advanceUntilIdle()
        assertEquals(1, presenter.uiState.value.angles.size)
    }

    @Test
    fun theHoleGridGivesTheExampleAt30Degrees() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleGrid) { presenter, harness ->
        zeroOrigin(presenter)
        type(presenter, PatternInput.Repeat1, "3")
        type(presenter, PatternInput.Step1, "10")
        type(presenter, PatternInput.Repeat2, "2")
        type(presenter, PatternInput.Step2, "20")
        type(presenter, PatternInput.GridAngle, "30")
        val points = create(presenter, harness)
        assertEquals(
            listOf("0.000" to "0.000", "8.660" to "-5.000", "17.321" to "-10.000", "10.000" to "17.321", "18.660" to "12.321", "27.321" to "7.321"),
            points.map(::xy),
        )
        assertEquals("Grid - 1", points.first().name)
    }

    @Test
    fun theHoleGridNeedsARepeatOfMoreThanOne() = withPattern(PatternMachines.legacyMill(), PatternKind.HoleGrid) { presenter, _ ->
        zeroOrigin(presenter)
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Repeat X or Repeat Y value must be larger than 1"), lines(presenter.uiState.value))
        presenter.onPlaneChange(Plane.YZ)
        presenter.onAxisSwitch(AxisLetter.X, false)
        advanceUntilIdle()
        assertEquals(listOf("Repeat Y", "Step Y", "Repeat Z", "Step Z", "Angle"), presenter.uiState.value.fields.map { it.label.english })
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Repeat Y or Repeat Z value must be larger than 1"), lines(presenter.uiState.value))
        type(presenter, PatternInput.Repeat1, "3")
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Step Y value is required"), lines(presenter.uiState.value))
    }

    @Test
    fun aLatheGridIsALinearPointArray() = withPattern(PatternMachines.lathe(), PatternKind.HoleGrid) { presenter, _ ->
        val state = presenter.uiState.value
        assertEquals("Linear Point Array", state.title.english)
        assertEquals(listOf("Repeat X", "Step X", "Repeat Z", "Step Z", "Angle"), state.fields.map { it.label.english })
    }

    @Test
    fun theEquidistantArrayGivesTheExpectedPoints() = withPattern(PatternMachines.legacyMill(use3d = false), PatternKind.EquidistantArray) { presenter, harness ->
        // Z is not in the array when the 3D sub-datums are off.
        assertEquals(
            listOf(PatternInput.Count, PatternInput.StartX, PatternInput.EndX, PatternInput.StartY, PatternInput.EndY),
            presenter.uiState.value.fields.map { it.input },
        )
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals("Please enter a valid number of holes.", lines(presenter.uiState.value).first())
        type(presenter, PatternInput.Count, "2")
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals("Number of holes can't be less than 3.", lines(presenter.uiState.value).first())
        type(presenter, PatternInput.Count, "5")
        type(presenter, PatternInput.StartX, "0")
        type(presenter, PatternInput.EndX, "100")
        type(presenter, PatternInput.StartY, "0")
        type(presenter, PatternInput.EndY, "")
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Please enter a valid number for the ending Y position."), lines(presenter.uiState.value))
        type(presenter, PatternInput.EndY, "50")
        val points = create(presenter, harness)
        assertEquals(listOf("0.000" to "0.000", "25.000" to "12.500", "50.000" to "25.000", "75.000" to "37.500", "100.000" to "50.000"), points.map(::xy))
        assertEquals(listOf("Grid - 1", "Grid - 2", "Grid - 3", "Grid - 4", "Grid - 5"), points.map { it.name })
        assertTrue(points.all { it.z == null && it.tags == listOf("Grid") })
        assertEquals("Equidistant Hole Array", presenter.uiState.value.title.english)
    }

    @Test
    fun theEquidistantEndStartsAtTheCurrentPosition() {
        val scope = TestScope()
        val harness = FunctionsHarness(scope)
        try {
            scope.runTest {
                harness.start(PatternMachines.lathe(diameterMode = false))
                harness.inputs(AxisId.X to 12_000.0, AxisId.Z to -30_000.0)
                val presenter = PatternPresenter(PatternKind.EquidistantArray, harness.presenter, harness.services)
                advanceUntilIdle()
                val state = presenter.uiState.value
                // A lathe has no Y in the array; Z is in the array (3D sub-datums on).
                assertEquals(
                    listOf(PatternInput.Count, PatternInput.StartX, PatternInput.EndX, PatternInput.StartZ, PatternInput.EndZ),
                    state.fields.map { it.input },
                )
                assertEquals("12", state.field(PatternInput.EndX)!!.length!!.text)
                assertEquals("-30", state.field(PatternInput.EndZ)!!.length!!.text)
                assertEquals("Equidistant Point Array", state.title.english)
                presenter.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun theArcCutGivesTheExpectedTable() {
        // The center reference: 17 points, step 5.6250°.
        withPattern(PatternMachines.legacyMill(), PatternKind.ArcCut) { presenter, harness ->
            zeroOrigin(presenter)
            type(presenter, PatternInput.Radius, "20")
            type(presenter, PatternInput.CutterDiameter, "6")
            type(presenter, PatternInput.Stepover, "2")
            type(presenter, PatternInput.StartAngle, "0")
            assertEquals("90", presenter.uiState.value.field(PatternInput.SweepAngle)!!.number!!.text)
            val points = create(presenter, harness)
            assertEquals(17, points.size)
            assertEquals("0.000" to "23.000", xy(points[0]))
            assertEquals("2.254" to "22.889", xy(points[1]))
            assertEquals("23.000" to "0.000", xy(points.last()))
            assertEquals("Arc - 1", points.first().name)
            assertEquals("Radius/Arc Cutting", presenter.uiState.value.title.english)
        }
        // The tangent reference: 15 points.
        withPattern(PatternMachines.legacyMill(), PatternKind.ArcCut) { presenter, harness ->
            zeroOrigin(presenter)
            type(presenter, PatternInput.Radius, "20")
            type(presenter, PatternInput.CutterDiameter, "6")
            type(presenter, PatternInput.Stepover, "2")
            type(presenter, PatternInput.StartAngle, "0")
            presenter.onAngleReference(AngleReference.Tangent)
            val points = create(presenter, harness)
            assertEquals(15, points.size)
            assertEquals("2.994" to "22.804", xy(points[0]))
            assertEquals("5.111" to "22.425", xy(points[1]))
            assertEquals("22.804" to "2.994", xy(points.last()))
        }
        // The internal cut: the point radius is R − D/2 = 17 mm.
        withPattern(PatternMachines.legacyMill(), PatternKind.ArcCut) { presenter, harness ->
            zeroOrigin(presenter)
            type(presenter, PatternInput.Radius, "20")
            type(presenter, PatternInput.CutterDiameter, "6")
            type(presenter, PatternInput.Stepover, "2")
            type(presenter, PatternInput.StartAngle, "0")
            presenter.onCutType(CutType.Internal)
            val points = create(presenter, harness)
            assertEquals(17, points.size)
            assertEquals("0.000" to "17.000", xy(points[0]))
            assertEquals("17.000" to "0.000", xy(points.last()))
        }
        // θ = −90: n = 16, step −5.625°.
        withPattern(PatternMachines.legacyMill(), PatternKind.ArcCut) { presenter, harness ->
            zeroOrigin(presenter)
            type(presenter, PatternInput.Radius, "20")
            type(presenter, PatternInput.CutterDiameter, "6")
            type(presenter, PatternInput.Stepover, "2")
            type(presenter, PatternInput.StartAngle, "0")
            type(presenter, PatternInput.SweepAngle, "-90")
            val points = create(presenter, harness)
            assertEquals(17, points.size)
            // The second point is at −5.625° (354.375°): X = 23 sin(−5.625°).
            assertEquals("-2.254" to "22.889", xy(points[1]))
            assertEquals("-23.000" to "0.000", xy(points.last()))
        }
    }

    @Test
    fun theArcCutChecksAndTheDefaultCutter() = withPattern(
        PatternMachines.legacyMill { it.copy(toolXUm = 6_000.0) },
        PatternKind.ArcCut,
    ) { presenter, _ ->
        // The default cutter diameter of a mill: the radius compensation diameter of the workspace.
        assertEquals(6_000.0, presenter.uiState.value.field(PatternInput.CutterDiameter)!!.length!!.valueUm!!, 1e-9)
        zeroOrigin(presenter)
        type(presenter, PatternInput.Radius, "20")
        type(presenter, PatternInput.Stepover, "50")
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Start Angle value is required"), lines(presenter.uiState.value))
        type(presenter, PatternInput.StartAngle, "0")
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Stepover value too large for this sweep angle."), lines(presenter.uiState.value))
        type(presenter, PatternInput.Stepover, "2")
        presenter.onAngleReference(AngleReference.Tangent)
        type(presenter, PatternInput.CutterDiameter, "40")
        type(presenter, PatternInput.SweepAngle, "10")
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Cutter Diameter value too large for this sweep angle."), lines(presenter.uiState.value))
        presenter.onCutType(CutType.Internal)
        presenter.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Cutter Diameter value too large for an internal cut of this radius."), lines(presenter.uiState.value))
    }

    @Test
    fun theStepAndRepeatOfAMillKeepsTheZOfTheSubDatums() = withPattern(
        PatternMachines.legacyMill {
            it.copy(
                subDatums = listOf(
                    SubDatum(id = "a", name = "Point 1", x = 10_000.0, y = 0.0, z = -5_000.0),
                    SubDatum(id = "b", name = "Point 2", x = 0.0, y = 10_000.0, z = -6_000.0),
                    SubDatum(id = "c", name = "Point 3", x = 5_000.0, y = 5_000.0),
                ),
                lastOrdinal = 3,
            )
        },
        PatternKind.StepAndRepeat,
        sourceIds = listOf("b", "a"),
    ) { presenter, harness ->
        presenter.onSourceChange(PositionSource.Zero)
        advanceUntilIdle()
        val state = presenter.uiState.value
        assertEquals("Step and Repeat 2 Sub-Datums", state.title.english)
        assertEquals(2, state.sourceCount)
        assertFalse(state.planeEnabled)
        type(presenter, PatternInput.Repeat1, "2")
        type(presenter, PatternInput.Step1, "50")
        val points = create(presenter, harness)
        // The first grid point is dropped: one copy of each source, in the list order (a, b).
        assertEquals(listOf("Grid - 4", "Grid - 5"), points.map { it.name })
        assertEquals(listOf("60.000" to "0.000", "50.000" to "10.000"), points.map(::xy))
        assertEquals(listOf("-5.000", "-6.000"), points.map { mm(it.z) })
    }

    @Test
    fun theStepAndRepeatOfTheWorkspaceUsesAllSubDatums() = withPattern(
        PatternMachines.legacyMill {
            it.copy(subDatums = listOf(SubDatum(id = "a", name = "Point 1", x = 1_000.0, y = 2_000.0), SubDatum(id = "b", name = "Point 2", x = 3_000.0, y = 4_000.0)))
        },
        PatternKind.StepAndRepeat,
    ) { presenter, harness ->
        assertEquals("Step and Repeat Workspace", presenter.uiState.value.title.english)
        assertEquals(2, presenter.uiState.value.sourceCount)
        zeroOrigin(presenter)
        type(presenter, PatternInput.Repeat2, "3")
        type(presenter, PatternInput.Step2, "10")
        val points = create(presenter, harness)
        assertEquals(listOf("1.000" to "12.000", "3.000" to "14.000", "1.000" to "22.000", "3.000" to "24.000"), points.map(::xy))
    }

    @Test
    fun theStepAndRepeatOfALatheKeepsXAndZ() = withPattern(
        PatternMachines.lathe(diameterMode = false) { it.copy(subDatums = listOf(SubDatum(id = "a", name = "Point 1", x = 10_000.0, z = -20_000.0))) },
        PatternKind.StepAndRepeat,
        sourceIds = listOf("a"),
    ) { presenter, harness ->
        presenter.onSourceChange(PositionSource.Zero)
        advanceUntilIdle()
        assertEquals(listOf("Repeat X", "Step X", "Repeat Z", "Step Z", "Angle"), presenter.uiState.value.fields.map { it.label.english })
        type(presenter, PatternInput.Repeat2, "2")
        type(presenter, PatternInput.Step2, "30")
        val points = create(presenter, harness)
        assertEquals(1, points.size)
        // X stays X and Z stays Z: the grid step goes along Z.
        assertEquals("10.000", mm(points[0].x))
        assertEquals("10.000", mm(points[0].z))
        assertEquals("Grid (ZX) - 2", points[0].name)
    }

    @Test
    fun anArmedThreadBlocksThePatternScreens() = withPattern(
        PatternMachines.legacyMill { it.copy(armedThreadId = "thread-1") },
        PatternKind.HoleCircle,
    ) { presenter, harness ->
        var state = presenter.uiState.value
        assertEquals("New sub-datums cannot be added while thread cutting is active.", state.blocked!!.english)
        assertTrue(state.closeAfterBlocked)
        presenter.onBlockedDismissed()
        zeroOrigin(presenter)
        type(presenter, PatternInput.Radius, "10")
        type(presenter, PatternInput.HoleCount, "3")
        presenter.onCreate()
        advanceUntilIdle()
        state = presenter.uiState.value
        assertNotNull(state.blocked)
        assertFalse(state.closeAfterBlocked)
        assertEquals(0, harness.readout.workspace().subDatums.size)
    }

    @Test
    fun aPartRotationStoresThePointsInTheWorkspaceFrame() = withPattern(
        PatternMachines.legacyMill { it.copy(partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0)) },
        PatternKind.EquidistantArray,
    ) { presenter, harness ->
        type(presenter, PatternInput.Count, "3")
        type(presenter, PatternInput.StartX, "0")
        type(presenter, PatternInput.EndX, "20")
        type(presenter, PatternInput.StartY, "0")
        type(presenter, PatternInput.EndY, "0")
        type(presenter, PatternInput.StartZ, "0")
        type(presenter, PatternInput.EndZ, "0")
        val points = create(presenter, harness)
        // The shown point (10, 0) is the stored point (0, 10) with φ = −90°.
        assertEquals("0.000" to "10.000", xy(points[1]))
        val frame = harness.presenter.frame.value!!
        val shown = frame.shownSubDatumPoint(points[1])
        assertEquals(10_000.0, shown.x!!, 1e-6)
        assertEquals(0.0, shown.y!!, 1e-6)
    }

    @Test
    fun aHoleCircleAtTheCurrentPositionWithAPartRotationStoresTheMachinePointsUnderTheTool() = withPattern(
        PatternMachines.legacyMill { it.copy(partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0)) },
        PatternKind.HoleCircle,
    ) { presenter, harness ->
        // The readouts stay in machine coordinates: the tool shows at (100, 0) mm in the view.
        harness.inputs(AxisId.X to 100_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        type(presenter, PatternInput.Radius, "10")
        type(presenter, PatternInput.HoleCount, "4")
        val points = create(presenter, harness)
        // LEGACY: the shown holes are (100, 10), (110, 0), (100, -10), (90, 0) around the tool. With
        // α = 90° (φ = −90°) about (0, 0), the inverse transform gives the stored points.
        assertEquals(listOf("-10.000" to "100.000", "0.000" to "110.000", "10.000" to "100.000", "0.000" to "90.000"), points.map(::xy))
        // The graphical view and the readout show each stored point where the user made it.
        val frame = harness.presenter.frame.value!!
        val shown = points.map { point -> frame.shownSubDatumPoint(point) }.map { mm(it.x) to mm(it.y) }
        assertEquals(listOf("100.000" to "10.000", "110.000" to "0.000", "100.000" to "-10.000", "90.000" to "0.000"), shown)
        val view = OperatorFrame.toStored(frame, listOf(SubDatum(id = "c", x = 100_000.0, y = 0.0))).single()
        assertEquals("0.000" to "100.000", xy(view))
    }

    @Test
    fun theStepAndRepeatTurnsTheSourceVectorWithThePartRotation() = withPattern(
        PatternMachines.legacyMill {
            it.copy(
                partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0),
                subDatums = listOf(SubDatum(id = "a", name = "Point 1", x = 10_000.0, y = 0.0)),
                lastOrdinal = 1,
            )
        },
        PatternKind.StepAndRepeat,
        sourceIds = listOf("a"),
    ) { presenter, harness ->
        presenter.onSourceChange(PositionSource.Zero)
        advanceUntilIdle()
        type(presenter, PatternInput.Repeat1, "2")
        type(presenter, PatternInput.Step1, "50")
        val points = create(presenter, harness)
        // The source vector (10, 0) turns with φ = −α = −90° to (0, −10)
        // (a positive α turns clockwise, 22.9.2). The copy shows at the grid point (50, 0) plus that
        // vector: (50, −10). The inverse display transform stores it at (10, 50).
        assertEquals(1, points.size)
        assertEquals("10.000" to "50.000", xy(points[0]))
        val shown = harness.presenter.frame.value!!.shownSubDatumPoint(points[0])
        assertEquals("50.000" to "-10.000", mm(shown.x) to mm(shown.y))
    }

    @Test
    fun aLatheXInTheDiameterModeTakesADiameter() = withPattern(PatternMachines.lathe(diameterMode = true), PatternKind.HoleCircle) { presenter, harness ->
        presenter.onSourceChange(PositionSource.Custom)
        type(presenter, PatternInput.CustomX, "20")
        type(presenter, PatternInput.CustomZ, "0")
        type(presenter, PatternInput.Radius, "5")
        type(presenter, PatternInput.HoleCount, "4")
        val state = presenter.uiState.value
        assertTrue(state.positionRows.first { it.axis == AxisLetter.X }.diameter)
        // The list shows X as a diameter, as the readout.
        assertEquals("20.000", state.pointRows[0].x)
        assertEquals("5.000", state.pointRows[0].z)
        val points = create(presenter, harness)
        // The stored X is a radius.
        assertEquals("10.000", mm(points[0].x))
        assertEquals("5.000", mm(points[0].z))
    }
}
