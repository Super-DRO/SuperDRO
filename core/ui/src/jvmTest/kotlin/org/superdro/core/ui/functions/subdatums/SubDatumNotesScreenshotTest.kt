package org.superdro.core.ui.functions.subdatums

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ThemeMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.route_graphical_view
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.navigation.SidePanelPlaceholder
import org.superdro.core.ui.readout.NoReadoutEvents
import org.superdro.core.ui.readout.ReadoutSamples
import org.superdro.core.ui.readout.ReadoutScreen
import org.superdro.core.ui.readout.SidePanelUiState
import org.superdro.core.ui.theme.AppTheme

/** Renders the portrait sub-datum panel and the note editor with multi-line notes for issue 71. */
class SubDatumNotesScreenshotTest {

    private val noteShoulder =
        "Rough turn the outer diameter to 38.20 mm with a CNMG 432 carbide insert at 900 RPM and 0.20 mm/rev feed, and leave 0.10 mm radial stock for the finish pass.\n" +
            "Finish turn to 38.000 mm (+0.000 / -0.025 mm) and face the shoulder at Z = -42.500 mm from the chuck datum.\n" +
            "Break the outer corner 0.3 mm x 45 deg before the thread relief pass."

    private val noteBore =
        "Drill an 18.5 mm pilot hole to 32.0 mm depth with flood coolant, then rough bore with the CCMT bore bar (T4) to 19.85 mm diameter.\n" +
            "Finish bore to 20.000 mm H7 (+0.021 / -0.000 mm) at 30.000 mm depth and check the fit with the 20.00 mm go/no-go plug gauge.\n" +
            "Run a spring pass at zero radial depth if the taper exceeds 0.005 mm."

    private val noteRelief =
        "Cut a 2.5 mm wide thread relief groove to 35.60 mm diameter with the 2.0 mm groove blade (T6) at 450 RPM and light hand feed.\n" +
            "Blend the relief floor cleanly into the shoulder wall at Z = -42.500 mm with no dwell marks."

    private val baseRows = listOf(
        SubDatumRowUi(
            id = "1",
            name = "OD Shoulder",
            note = noteShoulder,
            coordinates = "X 38.000 • Z -42.500",
            tags = listOf(TagMarkUi("turn", 0), TagMarkUi("op10", 1)),
            active = true,
        ),
        SubDatumRowUi(
            id = "2",
            name = "Bearing Bore",
            note = noteBore,
            coordinates = "X 20.000 • Z -30.000",
            tags = listOf(TagMarkUi("bore", 2), TagMarkUi("op10", 1)),
        ),
        SubDatumRowUi(
            id = "3",
            name = "Thread Relief",
            note = noteRelief,
            coordinates = "X 35.600 • Z -40.000",
            tags = listOf(TagMarkUi("groove", 3)),
            kind = SubDatumGroupKind.Thread,
        ),
        SubDatumRowUi(
            id = "4",
            name = "Part-Off Face",
            note = "",
            coordinates = "X 0.000 • Z -65.000",
        ),
    )

    private fun panelState(expandedIds: Set<String>): SubDatumPanelUiState = SubDatumPanelUiState(
        loaded = true,
        workspaceId = "w-lathe",
        workspaceName = "Spindle Adapter Op 10",
        lathe = true,
        rows = baseRows.map { row -> row.copy(expanded = row.id in expandedIds) },
        pointCount = baseRows.size,
        expandedIds = expandedIds,
        activeId = "1",
    )

