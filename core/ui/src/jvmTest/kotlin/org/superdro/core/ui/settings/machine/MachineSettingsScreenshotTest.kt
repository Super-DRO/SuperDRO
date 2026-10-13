package org.superdro.core.ui.settings.machine

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.settings.SettingsWindow
import org.superdro.core.ui.settings.renderSettings
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture

/**
 * Draws each group of the machine settings to PNG files
 * (`core/ui/build/screenshots/settings-machine-*.png`) in light and dark mode. The states come
 * from the real presenter on a database in memory.
 */
class MachineSettingsScreenshotTest {

    private fun stateOf(machine: MachineProfile, withAdapter: Boolean = false, edit: MachineSettingsPresenter.() -> Unit = {}): MachineSettingsUiState {
        val scope = TestScope()
        val store = UiStoreFixture(scope)
        var id = ""
        scope.launch {
            val adapterId = if (withAdapter) store.adapters.saveAdapterProfile(AdapterProfile(alias = "DRO adapter")).valueOrNull()?.id else null
            id = store.machines.createProfile(machine.copy(adapterProfileId = adapterId)).valueOrNull()!!.id
        }
        scope.testScheduler.advanceUntilIdle()
        val presenter = MachineSettingsPresenter(id, store.machines, store.adapters, FakeConnections(), MutableStateFlow(AppSettings()), scope.testPresenterContext())
        scope.testScheduler.advanceUntilIdle()
        presenter.edit()
        scope.testScheduler.advanceUntilIdle()
        val state = presenter.uiState.value
        presenter.close()
        store.close()
        return state
    }

    private fun render(name: String, state: MachineSettingsUiState, windows: List<SettingsWindow> = SettingsWindow.entries) {
        val files = renderSettings("machine-$name", windows) { MachineSettingsScreen(state, NoMachineSettingsEvents, onBack = {}) }
        files.forEach { file -> assertTrue(file.length() > 0, file.name) }
    }

    private val mill = MachineProfile.create(MachineType.VerticalMill, "Bridgeport")

    @Test
    fun theMachineTheDisplayAndTheFunctionGroupsOfAMill() {
        val base = stateOf(mill)
        render("machine", base)
        render("display", base.copy(group = MachineSettingsGroup.Display))
        render("functions", base.copy(group = MachineSettingsGroup.Functions), listOf(SettingsWindow.Landscape))
    }

    @Test
    fun theAxisGroupsWithAConflictAndASecondaryInput() {
        val state = stateOf(mill) {
            setPrimaryChannel(AxisId.X, 'y')
            setSecondaryEnabled(AxisId.Z, true)
        }
        render("axis-x", state.copy(group = MachineSettingsGroup.AxisX))
        render("axis-z", state.copy(group = MachineSettingsGroup.AxisZ), listOf(SettingsWindow.Landscape))
        render("angular", state.copy(group = MachineSettingsGroup.Angular), listOf(SettingsWindow.Landscape))
    }

    @Test
    fun theTachometerTheProbeAndTheLinkGroups() {
        val state = stateOf(mill.copy(adapter = AdapterAddress.Tcp("dro-adapter.local", 8080)), withAdapter = true) {
            setAxisEnabled(AxisId.Tachometer, true)
            setProbeEnabled(true)
        }
        render("tachometer", state.copy(group = MachineSettingsGroup.Tachometer), listOf(SettingsWindow.Landscape))
        render("probe", state.copy(group = MachineSettingsGroup.Probe), listOf(SettingsWindow.Landscape))
        render("link", state.copy(group = MachineSettingsGroup.Link))
        val presets = stateOf(mill) { setTachometerMode(org.superdro.core.model.TachometerMode.Preset) }
        render("tachometer-presets", presets.copy(group = MachineSettingsGroup.Tachometer), listOf(SettingsWindow.Portrait))
    }

    @Test
    fun theAuxiliaryReadoutAndTheCaliperOfALathe() {
        val lathe = MachineProfile.create(MachineType.Lathe, "Lathe")
        val state = stateOf(lathe) {
            setAxisEnabled(AxisId.Y, true)
            setAuxiliaryType(AuxiliaryType.CompoundSlide)
            setAuxiliaryVectoring(true)
        }
        render("auxiliary", state.copy(group = MachineSettingsGroup.AxisY))
        render("caliper", state.copy(group = MachineSettingsGroup.Caliper), listOf(SettingsWindow.Landscape))
        render("lathe-functions", state.copy(group = MachineSettingsGroup.Functions), listOf(SettingsWindow.Landscape))
    }

    @Test
    fun theTypeChangeQuestion() {
        val workspace = mill.workspaces.first().copy(subDatums = listOf(SubDatum(name = "P1", x = 1000.0, y = 2000.0)))
        val state = stateOf(
            mill.copy(workspaces = listOf(workspace), activeWorkspaceId = workspace.id, tools = listOf(Tool(name = "End mill", kind = ToolKind.Mill))),
        ) { setMachineType(MachineType.Lathe) }
        render("type-change", state)
    }
}
