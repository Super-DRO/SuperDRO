package org.superdro.core.ui.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ThemeMode
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.EmptyState
import org.superdro.core.ui.FrameLayout
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WidthClass
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.tabs.adddevice.AddDevicePresenter
import org.superdro.core.ui.tabs.adddevice.AddDeviceSheet
import org.superdro.core.ui.tabs.adddevice.SetupInput
import org.superdro.core.ui.tabs.remembered.MachineLinkState
import org.superdro.core.ui.tabs.remembered.RememberedMachineRow
import org.superdro.core.ui.tabs.remembered.RememberedMachinesEvents
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPane
import org.superdro.core.ui.tabs.remembered.RememberedMachinesUiState
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the frame, the add-device sheet, and the remembered machines pane to PNG files
 * (`core/ui/build/screenshots/tabs-*.png`) in light and dark mode, in landscape (1200 × 750 dp)
 * and in portrait (800 × 1280 dp). The states are built directly.
 */
class TabsScreenshotTest {

    private object NoEvents : DeviceTabsEvents {
        override fun onSelect(id: DeviceId) = Unit
        override fun onOpenMenu(id: DeviceId) = Unit
        override fun onCloseMenu() = Unit
        override fun onPause(id: DeviceId) = Unit
        override fun onOpenOverflow() = Unit
        override fun onCloseOverflow() = Unit
        override fun onDisconnectAll() = Unit
        override fun onMessageShown(id: Long) = Unit
    }

    private fun tab(id: String, name: String, state: SessionState, transport: TransportKind) =
        DeviceTabUiState(DeviceId(id), name, transport, state, TabStatusRules.of(state), inGrace = !state.isOnline)

    private val frameState = DeviceTabsUiState(
        tabs = listOf(
            tab("mill", "Mill", SessionState.Connected(0L), TransportKind.BluetoothSpp),
            tab("lathe", "Lathe", SessionState.Reconnecting(2, 0L, LinkFailure.Lost), TransportKind.UsbSerial),
            tab("router", "Router", SessionState.WaitingForDevice(LinkFailure.RadioOff), TransportKind.Tcp),
            tab("demo", "Demo mill", SessionState.AwaitingData, TransportKind.Fake),
        ),
        selected = DeviceId("mill"),
        rememberedCount = 3,
    )

    private val sizes = listOf(Triple("landscape", 1200, 750), Triple("portrait", 800, 1280))

