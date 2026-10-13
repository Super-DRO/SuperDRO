package org.superdro.core.ui.functions.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.tools_error_diameter
import org.superdro.core.resources.tools_error_name_blank
import org.superdro.core.resources.tools_error_name_too_long
import org.superdro.core.resources.tools_error_tooth_count
import org.superdro.core.resources.tools_error_z_offset_required
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.text.UiText

/** The tool rules, the row texts, and the activation of a tool. */
class ToolRulesTest {

    private fun mill(
        name: String = "6 mm Drill",
        diameter: Double? = 6_000.0,
        teeth: Double? = 2.0,
        z: Double? = -12_500.0,
    ) = ToolInput(ToolKind.Mill, name, "", metric = true, diameterUm = diameter, toothCount = teeth, zOffsetUm = z)

    @Test
    fun aMillToolNeedsANameADiameterThatIsNotZeroAWholeToothCountAndAZOffset() {
        assertIs<ToolCheck.Valid>(ToolRules.check(mill()))
        val errors = (ToolRules.check(mill(name = " ", diameter = 0.0, teeth = 2.5, z = null)) as ToolCheck.Invalid).errors
        assertEquals(UiText.of(Res.string.tools_error_name_blank), errors[ToolField.Name])
        assertEquals(UiText.of(Res.string.tools_error_diameter), errors[ToolField.Diameter])
        assertEquals(UiText.of(Res.string.tools_error_tooth_count), errors[ToolField.ToothCount])
        assertEquals(UiText.of(Res.string.tools_error_z_offset_required), errors[ToolField.ZOffset])
        assertEquals(
            UiText.of(Res.string.tools_error_name_too_long, 50),
            (ToolRules.check(mill(name = "x".repeat(51))) as ToolCheck.Invalid).errors[ToolField.Name],
        )
        assertIs<ToolCheck.Valid>(ToolRules.check(mill(name = "x".repeat(50))))
        val longDescription = mill().copy(description = "d".repeat(101))
        assertTrue(ToolField.Description in (ToolRules.check(longDescription) as ToolCheck.Invalid).errors)
        assertEquals(0, ((ToolRules.check(mill(teeth = 0.0)) as ToolCheck.Valid).tool.toothCount))
        assertIs<ToolCheck.Invalid>(ToolRules.check(mill(teeth = -1.0)))
    }

    @Test
    fun aLatheToolHasBlankOffsetsOfZeroAndStoresBoring() {
        val input = ToolInput(ToolKind.Lathe, "Boring Bar", "3/8 bar", metric = false, xOffsetUm = null, zOffsetUm = 9_525.0, internal = true)
        val tool = (ToolRules.check(input) as ToolCheck.Valid).tool
        assertEquals(0.0, tool.xOffsetUm)
        assertEquals(9_525.0, tool.zOffsetUm)
        assertTrue(tool.internal)
        assertEquals(1, tool.toothCount)
        assertEquals(ToolKind.Lathe, tool.kind)
    }

    @Test
    fun aHorizontalMillUsesTheMillTools() {
        assertEquals(ToolKind.Mill, ToolRules.kindOf(MachineType.HorizontalMill))
        assertEquals(ToolKind.Mill, ToolRules.kindOf(MachineType.VerticalMill))
        assertEquals(ToolKind.Lathe, ToolRules.kindOf(MachineType.Lathe))
    }

    @Test
    fun theSyncValueIsTheNegativePositionWithoutTheActiveToolOffset() {
        // The display shows 1.200 mm with an active X tool offset of 0.200 mm: the field gets −1.000 mm.
        assertEquals(-1_000.0, ToolRules.syncValue(1_200.0, 200.0))
        assertEquals(0.0, ToolRules.syncValue(300.0, 300.0))
    }

