package org.superdro.core.ui.adapter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.ThemeMode
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.ui.adapter.details.AdapterDetailsEvents
import org.superdro.core.ui.adapter.details.AdapterDetailsScreen
import org.superdro.core.ui.adapter.details.AdapterDetailsTags
import org.superdro.core.ui.adapter.details.GearTarget
import org.superdro.core.ui.adapter.identity.NewAdapterDialog
import org.superdro.core.ui.adapter.identity.NewAdapterEvents
import org.superdro.core.ui.adapter.identity.NewAdapterTags
import org.superdro.core.ui.adapter.inputs.InputSheetTags
import org.superdro.core.ui.adapter.inputs.TachPulsesSheet
import org.superdro.core.ui.adapter.list.AdapterRowUi
import org.superdro.core.ui.adapter.list.AdaptersEvents
import org.superdro.core.ui.adapter.list.AdaptersScreen
import org.superdro.core.ui.adapter.list.AdaptersTags
import org.superdro.core.ui.adapter.profile.ProfileActionTags
import org.superdro.core.ui.adapter.profile.ProfileActionsUiState
import org.superdro.core.ui.adapter.profile.ProfileDialog
import org.superdro.core.ui.adapter.list.OfflineAdapterScreen
import org.superdro.core.ui.adapter.common.IMPORT_REPORT_TAG
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the adapter screens. */
@OptIn(ExperimentalTestApi::class)
class AdapterScreensUiTest {

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) { content() }
        }
    }

    /** The details events that record each call. */
    private class RecordingDetails : AdapterDetailsEvents {
        val calls = mutableListOf<String>()
        override fun onSelectMode(channel: InputChannel, mode: InputMode) { calls += "select ${channel.letter} ${mode.id}" }
        override fun onCancel(channel: InputChannel) { calls += "cancel ${channel.letter}" }
        override fun onSave(channel: InputChannel) { calls += "save ${channel.letter}" }
        override fun onReference(channel: InputChannel) { calls += "reference ${channel.letter}" }
        override fun onClear(channel: InputChannel) { calls += "clear ${channel.letter}" }
        override fun onGear(target: GearTarget) { calls += "gear $target" }
        override fun onRestart() { calls += "restart" }
        override fun onRestartDialogDismissed() { calls += "dismiss restart" }
        override fun onCreateProfile() { calls += "create" }
    }

    @Test
    fun theReportOfAProfileImportShowsInTheScreenAndNotInADialog() = runComposeUiTest {
        // The dialog rule: a report is general information. It is the first item of the screen.
        val report = ProfileActionsUiState(dialog = ProfileDialog.Report(AdapterSamples.importReport))
        var screen by mutableStateOf("details")
        setContent {
            Themed {
                when (screen) {
                    "details" -> AdapterDetailsScreen(AdapterSamples.details, report, AdapterSamples.RecordingActions(), RecordingDetails(), onBack = {})
                    "list" -> AdaptersScreen(AdapterSamples.adapters, report, AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {})
                    else -> OfflineAdapterScreen(AdapterSamples.offline, report, AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {})
                }
            }
        }
        listOf("details", "list", "offline").forEach { name ->
            screen = name
            waitForIdle()
            onNodeWithTag(IMPORT_REPORT_TAG).assertIsDisplayed()
            onNodeWithText("Import report").assertIsDisplayed()
            onNodeWithText("Imported: 4. Not imported: 1. Warnings: 0.").assertIsDisplayed()
            onNodeWithText("Items that the import did not map").assertIsDisplayed()
            onNodeWithText("SuperDRO has no setting for “w_axis_scale”. The import ignores it.").assertIsDisplayed()
            assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size, name)
        }
    }

    @Test
    fun theChannelRowsSendTheirEvents() = runComposeUiTest {
        val events = RecordingDetails()
        val actions = AdapterSamples.RecordingActions()
        setContent { Themed { AdapterDetailsScreen(AdapterSamples.details, ProfileActionsUiState(), actions, events, onBack = {}) } }
        onNodeWithText("Some changes will not take effect until the adapter restarts.").assertExists()
        onNodeWithTag(AdapterDetailsTags.RESTART).assertHeightIsAtLeast(64.dp).performClick()
        fun scrollTo(tag: String) = onNodeWithTag(AdapterDetailsTags.GRID).performScrollToNode(hasTestTag(tag))
        scrollTo(AdapterDetailsTags.reference(InputChannel.X))
        onNodeWithTag(AdapterDetailsTags.reference(InputChannel.X)).assertHeightIsAtLeast(64.dp).performClick()
        onNodeWithTag(AdapterDetailsTags.clear(InputChannel.X)).performClick()
        scrollTo(AdapterDetailsTags.save(InputChannel.Y))
        onNodeWithTag(AdapterDetailsTags.save(InputChannel.Y)).performClick()
        onNodeWithTag(AdapterDetailsTags.cancel(InputChannel.Y)).performClick()
        scrollTo(AdapterDetailsTags.gear(InputChannel.X))
        onNodeWithContentDescription("Settings of Input X").performClick()
        // The busy channel Z has disabled buttons.
        onNodeWithTag(AdapterDetailsTags.GRID).performScrollToNode(hasTestTag(AdapterDetailsTags.clear(InputChannel.Z)))
        onNodeWithTag(AdapterDetailsTags.reference(InputChannel.Z)).assertIsNotEnabled()
        onNodeWithTag(AdapterDetailsTags.clear(InputChannel.Z)).assertIsNotEnabled()
        onNodeWithContentDescription("Command in progress").assertExists()
        assertEquals(listOf("restart", "reference x", "clear x", "save y", "cancel y", "gear Encoder(input=x)"), events.calls)
        onNodeWithTag(ProfileActionTags.EXPORT).performClick()
        onNodeWithContentDescription("Forget the adapter profile").performClick()
        assertEquals(listOf("askExport", "askForget"), actions.calls)
    }

    @Test
    fun theModeListSelectsAMode() = runComposeUiTest {
        val events = RecordingDetails()
        setContent { Themed { AdapterDetailsScreen(AdapterSamples.details, ProfileActionsUiState(), AdapterSamples.RecordingActions(), events, onBack = {}) } }
        onNodeWithTag(AdapterDetailsTags.mode(InputChannel.X)).performClick()
        onNodeWithTag(AdapterDetailsTags.modeItem(InputChannel.X, InputMode.DigitalProbe)).performClick()
        assertEquals(listOf("select x 8"), events.calls)
        onNodeWithTag(AdapterDetailsTags.GRID).performScrollToNode(hasTestTag(AdapterDetailsTags.raw(InputChannel.V)))
        onNodeWithTag(AdapterDetailsTags.raw(InputChannel.V)).assertExists()
        onNodeWithText("p: 0, q: 1").assertExists()
    }

    @Test
    fun theLegacyPromptTogglesTheInputsAndCreatesTheProfile() = runComposeUiTest {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(AdapterSamples.legacyPrompt)
        val events = object : NewAdapterEvents {
            override fun onName(text: String) { calls += "name $text"; state = state.copy(name = text) }
            override fun onUse(profileId: String?) { calls += "use $profileId" }
            override fun onToggleDeclared(input: DeclaredInput) { calls += "toggle $input" }
            override fun onConfirm() { calls += "confirm" }
            override fun onDisconnect() { calls += "disconnect" }
            override fun onDismiss() { calls += "dismiss" }
        }
        setContent { Themed { NewAdapterDialog(state, events) } }
        onNodeWithText("It looks like you connected a new adapter.").assertExists()
        onNodeWithTag(NewAdapterTags.NAME).performTextReplacement("Caliper box")
        onNodeWithTag(NewAdapterTags.declared(DeclaredInput.Tach)).performClick()
        onNodeWithTag(NewAdapterTags.CONFIRM).performClick()
        onNodeWithTag(NewAdapterTags.DISCONNECT).performClick()
        assertEquals(listOf("name Caliper box", "toggle Tach", "confirm", "disconnect"), calls)
    }

    @Test
    fun theDiffersPromptOffersTheStoredProfile() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val events = object : NewAdapterEvents {
            override fun onName(text: String) = Unit
            override fun onUse(profileId: String?) { calls += "use $profileId" }
            override fun onToggleDeclared(input: DeclaredInput) = Unit
            override fun onConfirm() = Unit
            override fun onDisconnect() = Unit
            override fun onDismiss() = Unit
        }
        setContent { Themed { NewAdapterDialog(AdapterSamples.differsPrompt, events) } }
        onNodeWithText("Use “Spare”").assertExists()
        onNodeWithTag(NewAdapterTags.use("spare")).performClick()
        onNodeWithTag(NewAdapterTags.CREATE).performClick()
        assertEquals(listOf("use spare", "use null"), calls)
    }

    @Test
    fun theAdaptersScreenOpensARowAndCreatesAProfile() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val actions = AdapterSamples.RecordingActions()
        val events = object : AdaptersEvents {
            override fun onRow(row: AdapterRowUi) { calls += "row ${row.id}" }
            override fun onCreate() { calls += "create" }
            override fun onCreateName(text: String) = Unit
            override fun onCreateToggle(input: DeclaredInput) = Unit
            override fun onCreateConfirm() = Unit
            override fun onCreateDismiss() = Unit
        }
        setContent { Themed { AdaptersScreen(AdapterSamples.adapters, ProfileActionsUiState(), actions, events, onBack = {}) } }
        onNodeWithTag(AdaptersTags.row("old")).performClick()
        onNodeWithTag(AdaptersTags.CREATE).performClick()
        onNodeWithTag(AdaptersTags.IMPORT).performClick()
        assertEquals(listOf("row old", "create"), calls)
        assertEquals(listOf("importAsNew"), actions.calls)
    }

    @Test
    fun theTachSheetSetsThePulses() = runComposeUiTest {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(AdapterSamples.tach)
        setContent {
            Themed {
                TachPulsesSheet(state, onText = { text -> calls += "text $text"; state = state.copy(text = text) }, onSet = { calls += "set" }, onDismiss = { calls += "dismiss" })
            }
        }
        onNodeWithTag(InputSheetTags.PULSES).performTextReplacement("60")
        onNodeWithTag(InputSheetTags.SET_PULSES).assertHeightIsAtLeast(64.dp).performClick()
        assertTrue(calls.containsAll(listOf("text 60", "set")))
    }
}
