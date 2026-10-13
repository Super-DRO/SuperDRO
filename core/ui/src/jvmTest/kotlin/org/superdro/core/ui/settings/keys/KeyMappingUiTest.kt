package org.superdro.core.ui.settings.keys

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.KeyMappingRoute
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.NavigationHost
import org.superdro.core.ui.readout.ReadoutHarness
import org.superdro.core.ui.readout.ReadoutScreen
import org.superdro.core.ui.settings.SettingsThemed
import org.superdro.core.ui.settings.settingsEntries
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/**
 * The key mapping entry in the navigation host with a DI graph, the capture of a key with Compose
 * key events, and a mapped key on the readout root.
 */
@OptIn(ExperimentalTestApi::class)
class KeyMappingUiTest {

    private class Graph(val scope: TestScope) {
        val store = UiStoreFixture(scope)
        val navigation = NavigationHolder().apply { push(KeyMappingRoute) }
        private val context = scope.testPresenterContext()
        val di = DI { bindProvider { KeyMappingPresenter(store.appSettings, context) } }
        val registry = EntryRegistry().apply { settingsEntries() }

        fun run() = scope.testScheduler.advanceUntilIdle()
    }

    private fun ComposeUiTest.settle(run: () -> Unit) {
        repeat(3) {
            waitForIdle()
            run()
        }
        waitForIdle()
    }

    private fun ComposeUiTest.show(graph: Graph) {
        setContent {
            ProvideDI(graph.di) {
                SettingsThemed(dark = false) {
                    NavigationHost(
                        holder = graph.navigation,
                        registry = graph.registry,
                        tabIds = emptyList(),
                        selected = null,
                        readoutFactory = { error("No tab in this test.") },
                        showMessage = {},
                    )
                }
            }
        }
    }

    @Test
    fun theUserAddsAKeyWithTheCaptureOfTheNextKeyDown() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        settle(graph::run)
        onNodeWithText("Num 1").assertIsDisplayed()
        onNodeWithText("The default key map is in use.").assertIsDisplayed()
        onNodeWithTag(KEY_ADD_TAG).performScrollTo().performClick()
        settle(graph::run)
        onNodeWithTag(KEY_NEXT_TAG).assertIsDisplayed()
        onNodeWithTag(KEY_NEXT_TAG).performClick()
        settle(graph::run)
        onNodeWithText("Press a key").assertIsDisplayed()
        onNodeWithTag(KEY_CAPTURE_TAG).requestFocus()
        onNodeWithTag(KEY_CAPTURE_TAG).performKeyInput { pressKey(Key.F5) }
        settle(graph::run)
        val settings = graph.store.appSettings.settings.value
        assertTrue(settings.keyMapCustomized)
        assertEquals(KeyMapping(Key.F5.keyCode, action = KeyAction.ZeroAxis, axis = AxisId.X), settings.keyMappings.last())
        onNodeWithText("F5").performScrollTo().assertIsDisplayed()
        onNodeWithText("Your own key map is in use.").performScrollTo().assertIsDisplayed()
        graph.store.close()
    }

    @Test
    fun escapeCancelsTheCapture() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        settle(graph::run)
        onNodeWithTag(KEY_ADD_TAG).performScrollTo().performClick()
        settle(graph::run)
        onNodeWithTag(KEY_NEXT_TAG).performClick()
        settle(graph::run)
        onNodeWithTag(KEY_CAPTURE_TAG).requestFocus()
        onNodeWithTag(KEY_CAPTURE_TAG).performKeyInput { pressKey(Key.Escape) }
        settle(graph::run)
        onNodeWithTag(KEY_CAPTURE_TAG).assertDoesNotExist()
        assertEquals(false, graph.store.appSettings.settings.value.keyMapCustomized)
        graph.store.close()
    }

    @Test
    fun aMappedKeyOnTheRootRunsItsActionAndAnUnknownKeyDoesNot() = runComposeUiTest {
        val target = RecordingTarget()
        setContent {
            Box(Modifier.fillMaxSize().testTag("root").then(hardwareKeysModifier(KeyMaps.default(), target)))
        }
        waitForIdle()
        onNodeWithTag("root").requestFocus()
        onNodeWithTag("root").performKeyInput {
            pressKey(Key.NumPad3)
            pressKey(Key.A)
            pressKey(Key.NumPadDivide)
        }
        waitForIdle()
        assertEquals(listOf<Pair<KeyAction, AxisId?>>(KeyAction.ZeroAxis to AxisId.Z, KeyAction.ToggleAbsoluteIncremental to null), target.runs)
    }

    @Test
    fun numOneOnTheReadoutScreenZerosX() = runComposeUiTest {
        val scope = TestScope()
        val harness = ReadoutHarness(scope)
        scope.launch { harness.start(states = mapOf(AxisId.X to AxisState(metric = true))) }
        scope.testScheduler.advanceUntilIdle()
        harness.inputs(AxisId.X to 25_000.0)
        val actions = ReadoutKeyActions(harness.presenter, RecordingNavigator())
        setContent {
            SettingsThemed(dark = false) {
                val state by harness.presenter.uiState.collectAsState()
                ReadoutScreen(
                    state = state,
                    axisValue = harness.presenter::axisValue,
                    events = org.superdro.core.ui.readout.NoReadoutEvents,
                    modifier = hardwareKeysModifier(KeyMaps.default(), actions).testTag("readout-root"),
                )
            }
        }
        settle { scope.testScheduler.advanceUntilIdle() }
        assertEquals("25.000", harness.text(AxisId.X))
        onNodeWithTag("readout-root").requestFocus()
        onNodeWithTag("readout-root").performKeyInput { pressKey(Key.NumPad1) }
        settle { scope.testScheduler.advanceUntilIdle() }
        assertEquals("0.000", harness.text(AxisId.X))
        harness.close()
    }
}
