package org.superdro.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.app_add_machine
import org.superdro.core.resources.app_link_bluetooth
import org.superdro.core.resources.app_link_demo
import org.superdro.core.resources.app_link_usb
import org.superdro.core.resources.app_link_wifi
import org.superdro.core.resources.app_more_options
import org.superdro.core.resources.app_no_machine_online
import org.superdro.core.resources.app_remembered_machines
import org.superdro.core.resources.app_settings
import org.superdro.core.resources.frame_menu_disconnect
import org.superdro.core.resources.frame_menu_rename
import org.superdro.core.resources.frame_tab_description
import org.superdro.core.resources.frame_update_available
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.ic_bluetooth
import org.superdro.core.resources.ic_circle
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_more_vert
import org.superdro.core.resources.ic_radio_unchecked
import org.superdro.core.resources.ic_science
import org.superdro.core.resources.ic_settings
import org.superdro.core.resources.ic_usb
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.service_background_app_settings
import org.superdro.core.resources.service_background_grant_permission
import org.superdro.core.resources.service_background_warning
import org.superdro.core.resources.service_background_warning_prerequisite
import org.superdro.core.resources.service_background_warning_restricted
import org.superdro.core.resources.service_background_warning_title
import org.superdro.core.resources.service_battery_not_now
import org.superdro.core.resources.ic_wifi
import org.superdro.core.resources.route_adapter_details
import org.superdro.core.resources.route_machine_settings
import org.superdro.core.ui.navigation.AboutRoute
import org.superdro.core.ui.navigation.AdapterDetailsRoute
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.AppSettingsRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.RenameMachineRoute
import org.superdro.core.ui.tabs.DeviceTabUiState
import org.superdro.core.ui.tabs.DeviceTabsEvents
import org.superdro.core.ui.tabs.DeviceTabsUiState
import org.superdro.core.ui.tabs.StatusRoles
import org.superdro.core.ui.tabs.TabStatus
import org.superdro.core.ui.tabs.TabStatusIcon
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.accentColor

/** The test tag of the tab of [id]. */
fun tabTag(id: DeviceId): String = "tab:${id.value}"

/** The test tag of the content area below the tab row. */
const val FRAME_CONTENT_TAG: String = "frame-content"

/** The test tag of the "+" button. */
const val ADD_MACHINE_TAG: String = "add-machine"

/**
 * The frame of the app: the tab row of the online machines with the
 * global actions, the content of the selected tab, and the snackbar of the frame.
 *
 * - One tap on a tab selects it. A long press opens the tab menu: Disconnect (the pause of the
 *   machine), Rename, Machine settings, and Adapter details (only while the session is
 *   `Connected`).
 * - A swipe does not change the tab: the content has no pager.
 * - "+" opens the add-device sheet. The gear opens the app settings. The overflow menu has About,
 *   Logs, Machine profiles, Remembered machines, Adapters, Probe library, Data output, and
 *   "Disconnect all".
 * - The frame keeps out of the system bars and the display cutout (`WindowInsets.safeDrawing`).
 *
 * @param navigation The back stacks: the frame opens the routes of the menus.
 * @param content The content below the tab row, for example the navigation host. It gets the
 *   modifier of the content area.
 */
@Composable
fun AppFrame(
    state: DeviceTabsUiState,
    events: DeviceTabsEvents,
    navigation: NavigationHolder,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    FrameLayout(
        state = state,
        events = events,
        modifier = modifier,
        onAddMachine = { navigation.push(AddDeviceRoute) },
        onOpenSettings = { navigation.push(AppSettingsRoute) },
        onOverflowAction = { action ->
            events.onCloseOverflow()
            val route = action.route
            if (route == null) events.onDisconnectAll() else navigation.push(route)
        },
        onTabMenuAction = { id, action ->
            events.onCloseMenu()
            when (action) {
                TabMenuAction.Disconnect -> events.onPause(id)
                TabMenuAction.Rename -> navigation.push(RenameMachineRoute(id.value))
                TabMenuAction.MachineSettings -> {
                    events.onSelect(id)
                    navigation.tab(id).push(MachineSettingsRoute)
                }
                TabMenuAction.AdapterDetails -> {
                    events.onSelect(id)
                    navigation.tab(id).push(AdapterDetailsRoute)
                }
            }
        },
        onOpenUpdate = {
            events.onAcknowledgeUpdate()
            navigation.push(AboutRoute)
        },
        content = content,
    )
}

/** The items of the tab menu. */
enum class TabMenuAction { Disconnect, Rename, MachineSettings, AdapterDetails }

