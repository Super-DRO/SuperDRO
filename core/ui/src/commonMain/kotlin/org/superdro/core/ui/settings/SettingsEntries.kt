package org.superdro.core.ui.settings

import org.superdro.core.ui.navigation.AboutRoute
import org.superdro.core.ui.navigation.HelpRoute
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.navigation.AppSettingsRoute
import org.superdro.core.ui.navigation.DeleteProfileRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.KeyMappingRoute
import org.superdro.core.ui.navigation.LogsRoute
import org.superdro.core.ui.navigation.MachineProfilesRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.OfflineMachineSettingsRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.settings.about.AboutEntry
import org.superdro.core.ui.settings.app.AppSettingsEntry
import org.superdro.core.ui.settings.keys.KeyMappingEntry
import org.superdro.core.ui.settings.logs.LogsEntry
import org.superdro.core.ui.settings.machine.MachineSettingsEntry
import org.superdro.core.ui.settings.machine.OfflineMachineSettingsEntry
import org.superdro.core.ui.settings.profiles.DeleteProfileEntry
import org.superdro.core.ui.settings.profiles.MachineProfilesEntry

/**
 * The entries of the settings area. The central provider
 * ([org.superdro.core.ui.navigation.appEntryRegistry]) calls this function.
 *
 * - Tab back stack: the machine settings of the profile of the tab.
 * - Global back stack: the app settings, the machine profiles
 *   with the delete question, the machine settings of a profile with no tab, the about screen,
 *   the log screen, and the key mapping.
 *
 * Each entry gets its presenter from the DI graph (`core:di`, `SettingsUiModule`). Other areas
 * register the other screens of the settings: the adapter area the calibration wizard of an axis
 * (`CalibrationWizardRoute`), the functions area the profile file, the import preview, and the
 * report, and the data output area its screen. The machine settings open the profile file.
 */
fun EntryRegistry.settingsEntries() {
    tab<MachineSettingsRoute>(EntryKind.Screen, RouteArea.Settings) { route -> MachineSettingsEntry(this, route) }
    global<AppSettingsRoute>(EntryKind.Screen, RouteArea.Settings) { route -> AppSettingsEntry(this, route) }
    global<MachineProfilesRoute>(EntryKind.Screen, RouteArea.Settings) { route -> MachineProfilesEntry(this, route) }
    global<OfflineMachineSettingsRoute>(EntryKind.Screen, RouteArea.Settings) { route -> OfflineMachineSettingsEntry(this, route) }
    global<DeleteProfileRoute>(EntryKind.Dialog, RouteArea.Settings) { route -> DeleteProfileEntry(this, route) }
    global<AboutRoute>(EntryKind.Screen, RouteArea.Settings) { route -> AboutEntry(this, route) }
    global<HelpRoute>(EntryKind.Screen, RouteArea.Settings) { route ->
        AboutEntry(this, route, helpTopic = HelpTopic.entries.firstOrNull { topic -> topic.name == route.topic })
    }
    global<LogsRoute>(EntryKind.Screen, RouteArea.Settings) { route -> LogsEntry(this, route) }
    global<KeyMappingRoute>(EntryKind.Screen, RouteArea.Settings) { route -> KeyMappingEntry(this, route) }
}
