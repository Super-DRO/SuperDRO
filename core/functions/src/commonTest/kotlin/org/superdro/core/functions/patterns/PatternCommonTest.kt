package org.superdro.core.functions.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.Workspace

/** The common pattern inputs. */
class PatternCommonTest {

    private val origin = AxisPoint(1000.0, 2000.0, 3000.0)
    private val point = PointUm(10.0, 20.0)

    @Test
    fun thePlaneMapAddsTheOriginToThePlaneAxes() {
        assertEquals(AxisPoint(1010.0, 2020.0, 3000.0), PatternPlanes.map(Plane.XY, origin, point))
        assertEquals(AxisPoint(1010.0, 2000.0, 3020.0), PatternPlanes.map(Plane.XZ, origin, point))
        assertEquals(AxisPoint(1000.0, 2010.0, 3020.0), PatternPlanes.map(Plane.YZ, origin, point))
        assertEquals(AxisPoint(1010.0, 2000.0, 3020.0), PatternPlanes.map(Plane.ZX, origin, point))
    }

    @Test
    fun aNullOriginCoordinateGivesNoCoordinate() {
        assertEquals(AxisPoint(1010.0, 2020.0, null), PatternPlanes.map(Plane.XY, AxisPoint(1000.0, 2000.0, null), point))
    }

    @Test
    fun aLatheAlwaysUsesTheZxPlaneAndAMillDefaultsToXy() {
        assertEquals(Plane.ZX, PatternPlanes.effectivePlane(MachineType.Lathe, Plane.XY))
        assertEquals(Plane.XY, PatternPlanes.effectivePlane(MachineType.VerticalMill))
        assertEquals(Plane.YZ, PatternPlanes.effectivePlane(MachineType.HorizontalMill, Plane.YZ))
        assertEquals(Plane.XY, PatternPlanes.effectivePlane(MachineType.VerticalMill, Plane.ZX))
    }

    @Test
    fun theGroupNameGetsThePlaneSuffixOutOfTheDefaultPlane() {
        assertEquals("", PatternPlanes.groupSuffix(MachineType.VerticalMill, Plane.XY))
        assertEquals(" (XZ)", PatternPlanes.groupSuffix(MachineType.VerticalMill, Plane.XZ))
        assertEquals(" (YZ)", PatternPlanes.groupSuffix(MachineType.VerticalMill, Plane.YZ))
        assertEquals(" (ZX)", PatternPlanes.groupSuffix(MachineType.Lathe, Plane.ZX))
    }

    @Test
    fun thePositionSourcesGiveTheOrigin() {
        val switches = AxisSwitches(x = true, y = true, z = false)
        val current = AxisPoint(5.0, 6.0, 7.0)
        val custom = AxisPoint(8.0, 9.0, 10.0)
        assertEquals(AxisPoint(5.0, 6.0, null), PositionSource.Current.origin(switches, current, custom))
        assertEquals(AxisPoint(0.0, 0.0, null), PositionSource.Zero.origin(switches, current, custom))
        assertEquals(AxisPoint(8.0, 9.0, null), PositionSource.Custom.origin(switches, current, custom))
        assertEquals(AxisPoint(null, 6.0, null), PositionSource.Current.origin(switches, AxisPoint(null, 6.0, 7.0)))
    }

    @Test
    fun thePlaneSwitchesFollowThePlaneAndThe3dSwitch() {
        assertEquals(AxisSwitches(x = true, y = false, z = true), PatternPlanes.defaultSwitches(Plane.ZX, true))
        assertEquals(AxisSwitches(x = true, y = true, z = true), PatternPlanes.defaultSwitches(Plane.XY, true))
        assertEquals(AxisSwitches(x = true, y = true, z = false), PatternPlanes.defaultSwitches(Plane.XY, false))
        assertEquals(AxisSwitches(x = true, y = false, z = true), PatternPlanes.defaultSwitches(Plane.XZ, false))
        assertEquals(AxisSwitches(x = false, y = true, z = true), PatternPlanes.defaultSwitches(Plane.YZ, false))
    }

    @Test
    fun theFirstPointReferenceSubtractsTheFirstPoint() {
        val points = listOf(PointUm(5.0, 10.0), PointUm(7.0, 3.0))
        assertEquals(points, points.withReference(PositionReference.Center))
        assertEquals(listOf(PointUm(0.0, 0.0), PointUm(2.0, -7.0)), points.withReference(PositionReference.FirstPoint))
    }