/**
 * The frame with the navigation as callbacks. [AppFrame] gives the callbacks; a test gives its
 * own.
 */
@Composable
fun FrameLayout(
    state: DeviceTabsUiState,
    events: DeviceTabsEvents,
    onAddMachine: () -> Unit,
    onOpenSettings: () -> Unit,
    onOverflowAction: (OverflowAction) -> Unit,
    onTabMenuAction: (DeviceId, TabMenuAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenUpdate: () -> Unit = { events.onAcknowledgeUpdate() },
    content: @Composable (Modifier) -> Unit,
) {
    val snackbarHost = LocalAppSnackbarHostState.current
    val message = state.messages.firstOrNull()
    if (message != null) {
        val text = message.text.resolve()
        LaunchedEffect(message.id) {
            snackbarHost.showSnackbar(text, withDismissAction = true)
            events.onMessageShown(message.id)
        }
    }
    var warningDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.backgroundWarning) {
        if (!state.backgroundWarning) warningDialogOpen = false
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopRow(
                state = state,
                events = events,
                onAddMachine = onAddMachine,
                onOpenSettings = onOpenSettings,
                onOverflowAction = onOverflowAction,
                onTabMenuAction = onTabMenuAction,
                onOpenBackgroundWarning = { warningDialogOpen = true },
                onOpenUpdate = onOpenUpdate,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxSize()) {
                content(Modifier.fillMaxSize().testTag(FRAME_CONTENT_TAG))
                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
    if (warningDialogOpen && state.backgroundWarning) {
        BackgroundWarningDialog(
            status = state.keepAliveStatus,
            onGrantPermission = {
                warningDialogOpen = false
                events.onRequestBackgroundPermission()
            },
            onOpenAppSettings = {
                warningDialogOpen = false
                events.onOpenBackgroundAppSettings()
            },
            onDismiss = { warningDialogOpen = false },
        )
    }
}

/** The test tag of the warning indicator that machine connections may disconnect in the background. */
const val BACKGROUND_WARNING_TAG: String = "backgroundWarning"

/** The test tag of the indicator that a new release version is available. */
const val UPDATE_AVAILABLE_TAG: String = "updateAvailable"

/**
 * Compact chip in [TopRow] when an unseen release is available. One tap marks the version as
 * seen and opens the About screen.
 */
@Composable
private fun UpdateAvailableIndicator(version: String, onClick: () -> Unit, minHeight: Dp) {
    Box(
        modifier = Modifier
            .heightIn(min = minHeight)
            .padding(horizontal = 4.dp)
            .testTag(UPDATE_AVAILABLE_TAG)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_info),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.frame_update_available, version),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Compact caution indicator in [TopRow] while the connection service cannot keep machine
 * connections active in the background (`KeepAliveStatus.linksCanStopInBackground`). Tapping it
 * opens [BackgroundWarningDialog] with the explanation and action buttons.
 */
@Composable
private fun BackgroundWarningIndicator(onClick: () -> Unit, minSize: Dp) {
    val description = stringResource(Res.string.service_background_warning)
    Box(
        modifier = Modifier
            .size(minSize)
            .testTag(BACKGROUND_WARNING_TAG)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).size(20.dp),
            )
        }
    }
}

/**
 * Explains why machine connections may disconnect in the background and provides direct actions
 * to grant the missing permission or open the system app settings.
 */
@Composable
private fun BackgroundWarningDialog(
    status: KeepAliveStatus,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detailRes = if (status == KeepAliveStatus.StartNotAllowed) {
        Res.string.service_background_warning_restricted
    } else {
        Res.string.service_background_warning_prerequisite
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(Res.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
        },
        title = { Text(stringResource(Res.string.service_background_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.service_background_warning))
                Text(stringResource(detailRes))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status != KeepAliveStatus.StartNotAllowed) {
                    TextButton(colors = AccentButtons.text(), onClick = onGrantPermission) {
                        Text(stringResource(Res.string.service_background_grant_permission))
                    }
                }
                TextButton(colors = AccentButtons.text(), onClick = onOpenAppSettings) {
                    Text(stringResource(Res.string.service_background_app_settings))
                }
            }
        },
        dismissButton = {
            TextButton(colors = AccentButtons.text(), onClick = onDismiss) {
                Text(stringResource(Res.string.service_battery_not_now))
            }
        },
    )
}

/**
 * The tab row of the online machines, then the background warning indicator (when active),
 * "+", the gear, and the overflow menu. The icon buttons have the minimum touch target.
 */