    @Composable
    private fun Themed(dark: Boolean, landscape: Boolean, content: @Composable () -> Unit) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout(if (landscape) WidthClass.Expanded else WidthClass.Medium, landscape)) {
                content()
            }
        }
    }

    @Test
    fun theFrameWithFourTabs() {
        listOf(false, true).forEach { dark ->
            sizes.forEach { (name, width, height) ->
                val file = ScreenshotRenderer.render("tabs-frame-$name-${if (dark) "dark" else "light"}", width, height) {
                    Themed(dark, name == "landscape") {
                        FrameLayout(frameState, NoEvents, onAddMachine = {}, onOpenSettings = {}, onOverflowAction = {}, onTabMenuAction = { _, _ -> }) { modifier ->
                            Box(modifier, contentAlignment = Alignment.Center) {
                                Text("Readout of the selected tab", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                assertTrue(file.length() > 0)
            }
        }
    }

    @Test
    fun theEmptyStateAndTheTabMenu() {
        listOf(false, true).forEach { dark ->
            val theme = if (dark) "dark" else "light"
            ScreenshotRenderer.render("tabs-empty-landscape-$theme", 1200, 750) {
                Themed(dark, true) {
                    FrameLayout(DeviceTabsUiState(rememberedCount = 2), NoEvents, {}, {}, {}, { _, _ -> }) { modifier ->
                        EmptyState(2, onAddMachine = {}, onOpenRememberedMachines = {}, modifier = modifier)
                    }
                }
            }
            ScreenshotRenderer.render("tabs-menu-landscape-$theme", 1200, 750) {
                Themed(dark, true) {
                    FrameLayout(frameState.copy(menuFor = DeviceId("mill")), NoEvents, {}, {}, {}, { _, _ -> }) { modifier -> Box(modifier) }
                }
            }
        }
    }

    @Test
    fun theRememberedMachinesPane() {
        val state = RememberedMachinesUiState(
            rows = listOf(
                RememberedMachineRow("mill", "Mill", TransportKind.BluetoothSpp, "24:6F:28:A1:B2:C3", MachineLinkState.Online),
                RememberedMachineRow("lathe", "Lathe", TransportKind.UsbSerial, "0403:6001", MachineLinkState.Offline),
                RememberedMachineRow("router", "Router", TransportKind.Tcp, "192.168.4.1:8080", MachineLinkState.Paused),
            ),
        )
        val events = object : RememberedMachinesEvents {
            override fun onConnect(profileId: String) = Unit
            override fun onPause(profileId: String) = Unit
            override fun onEdit(profileId: String) = Unit
            override fun onAskRemove(profileId: String) = Unit
            override fun onCancelRemove() = Unit
            override fun onConfirmRemove() = Unit
        }
        listOf(false, true).forEach { dark ->
            sizes.forEach { (name, width, height) ->
                ScreenshotRenderer.render("tabs-remembered-$name-${if (dark) "dark" else "light"}", width, height) {
                    Themed(dark, name == "landscape") { RememberedMachinesPane(state, events, onBack = {}) }
                }
            }
        }
    }

    @Test
    fun theAddDeviceSheet() {
        val scope = TestScope()
        val store = UiStoreFixture(scope)
        scope.launch { store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "Lathe")) }
        scope.testScheduler.advanceUntilIdle()
        val discoveries = listOf(
            FakeDiscovery(
                TransportKind.BluetoothSpp,
                listOf(
                    DiscoveredAdapter(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "HC-05", "24:6F:28:A1:B2:C3"),
                    DiscoveredAdapter(AdapterAddress.BluetoothSpp("98:D3:31:F5:12:0A"), "DRO adapter", "98:D3:31:F5:12:0A"),
                ),
            ),
            FakeDiscovery(TransportKind.UsbSerial, listOf(DiscoveredAdapter(AdapterAddress.UsbSerial(0x0403, 0x6001), "FT232R", "0403:6001", needsPermission = true))),
        )
        val presenter = AddDevicePresenter(
            FakeConnections(), store.tabs, store.machines, store.adapters, settingsFlow(), discoveries, FakeCapabilities(),
            FakeDemoAdapter(), RecordingRequests(), FirstConnectRequests(store.appSettings), scope.testPresenterContext(),
        )
        scope.testScheduler.advanceUntilIdle()
        listOf(false, true).forEach { dark ->
            sizes.forEach { (name, width, height) ->
                ScreenshotRenderer.render("tabs-add-link-$name-${if (dark) "dark" else "light"}", width, height) {
                    Themed(dark, name == "landscape") { AddDeviceSheet(presenter.uiState.value, presenter, onDismiss = {}) }
                }
            }
        }
        presenter.selectLink(presenter.uiState.value.sections.first().options.first())
        presenter.setName("Bridgeport")
        presenter.updateSetup { setup -> setup.copy(quill = SetupInput(true, 'x')) }
        scope.testScheduler.advanceUntilIdle()
        listOf(false, true).forEach { dark ->
            sizes.forEach { (name, width, height) ->
                ScreenshotRenderer.render("tabs-add-profile-$name-${if (dark) "dark" else "light"}", width, height) {
                    Themed(dark, name == "landscape") { AddDeviceSheet(presenter.uiState.value, presenter, onDismiss = {}) }
                }
            }
        }
        presenter.close()
        store.close()
    }
}
