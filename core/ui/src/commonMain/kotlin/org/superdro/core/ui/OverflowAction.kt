package org.superdro.core.ui

import org.jetbrains.compose.resources.StringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.app_menu_about
import org.superdro.core.resources.app_menu_adapters
import org.superdro.core.resources.app_menu_data_output
import org.superdro.core.resources.app_menu_disconnect_all
import org.superdro.core.resources.app_menu_logs
import org.superdro.core.resources.app_menu_machine_profiles
import org.superdro.core.resources.app_menu_probe_library
import org.superdro.core.resources.app_menu_remembered_machines
import org.superdro.core.ui.navigation.AboutRoute
import org.superdro.core.ui.navigation.AdaptersRoute
import org.superdro.core.ui.navigation.DataOutputRoute
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.LogsRoute
import org.superdro.core.ui.navigation.MachineProfilesRoute
import org.superdro.core.ui.navigation.ProbeLibraryRoute
import org.superdro.core.ui.navigation.RememberedMachinesRoute

/**
 * The items of the global overflow menu, in the order of the menu. The app has no navigation drawer.
 * The tab menu, the tab back stack, and the global settings give the navigation.
 *
 * @property route The global screen of the item, or null for "Disconnect all".
 */
enum class OverflowAction(val label: StringResource, val route: GlobalRoute?) {
    About(Res.string.app_menu_about, AboutRoute),
    Logs(Res.string.app_menu_logs, LogsRoute),
    MachineProfiles(Res.string.app_menu_machine_profiles, MachineProfilesRoute),
    RememberedMachines(Res.string.app_menu_remembered_machines, RememberedMachinesRoute),
    Adapters(Res.string.app_menu_adapters, AdaptersRoute),
    ProbeLibrary(Res.string.app_menu_probe_library, ProbeLibraryRoute),
    DataOutput(Res.string.app_menu_data_output, DataOutputRoute),
    DisconnectAll(Res.string.app_menu_disconnect_all, null),
}
