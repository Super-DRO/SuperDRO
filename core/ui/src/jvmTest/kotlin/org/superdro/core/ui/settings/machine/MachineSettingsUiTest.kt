package org.superdro.core.ui.settings.machine

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.AppSettings
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFont
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.settings.SettingsThemed
import org.superdro.core.ui.settings.SettingsWindow
import org.superdro.core.ui.settings.components.settingsGroupTag
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.settings.help.LocalHelpOpener
import org.superdro.core.ui.settings.help.helpButtonTag
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.text.UiText

/** The main flows of the machine settings screen with the real presenter on a database in memory. */
@OptIn(ExperimentalTestApi::class)
class MachineSettingsUiTest {

    private class Screen(val scope: TestScope, machine: MachineProfile) {
        val store = UiStoreFixture(scope)
        val messages = mutableListOf<UiText>()
        val opened = mutableListOf<String>()
        val helpOpened = mutableListOf<HelpTopic>()
        val profile: MachineProfile
        val presenter: MachineSettingsPresenter

        init {
            var stored: MachineProfile? = null
            scope.launch { stored = store.machines.createProfile(machine).valueOrNull() }
            scope.testScheduler.advanceUntilIdle()
            profile = stored!!
            presenter = MachineSettingsPresenter(
                profile.id, store.machines, store.adapters, FakeConnections(), MutableStateFlow(AppSettings()), scope.testPresenterContext(),
                showMessage = { text -> messages += text },
            )
            run()
        }

        val navigation = object : MachineSettingsNavigation {
            override fun openEncoder(input: Char) {
                opened += "encoder $input"
            }

            override fun openProbeLibrary() {
                opened += "probe library"
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

    private fun ComposeUiTest.show(screen: Screen, window: SettingsWindow = SettingsWindow.Landscape) {
        setContent {
            SettingsThemed(dark = false, window = window) {
                CompositionLocalProvider(LocalHelpOpener provides { topic -> screen.helpOpened += topic }) {
                    val state by screen.presenter.uiState.collectAsState()
                    MachineSettingsScreen(state, screen.presenter, onBack = {}, navigation = screen.navigation)
                }
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
    fun aSwitchWritesTheProfileAtOnce() = runComposeUiTest {
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        show(screen)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.Display)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("short-press-zero")).performScrollTo().assertIsOff().assertHeightIsAtLeast(64.dp).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("short-press-zero")).assertIsOn()
        assertTrue(screen.stored().display.shortPressZero)
        screen.close()
    }

    @Test
    fun theMachineGroupOpensTheProfileFile() = runComposeUiTest {
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        show(screen)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.Machine)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("profile-file")).performScrollTo().assertHeightIsAtLeast(64.dp).performClick()
        assertEquals(listOf("profile file"), screen.opened)
        screen.close()
    }

    @Test
    fun theFontChoiceShowsTheSevenFontsAndSelectsOne() = runComposeUiTest {
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        show(screen)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.Display)).performClick()
        settle(screen)
        listOf("DSEG7", "Monospace font of the tablet", "JetBrains Mono", "System font", "Roboto Mono", "Droid Sans Mono", "Cousine").forEach { name ->
            onNodeWithText(name).performScrollTo().assertIsDisplayed()
        }
        assertEquals(ReadoutFont.entries.size, onAllNodesWithText("-0123.456").fetchSemanticsNodes().size)
        onNodeWithTag(machineSettingTag("font:${ReadoutFont.JetBrainsMono.name}")).performScrollTo().performClick()
        settle(screen)
        assertEquals(ReadoutFont.JetBrainsMono, screen.stored().display.readoutFont)
        screen.close()
    }

    @Test
    fun theLabelFieldWritesTheLabelAndTheLastAxisStaysOn() = runComposeUiTest {
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        screen.presenter.setAxisEnabled(org.superdro.core.model.AxisId.Y, false)
        screen.presenter.setAxisEnabled(org.superdro.core.model.AxisId.Z, false)
        screen.run()
        show(screen)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.AxisX)).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("field:${SettingsTextField.LabelX.name}")).performTextClearance()
        onNodeWithTag(machineSettingTag("field:${SettingsTextField.LabelX.name}")).performTextInput("Q")
        settle(screen)
        assertEquals("Q", screen.stored().x.label)
        onNodeWithTag(machineSettingTag("enabled:X")).performClick()
        settle(screen)
        onNodeWithTag(machineSettingTag("enabled:X")).assertIsOn()
        assertEquals(1, screen.messages.size)
        assertTrue(screen.stored().x.enabled)
        // The encoder row opens the encoder settings of the adapter input.
        onNodeWithTag(machineSettingTag("encoder:x")).performScrollTo().performClick()
        assertEquals(listOf("encoder x"), screen.opened)
        screen.close()
    }

    @Test
    fun theProbeSwitchShowsTheEdgeFinderAndTheToolSetterAndNoZOffset() = runComposeUiTest {
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        show(screen)
        onNodeWithTag(settingsGroupTag(MachineSettingsGroup.Probe)).performClick()
        settle(screen)
        assertTrue(onAllNodesWithText("Use the probe as an edge finder").fetchSemanticsNodes().isEmpty())
        onNodeWithTag(machineSettingTag("probe")).performClick()
        settle(screen)
        onNodeWithText("Use the probe as an edge finder").assertIsDisplayed()
        onNodeWithText("Use the probe as a tool setter").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Z offset", substring = true).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(machineSettingTag("probe-library")).performScrollTo().performClick()
        assertEquals(listOf("probe library"), screen.opened)
        screen.close()
    }

    @Test
    fun theMuteSwitchOfTheMachineIsOffWithTheMuteScopeOfTheApp() = runComposeUiTest {
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        show(screen)
        onNodeWithTag(machineSettingTag("mute")).performScrollTo().assertIsNotEnabled()
        screen.close()
    }

    @Test
    fun theTypeChangeAsksFirst() = runComposeUiTest {
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        show(screen, SettingsWindow.Portrait)
        onNodeWithTag(machineSettingTag("machine-type")).performClick()
        waitForIdle()
        onNodeWithTag(machineSettingTag("machine-type") + ":1").performClick()
        settle(screen)
        onNodeWithText("Change the machine type?").assertIsDisplayed()
        onNodeWithText("Mill: the machine type changes from Vertical mill to Lathe.").assertIsDisplayed()
        assertEquals(MachineType.VerticalMill, screen.stored().machineType)
        onNodeWithText("Change type").performClick()
        settle(screen)
        assertEquals(MachineType.Lathe, screen.stored().machineType)
        assertFalse(onAllNodesWithText("Change the machine type?").fetchSemanticsNodes().isNotEmpty())
        screen.close()
    }

    @Test
    fun theHelpButtonOpensTheHelpTextInTheAboutScreen() = runComposeUiTest {
        // The dialog rule: the help text shows in the help group of the about screen, not in a dialog.
        val screen = Screen(TestScope(), MachineProfile.create(MachineType.VerticalMill, "Mill"))
        show(screen)
        onNodeWithTag(helpButtonTag(HelpTopic.MachineSettings)).assertHeightIsAtLeast(64.dp).performClick()
        waitForIdle()
        assertEquals(listOf(HelpTopic.MachineSettings), screen.helpOpened)
        assertEquals(0, onAllNodesWithTag("help-text").fetchSemanticsNodes().size)
        screen.close()
    }
}