    @Test
    fun theRowTexts() = runTest {
        val mill = Tool(kind = ToolKind.Mill, name = "End Mill", metric = true, diameterUm = 10_000.0, toothCount = 4, zOffsetUm = -12_500.0)
        assertEquals("⌀ 10.000 mm • 4 flutes • -12.500 mm Δz", ToolTexts.row(mill, profileMetric = false).loadString(TestEnvironment))
        assertEquals("⌀ 10.000 mm • 1 flute • -12.500 mm Δz", ToolTexts.row(mill.copy(toothCount = 1), false).loadString(TestEnvironment))
        val boring = Tool(kind = ToolKind.Lathe, name = "Boring Bar", xOffsetUm = -3_175.0, zOffsetUm = 9_525.0, internal = true)
        assertEquals("-0.1250 in Δx • 0.3750 in Δz • ID", ToolTexts.row(boring, profileMetric = false).loadString(TestEnvironment))
        assertEquals("0.0000 in Δx • 0.0000 in Δz • OD/Facing", ToolTexts.row(boring.copy(xOffsetUm = 0.0, zOffsetUm = 0.0, internal = false), false).loadString(TestEnvironment))
    }

    @Test
    fun aTapActivatesALatheToolAndASecondTapClearsIt() {
        val tool = Tool(id = "t1", kind = ToolKind.Lathe, xOffsetUm = 500.0, zOffsetUm = -250.0)
        val active = ToolActivation.toggle(Workspace(), tool, MachineType.Lathe)
        assertEquals("t1", active.activeToolId)
        assertEquals(AxisState(toolOffset = 500.0, toolOffsetActive = true), active.axisStates.getValue(AxisId.X))
        assertEquals(AxisState(toolOffset = -250.0, toolOffsetActive = true), active.axisStates.getValue(AxisId.Z))
        val cleared = ToolActivation.toggle(active, tool, MachineType.Lathe)
        assertEquals(null, cleared.activeToolId)
        assertEquals(false, cleared.axisStates.getValue(AxisId.X).toolOffsetActive)
        assertEquals(false, cleared.axisStates.getValue(AxisId.Z).toolOffsetActive)
    }

    @Test
    fun aMillToolSetsTheZOffsetAndClearsTheXAndYOffsets() {
        val start = Workspace().let { base ->
            base.copy(axisStates = base.axisStates + (AxisId.X to AxisState(toolOffset = 5_000.0, toolOffsetActive = true)))
        }
        val tool = Tool(id = "m1", kind = ToolKind.Mill, diameterUm = 10_000.0, toothCount = 4, zOffsetUm = -12_500.0)
        val active = ToolActivation.toggle(start, tool, MachineType.VerticalMill)
        assertEquals("m1", active.activeToolId)
        assertEquals(false, active.axisStates.getValue(AxisId.X).toolOffsetActive)
        assertEquals(-12_500.0, active.axisStates.getValue(AxisId.Z).toolOffset)
        assertEquals(10_000.0, active.toolXUm)
        assertEquals(4, active.toothCount)
        val cleared = ToolActivation.toggle(active, tool, MachineType.VerticalMill)
        assertEquals(null, cleared.activeToolId)
        assertEquals(false, cleared.axisStates.getValue(AxisId.Z).toolOffsetActive)
        assertEquals(0.0, cleared.toolXUm)
    }

    @Test
    fun theSortOrders() {
        val a = Tool(name = "b", createdOn = kotlin.time.Instant.fromEpochSeconds(1))
        val b = Tool(name = "A", createdOn = kotlin.time.Instant.fromEpochSeconds(2))
        assertEquals(listOf("A", "b"), listOf(a, b).sortedFor(ToolSort.NameAscending).map { it.name })
        assertEquals(listOf("b", "A"), listOf(a, b).sortedFor(ToolSort.NameDescending).map { it.name })
        assertEquals(listOf("A", "b"), listOf(a, b).sortedFor(ToolSort.Newest).map { it.name })
        assertEquals(listOf("b", "A"), listOf(a, b).sortedFor(ToolSort.Oldest).map { it.name })
    }
}