@Composable
private fun TopRow(
    state: DeviceTabsUiState,
    events: DeviceTabsEvents,
    onAddMachine: () -> Unit,
    onOpenSettings: () -> Unit,
    onOverflowAction: (OverflowAction) -> Unit,
    onTabMenuAction: (DeviceId, TabMenuAction) -> Unit,
    onOpenBackgroundWarning: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val target = LocalTouchTargets.current.button
    val selectedIndex = state.selectedIndex
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedIndex.coerceAtLeast(0),
            modifier = Modifier.weight(1f),
            edgePadding = 0.dp,
            divider = {},
            indicator = {
                // The indicator reads the position of the selected tab. With no selected tab
                // there is no position, so the row shows no indicator.
                if (selectedIndex >= 0) {
                    // A thin line: in light mode not `primary`.
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true),
                        width = Dp.Unspecified,
                        color = accentColor(),
                    )
                }
            },
        ) {
            state.tabs.forEach { tab ->
                DeviceTab(
                    tab = tab,
                    selected = tab.id == state.selected,
                    menuOpen = state.menuFor == tab.id,
                    minHeight = target,
                    onSelect = { events.onSelect(tab.id) },
                    onLongPress = { events.onOpenMenu(tab.id) },
                    onDismissMenu = events::onCloseMenu,
                    onMenuAction = { action -> onTabMenuAction(tab.id, action) },
                )
            }
        }
        val availableUpdateVersion = state.availableUpdateVersion
        if (availableUpdateVersion != null) {
            UpdateAvailableIndicator(version = availableUpdateVersion, onClick = onOpenUpdate, minHeight = target)
        }
        if (state.backgroundWarning) {
            BackgroundWarningIndicator(onClick = onOpenBackgroundWarning, minSize = target)
        }
        IconButton(onClick = onAddMachine, modifier = Modifier.size(target).testTag(ADD_MACHINE_TAG)) {
            Icon(painterResource(Res.drawable.ic_add), contentDescription = stringResource(Res.string.app_add_machine))
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(target)) {
            Icon(painterResource(Res.drawable.ic_settings), contentDescription = stringResource(Res.string.app_settings))
        }
        Box {
            IconButton(onClick = events::onOpenOverflow, modifier = Modifier.size(target)) {
                Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = stringResource(Res.string.app_more_options))
            }
            DropdownMenu(
                expanded = state.overflowOpen,
                onDismissRequest = events::onCloseOverflow,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                OverflowAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(action.label)) },
                        onClick = { onOverflowAction(action) },
                        modifier = Modifier.heightIn(min = target),
                    )
                }
            }
        }
    }
}

