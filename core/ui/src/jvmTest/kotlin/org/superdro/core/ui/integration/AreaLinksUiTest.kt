package org.superdro.core.ui.integration

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.probeOptionTag
import org.superdro.core.ui.adapter.probe.probeSelectorTag
import org.superdro.core.ui.functions.tools.ToolEditTags
import org.superdro.core.ui.graphics.probe.Z_AUTO_BUTTON_TAG
import org.superdro.core.ui.graphics.probe.Z_AUTO_HEIGHT_TAG
import org.superdro.core.ui.graphics.probe.storeProbe
import org.superdro.core.ui.navigation.ProbeProfileRoute
import org.superdro.core.ui.navigation.ToolEditRoute
import org.superdro.core.ui.navigation.ToolGeometryRoute
import org.superdro.core.ui.navigation.ZReferenceRoute

/** The UI tests of the links between the areas of `core:ui`, with the real entries. */
@OptIn(ExperimentalTestApi::class)
class AreaLinksUiTest {

    @Test
    fun theZReferenceSheetHasTheAutomaticModeOfTheGraphicsArea() = runComposeUiTest {
        val ui = IntegrationHarness(TestScope())
        ui.read {
            val base = MachineProfile.create(MachineType.VerticalMill).copy(metricDefault = true)
            ui.start(base.copy(probe = ProbeConfig(enabled = true, toolSetterEnabled = true)))
            val setter = ui.graphics.storeProbe(ProbeProfile(name = "Setter 25", type = ProbeType.ToolHeightSetter, port = 'p', zOffsetUm = 25_000.0))
            ui.graphics.updateProfile { profile -> profile.copy(probeRoles = profile.probeRoles.copy(zReferenceProfileId = setter.id)) }
        }
        setContent { ui.Entry(ZReferenceRoute) }
        ui.settle(this)
        // The `automatic` slot of the sheet of the functions area has the section of the graphics area.
        onNodeWithTag(Z_AUTO_HEIGHT_TAG).performScrollTo().assertTextEquals("Setter height: 25.000 mm")
        onNodeWithTag(probeSelectorTag(ProbeFunction.ZReference)).performScrollTo()
        // "New profile…" opens the probe profile screen.
        onNodeWithTag(probeSelectorTag(ProbeFunction.ZReference)).performClick()
        onNodeWithTag(probeOptionTag(ProbeFunction.ZReference, null)).performClick()
        assertEquals(listOf<Any>(ProbeProfileRoute(null)), ui.global.pushed)
        // "Auto" arms the probe. A probe closure sets Z and closes the sheet.
        onNodeWithTag(Z_AUTO_BUTTON_TAG).performScrollTo().performClick()
        ui.settle(this)
        ui.graphics.moveTo(1_000L, x = 0L, y = 0L, z = -10_000L)
        ui.graphics.session.probe(active = true, timeMs = 1_000L)
        ui.graphics.session.tick(1_600L)
        ui.settle(this)
        assertEquals(listOf<Any>(ZReferenceRoute), ui.navigator.popped)
        assertEquals(25_000.0, ui.graphics.readout.frame.value!!.reading(AxisId.Z).absolute, 1e-6)
        ui.close()
    }

    @Test
    fun theLatheToolEditSheetOpensTheCutterGeometryScreen() = runComposeUiTest {
        val ui = IntegrationHarness(TestScope())
        val tool = ui.read {
            ui.start(MachineProfile.create(MachineType.Lathe).copy(metricDefault = true))
            ui.graphics.store.tools.addTool(ui.graphics.profile.id, Tool(kind = ToolKind.Lathe, name = "OD turning", metric = true)).valueOrNull()!!
        }
        setContent { ui.Entry(ToolEditRoute(tool.id)) }
        ui.settle(this)
        onNodeWithTag(ToolEditTags.GEOMETRY_STATE).performScrollTo().assertTextEquals("Not defined")
        onNodeWithTag(ToolEditTags.GEOMETRY).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<Any>(ToolGeometryRoute(tool.id)), ui.navigator.pushed)
        ui.close()
    }

    @Test
    fun aNewLatheToolMustBeSavedBeforeItsCutterGeometry() = runComposeUiTest {
        val ui = IntegrationHarness(TestScope())
        ui.read { ui.start(MachineProfile.create(MachineType.Lathe).copy(metricDefault = true)) }
        setContent { ui.Entry(ToolEditRoute(null)) }
        ui.settle(this)
        onNodeWithTag(ToolEditTags.GEOMETRY).performScrollTo().assertIsNotEnabled()
        onNodeWithTag(ToolEditTags.GEOMETRY_STATE).assertTextEquals("Save the tool first. Then add the cutter geometry.")
        assertTrue(ui.navigator.pushed.isEmpty())
        ui.close()
    }
}
