package org.superdro.core.ui.functions.patterns

import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.functions.arc.AngleReference
import org.superdro.core.functions.arc.CutType
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.ui.functions.renderFunctionScreenshot

/**
 * The screenshots of the pattern screens in light and dark mode. The
 * files go to `core/ui/build/screenshots/functions-patterns-*.png`.
 */
class PatternScreenshotTest {

    private object NoEvents : PatternEvents {
        override fun onPrefixChange(text: String) = Unit
        override fun onPlaneChange(plane: Plane) = Unit
        override fun onSourceChange(source: PositionSource) = Unit
        override fun onAxisSwitch(axis: AxisLetter, on: Boolean) = Unit
        override fun onReferenceChange(reference: PositionReference) = Unit
        override fun onSubDatumSelected(id: String?) = Unit
        override fun onText(input: PatternInput, text: String) = Unit
        override fun onUnit(input: PatternInput, metric: Boolean) = Unit
        override fun onAddAngle() = Unit
        override fun onRemoveAngle(index: Int) = Unit
        override fun onCutType(cutType: CutType) = Unit
        override fun onAngleReference(reference: AngleReference) = Unit
        override fun onCreate() = Unit
        override fun onBlockedDismissed() = Unit
        override fun onBack() = Unit
    }

    private fun render(
        name: String,
        machine: MachineProfile,
        kind: PatternKind,
        sourceIds: List<String> = emptyList(),
        portrait: Boolean = false,
        setup: LivePattern.() -> Unit,
    ) {
        LivePattern(machine, kind, sourceIds, inputs = mapOf(AxisId.X to 25_000.0, AxisId.Y to 10_000.0, AxisId.Z to -2_000.0)).use { live ->
            live.setup()
            val state = live.presenter.uiState.value
            listOf(false, true).forEach { dark ->
                val mode = if (dark) "dark" else "light"
                val file = renderFunctionScreenshot("functions-patterns-$name-$mode", dark) { PatternScreen(state, NoEvents) }
                assertTrue(file.exists())
                if (portrait) {
                    renderFunctionScreenshot("functions-patterns-$name-portrait-$mode", dark, widthDp = 800, heightDp = 1280) {
                        PatternScreen(state, NoEvents)
                    }
                }
            }
        }
    }

    private fun LivePattern.type(input: PatternInput, text: String) = act { onText(input, text) }

    @Test
    fun theHoleCircle() = render("hole-circle", PatternMachines.standardMill(), PatternKind.HoleCircle, portrait = true) {
        act { onSourceChange(PositionSource.Custom) }
        type(PatternInput.CustomX, "100")
        type(PatternInput.CustomY, "50")
        type(PatternInput.Radius, "50")
        type(PatternInput.HoleCount, "6")
    }

    @Test
    fun theHoleCircleWithErrors() = render("hole-circle-errors", PatternMachines.legacyMill(), PatternKind.HoleCircle) {
        act { onPrefixChange("ab") }
        act { onCreate() }
    }

    @Test
    fun theLatheHoleCircle() = render("hole-circle-lathe", PatternMachines.lathe(), PatternKind.HoleCircle) {
        act { onSourceChange(PositionSource.Zero) }
        type(PatternInput.Radius, "10")
        type(PatternInput.HoleCount, "8")
    }

    @Test
    fun theArbitraryHoleCircle() = render("arbitrary-hole-circle", PatternMachines.legacyMill(), PatternKind.ArbitraryHoleCircle) {
        act { onSourceChange(PositionSource.Zero) }
        type(PatternInput.Radius, "25.4")
        listOf("10", "100", "200").forEach { angle ->
            type(PatternInput.AngleEntry, angle)
            act { onAddAngle() }
        }
    }

    @Test
    fun theHoleGrid() = render("hole-grid", PatternMachines.legacyMill(), PatternKind.HoleGrid) {
        act { onSourceChange(PositionSource.Zero) }
        type(PatternInput.Repeat1, "3")
        type(PatternInput.Step1, "10")
        type(PatternInput.Repeat2, "2")
        type(PatternInput.Step2, "20")
        type(PatternInput.GridAngle, "30")
    }

    @Test
    fun theStepAndRepeat() = render(
        "step-and-repeat",
        PatternMachines.legacyMill {
            it.copy(
                subDatums = listOf(
                    SubDatum(id = "a", name = "Point 1", x = 10_000.0, y = 0.0, z = -5_000.0),
                    SubDatum(id = "b", name = "Point 2", x = 0.0, y = 10_000.0, z = -5_000.0),
                ),
            )
        },
        PatternKind.StepAndRepeat,
        sourceIds = listOf("a", "b"),
    ) {
        act { onSourceChange(PositionSource.Zero) }
        type(PatternInput.Repeat1, "3")
        type(PatternInput.Step1, "30")
    }

    @Test
    fun theEquidistantArray() = render("equidistant-array", PatternMachines.legacyMill(use3d = false), PatternKind.EquidistantArray) {
        type(PatternInput.Count, "5")
        type(PatternInput.EndX, "100")
        type(PatternInput.EndY, "50")
    }

    @Test
    fun theArcCut() = render("arc-cut", PatternMachines.legacyMill(), PatternKind.ArcCut) {
        act { onSourceChange(PositionSource.Zero) }
        type(PatternInput.Radius, "20")
        type(PatternInput.CutterDiameter, "6")
        type(PatternInput.Stepover, "2")
        type(PatternInput.StartAngle, "0")
        act { onAngleReference(AngleReference.Tangent) }
    }
}