/**
 * One tab: the transport icon, the machine name, and the status with an icon and a short text.
 * The tab is not a Material `Tab`, because a `Tab` has no long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceTab(
    tab: DeviceTabUiState,
    selected: Boolean,
    menuOpen: Boolean,
    minHeight: Dp,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onMenuAction: (TabMenuAction) -> Unit,
) {
    val linkName = stringResource(linkName(tab.transport))
    val statusText = tab.status.text.resolve()
    val description = stringResource(Res.string.frame_tab_description, tab.name, linkName, statusText)
    Box {
        Row(
            modifier = Modifier
                .heightIn(min = minHeight)
                .testTag(tabTag(tab.id))
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = description
                }
                .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(transportIcon(tab.transport)),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = tab.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusChip(tab.status, statusText)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.frame_menu_disconnect)) },
                onClick = { onMenuAction(TabMenuAction.Disconnect) },
                modifier = Modifier.heightIn(min = minHeight),
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.frame_menu_rename)) },
                onClick = { onMenuAction(TabMenuAction.Rename) },
                modifier = Modifier.heightIn(min = minHeight),
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.route_machine_settings)) },
                onClick = { onMenuAction(TabMenuAction.MachineSettings) },
                modifier = Modifier.heightIn(min = minHeight),
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.route_adapter_details)) },
                onClick = { onMenuAction(TabMenuAction.AdapterDetails) },
                enabled = tab.adapterDetailsEnabled,
                modifier = Modifier.heightIn(min = minHeight),
            )
        }
    }
}

/** The status of a tab: the icon and the short text on the container of its roles. */
@Composable
fun StatusChip(status: TabStatus, text: String, modifier: Modifier = Modifier) {
    val (container, content) = statusColors(status.roles)
    Surface(modifier = modifier, color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusIcon(status.icon, content)
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun StatusIcon(icon: TabStatusIcon, color: Color) {
    val size = Modifier.size(14.dp)
    when (icon) {
        TabStatusIcon.Progress, TabStatusIcon.Reconnecting -> CircularProgressIndicator(modifier = size, color = color, strokeWidth = 2.dp)
        else -> Icon(
            painter = painterResource(
                when (icon) {
                    TabStatusIcon.Connected -> Res.drawable.ic_circle
                    TabStatusIcon.Warning -> Res.drawable.ic_warning
                    TabStatusIcon.Error -> Res.drawable.ic_error
                    else -> Res.drawable.ic_radio_unchecked
                },
            ),
            contentDescription = null,
            tint = color,
            modifier = size,
        )
    }
}

/** Gives the container and the content color of [roles]. */
@Composable
fun statusColors(roles: StatusRoles): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (roles) {
        StatusRoles.Connected -> scheme.primaryContainer to scheme.onPrimaryContainer
        StatusRoles.Reconnect -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        StatusRoles.Failure -> scheme.errorContainer to scheme.onErrorContainer
        StatusRoles.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

/** Gives the icon of a link type. */
fun transportIcon(kind: TransportKind): DrawableResource = when (kind) {
    TransportKind.BluetoothSpp, TransportKind.Ble -> Res.drawable.ic_bluetooth
    TransportKind.UsbSerial -> Res.drawable.ic_usb
    TransportKind.Tcp -> Res.drawable.ic_wifi
    TransportKind.Fake -> Res.drawable.ic_science
}

/** Gives the name of a link type. */
fun linkName(kind: TransportKind): StringResource = when (kind) {
    TransportKind.BluetoothSpp, TransportKind.Ble -> Res.string.app_link_bluetooth
    TransportKind.UsbSerial -> Res.string.app_link_usb
    TransportKind.Tcp -> Res.string.app_link_wifi
    TransportKind.Fake -> Res.string.app_link_demo
}

/** The minimum height of a large button: 72 dp (64 dp minimum, keypad keys 72 dp). */
private val LargeButtonHeight: Dp = 72.dp

/**
 * The empty state: "No machine is online", the number of remembered
 * machines with a link to the pane, and a large "Add machine" button. The large button uses
 * `primaryContainer` with `onPrimaryContainer`.
 */
@Composable
fun EmptyState(
    rememberedMachineCount: Int,
    onAddMachine: () -> Unit,
    onOpenRememberedMachines: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.app_no_machine_online),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (rememberedMachineCount > 0) {
            // In light mode, `primary` is not a text color.
            TextButton(
                onClick = onOpenRememberedMachines,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button),
                colors = AccentButtons.text(),
            ) {
                Text(pluralStringResource(Res.plurals.app_remembered_machines, rememberedMachineCount, rememberedMachineCount))
            }
        }
        Spacer(Modifier.size(24.dp))
        Button(
            onClick = onAddMachine,
            modifier = Modifier.heightIn(min = LargeButtonHeight),
            colors = AccentButtons.filled(),
        ) {
            Icon(painterResource(Res.drawable.ic_add), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.app_add_machine), style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * The state of the frame with no tabs, for [AppPreview]. The frame with tabs uses
 * [DeviceTabsUiState].
 */
@Immutable
data class AppFrameState(
    val rememberedMachineCount: Int = 0,
)

/** The frame with no tab: only the empty state. [AppPreview] calls it. */
@Composable
fun AppFrame(
    state: AppFrameState,
    modifier: Modifier = Modifier,
    onAddMachine: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenRememberedMachines: () -> Unit = {},
    onOverflowAction: (OverflowAction) -> Unit = {},
) {
    FrameLayout(
        state = DeviceTabsUiState(rememberedCount = state.rememberedMachineCount),
        events = NoTabEvents,
        modifier = modifier,
        onAddMachine = onAddMachine,
        onOpenSettings = onOpenSettings,
        onOverflowAction = onOverflowAction,
        onTabMenuAction = { _, _ -> },
    ) { contentModifier ->
        EmptyState(
            rememberedMachineCount = state.rememberedMachineCount,
            onAddMachine = onAddMachine,
            onOpenRememberedMachines = onOpenRememberedMachines,
            modifier = contentModifier,
        )
    }
}

/** The events of a frame with no presenter. */
private object NoTabEvents : DeviceTabsEvents {
    override fun onSelect(id: DeviceId) = Unit
    override fun onOpenMenu(id: DeviceId) = Unit
    override fun onCloseMenu() = Unit
    override fun onPause(id: DeviceId) = Unit
    override fun onOpenOverflow() = Unit
    override fun onCloseOverflow() = Unit
    override fun onDisconnectAll() = Unit
    override fun onMessageShown(id: Long) = Unit
}