    @Test
    fun thePrefixHas3To16CharactersAndAnEmptyPrefixTakesTheDefault() {
        assertEquals(PrefixResult.Valid("Circle"), PatternPrefix.resolve("", "Circle"))
        assertEquals(PrefixResult.Valid("Grid"), PatternPrefix.resolve("   ", "Grid"))
        assertEquals(PrefixResult.Valid("Arc"), PatternPrefix.resolve("", "Arc"))
        assertEquals(PrefixResult.Valid("abc"), PatternPrefix.resolve("abc", "Arc"))
        assertEquals(PrefixResult.Valid("abcdefghijklmnop"), PatternPrefix.resolve("abcdefghijklmnop", "Arc"))
        val short = assertIs<PrefixResult.Invalid>(PatternPrefix.resolve("ab", "Arc"))
        assertEquals("Sub-Datum Prefix must be at least 3 characters long.", short.error.text)
        val long = assertIs<PrefixResult.Invalid>(PatternPrefix.resolve("abcdefghijklmnopq", "Arc"))
        assertEquals("Sub-Datum Prefix can't be longer than 16 characters.", long.error.text)
    }

    @Test
    fun theErrorSummaryHasTheHeaderAndOneLineForEachError() {
        val errors = listOf(
            PatternError(PatternField.CircleRadius, PatternTexts.VALUE_REQUIRED.format(PatternField.CircleRadius.label.message())),
            PatternError(PatternField.HoleCount, PatternTexts.VALUE_TOO_LARGE.format(PatternField.HoleCount.label.message())),
        )
        assertEquals(
            "This operation could not be completed. Please fix the errors below:\n" +
                "\t• Circle Radius value is required\n\t• Hole Count value is too large",
            PatternError.summary(errors),
        )
    }

    @Test
    fun theNamesUseTheNextNumbersAndTheWorkspaceKeepsTheLastNumber() {
        val workspace = Workspace(lastOrdinal = 4)
        val group = PatternGroup("Circle", listOf(AxisPoint(1.0, 2.0), AxisPoint(3.0, 4.0), AxisPoint(5.0, 6.0)))
        val batch = PatternBatch.create(workspace, group, groupId = 77L)
        assertEquals(listOf("Circle - 5", "Circle - 6", "Circle - 7"), batch.subDatums.map { subDatum -> subDatum.name })
        assertEquals(7, batch.lastOrdinal)
        assertEquals(setOf(77L), batch.subDatums.map { subDatum -> subDatum.groupId }.toSet())
        assertEquals(setOf(SubDatumGroupKind.Pattern), batch.subDatums.map { subDatum -> subDatum.groupKind }.toSet())
        // SuperDRO stores 7 here, so the next group starts at 8.
        val next = PatternBatch.create(PatternBatch.addTo(workspace, group, 77L), group, 78L)
        assertEquals("Circle - 8", next.subDatums.first().name)
    }

    @Test
    fun aWorkspaceWithNoOrdinalCountsItsSubDatums() {
        val workspace = Workspace(subDatums = listOf(SubDatum(name = "A"), SubDatum(name = "B")))
        assertEquals("Point 3" to 3, SubDatumNames.nextPointName(workspace))
        assertEquals("Point 1" to 1, SubDatumNames.nextPointName(Workspace()))
        assertEquals("Point 10" to 10, SubDatumNames.nextPointName(Workspace(lastOrdinal = 9)))
    }

    @Test
    fun theGroupTagIsTheNormalizedNameAndAUsedTagGetsANumber() {
        assertEquals("Circle_(ZX)", PatternTags.normalize("  Circle  (ZX) "))
        assertEquals(null, PatternTags.normalize("   "))
        val used = Workspace(
            subDatums = listOf(SubDatum(tags = listOf("grid")), SubDatum(tags = listOf("Grid_2"))),
        )
        assertEquals("Grid_3", PatternTags.groupTag("Grid", used))
        assertEquals("Circle", PatternTags.groupTag("Circle", used))
        assertEquals("Group", PatternTags.groupTag(" ", Workspace()))
        val batch = PatternBatch.create(used, PatternGroup("Grid", listOf(AxisPoint(0.0, 0.0))), 1L)
        assertEquals(listOf("Grid_3"), batch.subDatums.single().tags)
    }

    @Test
    fun aPointKeepsTheFullValueOfEachField() {
        val radius = 12345.6789012
        val result = HoleCircle.create(
            PatternCommon(origin = AxisPoint(0.0, 0.0), convention = PatternAngleConvention.Legacy),
            HoleCircleInput(radiusUm = radius, holeCount = 4),
        )
        val group = assertIs<PatternResult.Success>(result).group
        assertEquals(radius, group.points.first().y)
    }
}
