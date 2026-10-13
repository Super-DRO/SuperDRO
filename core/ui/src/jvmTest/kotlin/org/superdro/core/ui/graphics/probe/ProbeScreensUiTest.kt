package org.superdro.core.ui.graphics.probe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.functions.probe.EdgeSide
import org.superdro.core.functions.probe.FeatureType
import org.superdro.core.functions.probe.OriginOption
import org.superdro.core.model.AxisId
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.probeOptionTag
import org.superdro.core.ui.adapter.probe.probeSelectorTag
import androidx.compose.ui.test.onNodeWithContentDescription

/** The Compose UI tests of the edge finder screen and of the automatic Z reference. */
@OptIn(ExperimentalTestApi::class)
class ProbeScreensUiTest {

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AppTheme(mode = ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp), content = content)
        }
    }

    @Test
    fun theEdgeButtonsAndTheActionsGiveTheirEvents() = runComposeUiTest {
        val events = ProbeSamples.RecordingEvents()
        setContent { Themed { EdgeFinderScreen(ProbeSamples.edgeFinder(), events) } }
        onNodeWithTag(edgeTag(EdgeSide.Far)).performClick()
        onNodeWithTag(edgeTag(EdgeSide.Left)).performTouchInput { longClick() }
        onNodeWithTag(EDGE_FINDER_AUTO_TAG).performClick()
        onNodeWithTag(EDGE_FINDER_ADD_TAG).performScrollTo().performTouchInput { longClick() }
        onNodeWithTag(EDGE_FINDER_SET_ORIGIN_TAG).performScrollTo().performClick()
        onNodeWithTag(EDGE_FINDER_CANCEL_TAG).performScrollTo().performClick()
        assertEquals(listOf("tap Far", "long Left", "auto", "add true", "set origin", "cancel"), events.calls)
        onNodeWithTag(EDGE_FINDER_ORIGIN_TAG).performScrollTo().assertTextEquals("x: -11.004 mm y: 2.250 mm")
        onNodeWithText("Warning: maximum spread between the samples is 0.030 mm").assertExists()
    }

    @Test
    fun theOptionsWithNoEdgeAndTheFeatureWhileAutoRunsAreDisabled() = runComposeUiTest {
        val events = ProbeSamples.RecordingEvents()
        setContent { Themed { EdgeFinderScreen(ProbeSamples.edgeFinder(), events) } }
        onNodeWithTag(optionTag(AxisId.X, OriginOption.Center)).performScrollTo().assertIsNotEnabled()
        onNodeWithTag(featureTag(AxisId.X, FeatureType.Internal)).performScrollTo().assertIsNotEnabled()
        onNodeWithTag(optionTag(AxisId.Y, OriginOption.SideA)).performScrollTo().performClick()
        assertEquals(listOf("option Y SideA"), events.calls)
    }

    @Test
    fun theEdgeButtonsHaveTheShopFloorTouchTarget() = runComposeUiTest {
        setContent { Themed { EdgeFinderScreen(ProbeSamples.edgeFinder(), ProbeSamples.RecordingEvents()) } }
        EdgeSide.entries.forEach { side ->
            onNodeWithTag(edgeTag(side)).assertWidthIsAtLeast(64.dp).assertHeightIsAtLeast(64.dp)
        }
    }

    @Test
    fun theAutoButtonOfTheZReferenceTogglesAndShowsItsState() = runComposeUiTest {
        var toggles = 0
        setContent { Themed { ZReferenceAutoSection(ProbeSamples.zAuto(armed = true), onToggle = { toggles++ }, onSelectProbe = {}, onNewProbe = {}) } }
        onNodeWithText("Armed: touch the setter with the tool").assertExists()
        onNodeWithTag(Z_AUTO_HEIGHT_TAG).assertTextEquals("Setter height: 25.000 mm")
        onNodeWithTag(Z_AUTO_BUTTON_TAG).performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun theProbeSelectorOffersNewProfile() = runComposeUiTest {
        val events = ProbeSamples.RecordingEvents()
        setContent { Themed { EdgeFinderScreen(ProbeSamples.edgeFinder(), events) } }
        // The probe selector of the adapter area: select, "New profile…", and edit.
        onNodeWithTag(probeSelectorTag(ProbeFunction.EdgeFinder)).performClick()
        onNodeWithTag(probeOptionTag(ProbeFunction.EdgeFinder, "edge")).performClick()
        onNodeWithTag(probeSelectorTag(ProbeFunction.EdgeFinder)).performClick()
        onNodeWithTag(probeOptionTag(ProbeFunction.EdgeFinder, null)).performClick()
        onNodeWithContentDescription("Edit the probe profile").performClick()
        assertEquals(listOf("select edge", "new probe", "edit touch"), events.calls)
    }
}