    private object NoopEvents : SubDatumPanelEvents {
        override fun onTap(id: String) = Unit
        override fun onToggleExpanded(id: String) = Unit
        override fun onLongPress(id: String) = Unit
        override fun onMenuDismiss() = Unit
        override fun onEdit(id: String) = Unit
        override fun onSelectPoint(id: String) = Unit
        override fun onDelete(id: String) = Unit
        override fun onSetOrigin(id: String) = Unit
        override fun onAlignSpindle(id: String) = Unit
        override fun onAlignOrigin(id: String) = Unit
        override fun onPair(pointA: String, pointB: String) = Unit
        override fun onWorkspaces() = Unit
        override fun onSelecting(on: Boolean) = Unit
        override fun onSelectAll() = Unit
        override fun onDeselect() = Unit
        override fun onTagSelected() = Unit
        override fun onCopy() = Unit
        override fun onFilterToggle() = Unit
        override fun onFilterLongPress() = Unit
        override fun onFilterRow(tag: String?) = Unit
        override fun onToggle3d() = Unit
        override fun onDeleteSelectedOrAll() = Unit
        override fun onDeleteAll() = Unit
        override fun onFirst() = Unit
        override fun onPrevious() = Unit
        override fun onNext() = Unit
        override fun onLast() = Unit
        override fun onImport() = Unit
        override fun onExport() = Unit
        override fun onWorkspaceCommands() = Unit
        override fun onUndoTransform() = Unit
    }

    private object NoopEditEvents : SubDatumEditEvents {
        override fun onName(text: String) = Unit
        override fun onNote(text: String) = Unit
        override fun onTags(text: String) = Unit
        override fun onText(field: SubDatumField, text: String) = Unit
        override fun onUnit(field: SubDatumField, metric: Boolean) = Unit
        override fun onSave() = Unit
        override fun onDelete() = Unit
        override fun onDismiss() = Unit
    }

    @Test
    fun renderIssue71Screenshots() {
        val collapsed = renderPanel("issue71-1-collapsed", emptySet())
        val oneExpanded = renderPanel("issue71-2-one-expanded", setOf("1"))
        val twoExpanded = renderPanel("issue71-3-two-expanded", setOf("1", "2"))
        val editor = renderEditor("issue71-4-note-editor")
        assertTrue(collapsed.length() > 0)
        assertTrue(oneExpanded.length() > 0)
        assertTrue(twoExpanded.length() > 0)
        assertTrue(editor.length() > 0)
    }

    private fun renderPanel(name: String, expandedIds: Set<String>) =
        renderPortrait(name) {
            val sample = ReadoutSamples.lathe()
            val readoutState = sample.state.copy(
                sidePanel = SidePanelUiState(mode = SidePanelMode.SubDatums, addVisible = true, toolsEnabled = true),
            )
            ReadoutScreen(
                state = readoutState,
                axisValue = sample::flow,
                events = NoReadoutEvents,
                layout = WindowLayout.of(800.dp, 1280.dp),
                sidePanelContent = { SubDatumPanel(panelState(expandedIds), NoopEvents) },
                graphicalViewPane = { SidePanelPlaceholder(Res.string.route_graphical_view) },
                rpmText = sample.rpm,
            )
        }

    private fun renderEditor(name: String) =
        renderPortrait(name) {
            val sample = ReadoutSamples.lathe()
            ReadoutScreen(
                state = sample.state,
                axisValue = sample::flow,
                events = NoReadoutEvents,
                layout = WindowLayout.of(800.dp, 1280.dp),
                graphicalViewPane = { SidePanelPlaceholder(Res.string.route_graphical_view) },
                rpmText = sample.rpm,
            )
            SubDatumEditSheet(
                state = SubDatumEditUiState(
                    loaded = true,
                    newPoint = false,
                    name = "OD Shoulder",
                    note = noteShoulder,
                    tags = "turn op10",
                    x = LengthFieldState.of(38_000.0, true),
                    z = LengthFieldState.of(-42_500.0, true),
                    lathe = true,
                    xDiameter = true,
                    showY = false,
                ),
                events = NoopEditEvents,
            )
        }

    private fun renderPortrait(name: String, content: @Composable () -> Unit) =
        ScreenshotRenderer.render(name, widthDp = 800, heightDp = 1280, frames = 30) {
            val layout = WindowLayout.of(800.dp, 1280.dp)
            AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
                CompositionLocalProvider(LocalWindowLayout provides layout) {
                    content()
                }
            }
        }
}
