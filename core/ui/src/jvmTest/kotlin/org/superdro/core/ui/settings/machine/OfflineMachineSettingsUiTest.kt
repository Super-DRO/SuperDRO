package org.superdro.core.ui.settings.machine

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.settings.SettingsThemed
import org.superdro.core.ui.settings.components.settingsGroupTag
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture

/** The machine settings screen of a stored profile with no tab (GitHub issue #6). */
@OptIn(ExperimentalTestApi::class)
class OfflineMachineSettingsUiTest {

    private class Screen(val scope: TestScope, withAdapter: Boolean) {
        val store = UiStoreFixture(scope)
        val opened = mutableListOf<String>()
        val profile: MachineProfile
        val presenter: MachineSettingsPresenter

        init {
            var stored: MachineProfile? = null
            scope.launch {
                val adapterId = if (withAdapter) store.adapters.saveAdapterProfile(AdapterProfile(alias = "Adapter")).valueOrNull()!!.id else null
                stored = store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill, "Imported").copy(adapterProfileId = adapterId)).valueOrNull()
            }
            scope.testScheduler.advanceUntilIdle()
            profile = stored!!
            presenter = MachineSettingsPresenter(
                profile.id, store.machines, store.adapters, FakeConnections(), MutableStateFlow(AppSettings()), scope.testPresenterContext(),
                tab = false,
            )
            run()
        }

        val navigation = object : MachineSettingsNavigation {
            override fun openEncoder(input: Char) {
                opened += "encoder $input"
            }

            override fun openProbeInput() {
                opened += "probe input"
            }

            override fun openProfileFile() {
                opened += "profile file"
            }
        }

        fun run() = scope.testScheduler.advanceUntilIdle()

        fun stored(): MachineProfile {
            var value: MachineProfile? = null
            scope.launch { value = store.machines.loadProfile(profile.id) }
            run()
            return value!!
        }

        fun close() {
            presenter.close()
            store.close()
        }
    }

    private fun ComposeUiTest.show(screen: Screen) {
        setContent {
            SettingsThemed(dark = false) {
                val state by screen.presenter.uiState.collectAsState()
                MachineSettingsScreen(state, screen.presenter, onBack = {}, navigation = screen.navigation)
            }
        }
        settle(screen)
    }

    private fun ComposeUiTest.settle(screen: Screen) {
        repeat(3) {
            waitForIdle()
            screen.run()
        }
        waitForIdle()
    }

    @Test
    fun theScreenShowsTheNoticeAndTheProfileFileIsDisabled() = runComposeUiTest {
        val screen = Screen(TestScope(), withAdapter = false)
        show(screen)
        onNodeWithTag(machineSettingTag("no-tab")).assertIsDisplayed()
        onNodeWithText("This machine has no tab. Each change goes into the stored profile. The tab of this machine uses the changes when it opens.")
            .assertIsDisplayed()
        onNodeWithTag(machineSettingTag("profile-file")).performScrollTo().assertIsNotEnabled().performClick()
        onNodeWithText("Open a tab of this machine to export or import a profile file.").assertIsDisplayed()
        assertTrue(screen.opened.isEmpty())
        screen.close()
    }

    @Test
    fun aSwitchWithNoTabWritesTheStore() = runComposeUiTest {
        val screen = Screen(TestScope(), withAdapter = false)
        show(screen)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.Display)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("short-press-zero")).performScrollTo().performClick()
        settle(screen)
        assertTrue(screen.stored().display.shortPressZero)
        screen.close()
    }

    @Test
    fun withNoAdapterProfileTheEncoderAndTheProbeInputAreDisabled() = runComposeUiTest {
        val screen = Screen(TestScope(), withAdapter = false)
        show(screen)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.AxisX)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("encoder:x")).performScrollTo().assertIsNotEnabled().performClick()
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.Probe)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("probe-input")).performScrollTo().assertIsNotEnabled().performClick()
        assertTrue(screen.opened.isEmpty())
        screen.close()
    }

    @Test
    fun withAnAdapterProfileTheEncoderAndTheProbeInputOpen() = runComposeUiTest {
        val screen = Screen(TestScope(), withAdapter = true)
        show(screen)
        assertEquals(0, onAllNodesWithText("This machine has no adapter profile. Connect the adapter one time to make its profile.").fetchSemanticsNodes().size)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.AxisX)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("encoder:x")).performScrollTo().assertIsEnabled().performClick()
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.Probe)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("probe-input")).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("encoder x", "probe input"), screen.opened)
        screen.close()
    }
}
