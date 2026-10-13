package org.superdro.core.ui.settings.app

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.MuteScope
import org.superdro.core.model.ThemeMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_unit_mm
import org.superdro.core.resources.route_about
import org.superdro.core.resources.route_app_settings
import org.superdro.core.resources.route_key_mapping
import org.superdro.core.resources.route_logs
import org.superdro.core.resources.route_machine_profiles
import org.superdro.core.resources.route_remembered_machines
import org.superdro.core.resources.settings_app_about_summary
import org.superdro.core.resources.settings_app_battery
import org.superdro.core.resources.settings_app_battery_exempt
import org.superdro.core.resources.settings_app_battery_not_exempt
import org.superdro.core.resources.settings_app_battery_open
import org.superdro.core.resources.settings_app_dxf_bezier
import org.superdro.core.resources.settings_app_dxf_blocks
import org.superdro.core.resources.settings_app_dxf_entities
import org.superdro.core.resources.settings_app_dxf_note
import org.superdro.core.resources.settings_app_dxf_polyline
import org.superdro.core.resources.settings_app_dxf_reset
import org.superdro.core.resources.settings_app_dxf_source_bytes
import org.superdro.core.resources.settings_app_dxf_spline
import org.superdro.core.resources.settings_app_dxf_time
import org.superdro.core.resources.settings_app_dxf_tolerance
import org.superdro.core.resources.settings_app_full_screen
import org.superdro.core.resources.settings_app_full_screen_summary
import org.superdro.core.resources.settings_app_get_sig
import org.superdro.core.resources.settings_app_get_sig_summary
import org.superdro.core.resources.settings_app_graphical_view
import org.superdro.core.resources.settings_app_group_appearance
import org.superdro.core.resources.settings_app_group_connection
import org.superdro.core.resources.settings_app_group_developer
import org.superdro.core.resources.settings_app_group_dxf
import org.superdro.core.resources.settings_app_group_machines
import org.superdro.core.resources.settings_app_group_sounds
import org.superdro.core.resources.settings_app_keys_summary
import org.superdro.core.resources.settings_app_large_buttons
import org.superdro.core.resources.settings_app_large_buttons_summary
import org.superdro.core.resources.settings_app_link_types
import org.superdro.core.resources.settings_app_logs_summary
import org.superdro.core.resources.settings_app_mute
import org.superdro.core.resources.settings_app_mute_machine_note
import org.superdro.core.resources.settings_app_mute_scope
import org.superdro.core.resources.settings_app_mute_scope_app
import org.superdro.core.resources.settings_app_mute_scope_machine
import org.superdro.core.resources.settings_app_mute_summary
import org.superdro.core.resources.settings_app_profiles_summary
import org.superdro.core.resources.settings_app_remembered_summary
import org.superdro.core.resources.settings_app_setup_reminder
import org.superdro.core.resources.settings_app_setup_reminder_summary
import org.superdro.core.resources.settings_app_show_hints
import org.superdro.core.resources.settings_app_show_hints_summary
import org.superdro.core.resources.settings_app_theme
import org.superdro.core.resources.settings_app_theme_dark
import org.superdro.core.resources.settings_app_theme_summary
import org.superdro.core.resources.settings_app_theme_system
import org.superdro.core.resources.settings_app_view_above
import org.superdro.core.resources.settings_app_view_below
import org.superdro.core.resources.settings_app_view_full_screen
import org.superdro.core.resources.settings_app_wake_lock
import org.superdro.core.resources.settings_app_wake_lock_summary
import org.superdro.core.resources.settings_app_wake_lock_warning
import org.superdro.core.ui.linkName
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.settings.components.NoticeKind
import org.superdro.core.ui.settings.components.SettingsButtons
import org.superdro.core.ui.settings.components.SettingsGroupItem
import org.superdro.core.ui.settings.components.SettingsGroupLayout
import org.superdro.core.ui.settings.components.SettingsLink
import org.superdro.core.ui.settings.components.SettingsNote
import org.superdro.core.ui.settings.components.SettingsNotice
import org.superdro.core.ui.settings.components.SettingsSection
import org.superdro.core.ui.settings.components.SettingsSegmented
import org.superdro.core.ui.settings.components.SettingsSwitch
import org.superdro.core.ui.settings.components.SettingsTextField
import org.superdro.core.ui.settings.help.HelpButton
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.tabs.adddevice.LinkTypes
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the app settings screen. */
object AppSettingsTags {
    const val THEME = "app-theme"
    const val LARGE_BUTTONS = "app-large-buttons"
    const val FULL_SCREEN = "app-full-screen"
    const val SHOW_HINTS = "app-show-hints"
    const val SETUP_REMINDER = "app-setup-reminder"
    const val GRAPHICAL_VIEW = "app-graphical-view"
    const val MUTE = "app-mute"
    const val MUTE_SCOPE = "app-mute-scope"
    const val WAKE_LOCK = "app-wake-lock"
    const val WAKE_LOCK_WARNING = "app-wake-lock-warning"
    const val BATTERY_STATE = "app-battery-state"
    const val BATTERY_OPEN = "app-battery-open"
    const val DXF_RESET = "app-dxf-reset"
    const val GET_SIG = "app-get-sig"

    /** The tag of the switch of the link type [kind]. */
    fun linkType(kind: org.superdro.core.model.TransportKind): String = "app-link:${kind.name}"

    /** The tag of the field of [limit]. */
    fun dxf(limit: DxfLimit): String = "app-dxf:${limit.name}"
}

/** The navigation of the "Machines" group: each callback opens a global screen. */
class AppSettingsLinks(
    val onRememberedMachines: () -> Unit = {},
    val onMachineProfiles: () -> Unit = {},
    val onKeyMapping: () -> Unit = {},
    val onLogs: () -> Unit = {},
    val onAbout: () -> Unit = {},
)

/**
 * The app settings screen (the "App" rows): the appearance, the sounds, the connection, the
 * machines, the DXF import limits, and the developer option. The composable is stateless: [state] comes from
 * [AppSettingsPresenter], and each event goes to [presenter].
 */
@Composable
fun AppSettingsScreen(
    state: AppSettingsUiState,
    presenter: AppSettingsPresenter,
    links: AppSettingsLinks,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(Res.string.route_app_settings),
        onBack = onBack,
        actions = { HelpButton(HelpTopic.AppSettings) },
    ) {
        val groups = AppSettingsGroup.entries.map { group -> SettingsGroupItem(group, stringResource(groupTitle(group))) }
        SettingsGroupLayout(groups = groups, selected = state.group, onSelect = presenter::selectGroup) { group ->
            when (group) {
                AppSettingsGroup.Appearance -> AppearanceGroup(state, presenter)
                AppSettingsGroup.Sounds -> SoundsGroup(state, presenter)
                AppSettingsGroup.Connection -> ConnectionGroup(state, presenter)
                AppSettingsGroup.Machines -> MachinesGroup(links)
                AppSettingsGroup.DxfImport -> DxfGroup(state, presenter)
                AppSettingsGroup.Developer -> DeveloperGroup(state, presenter)
            }
        }
    }
}

/** Gives the title of [group]. */
fun groupTitle(group: AppSettingsGroup): StringResource = when (group) {
    AppSettingsGroup.Appearance -> Res.string.settings_app_group_appearance
    AppSettingsGroup.Sounds -> Res.string.settings_app_group_sounds
    AppSettingsGroup.Connection -> Res.string.settings_app_group_connection
    AppSettingsGroup.Machines -> Res.string.settings_app_group_machines
    AppSettingsGroup.DxfImport -> Res.string.settings_app_group_dxf
    AppSettingsGroup.Developer -> Res.string.settings_app_group_developer
}

@Composable
private fun ColumnScope.AppearanceGroup(state: AppSettingsUiState, presenter: AppSettingsPresenter) {
    val settings = state.settings
    SettingsSection(stringResource(Res.string.settings_app_group_appearance)) {
        SettingsSegmented(
            title = stringResource(Res.string.settings_app_theme),
            summary = stringResource(Res.string.settings_app_theme_summary),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { mode ->
                stringResource(
                    when (mode) {
                        ThemeMode.System -> Res.string.settings_app_theme_system
                        ThemeMode.Dark -> Res.string.settings_app_theme_dark
                    },
                )
            },
            onSelect = presenter::setThemeMode,
            tag = AppSettingsTags.THEME,
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_app_large_buttons),
            summary = stringResource(Res.string.settings_app_large_buttons_summary),
            checked = settings.largeButtons,
            onCheckedChange = presenter::setLargeButtons,
            tag = AppSettingsTags.LARGE_BUTTONS,
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_app_full_screen),
            summary = stringResource(Res.string.settings_app_full_screen_summary),
            checked = settings.fullScreen,
            onCheckedChange = presenter::setFullScreen,
            tag = AppSettingsTags.FULL_SCREEN,
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_app_show_hints),
            summary = stringResource(Res.string.settings_app_show_hints_summary),
            checked = settings.showHints,
            onCheckedChange = presenter::setShowHints,
            tag = AppSettingsTags.SHOW_HINTS,
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_app_setup_reminder),
            summary = stringResource(Res.string.settings_app_setup_reminder_summary),
            checked = settings.showSetupReminder,
            onCheckedChange = presenter::setShowSetupReminder,
            tag = AppSettingsTags.SETUP_REMINDER,
        )
        SettingsSegmented(
            title = stringResource(Res.string.settings_app_graphical_view),
            options = GraphicalViewPlacement.entries,
            selected = settings.graphicalViewInPortrait,
            label = { placement ->
                stringResource(
                    when (placement) {
                        GraphicalViewPlacement.BelowReadout -> Res.string.settings_app_view_below
                        GraphicalViewPlacement.AboveReadout -> Res.string.settings_app_view_above
                        GraphicalViewPlacement.FullScreen -> Res.string.settings_app_view_full_screen
                    },
                )
            },
            onSelect = presenter::setGraphicalViewInPortrait,
            tag = AppSettingsTags.GRAPHICAL_VIEW,
        )
    }
}

@Composable
private fun ColumnScope.SoundsGroup(state: AppSettingsUiState, presenter: AppSettingsPresenter) {
    val settings = state.settings
    SettingsSection(stringResource(Res.string.settings_app_group_sounds)) {
        SettingsSegmented(
            title = stringResource(Res.string.settings_app_mute_scope),
            options = MuteScope.entries,
            selected = settings.muteScope,
            label = { scope ->
                stringResource(
                    when (scope) {
                        MuteScope.App -> Res.string.settings_app_mute_scope_app
                        MuteScope.EachMachine -> Res.string.settings_app_mute_scope_machine
                    },
                )
            },
            onSelect = presenter::setMuteScope,
            tag = AppSettingsTags.MUTE_SCOPE,
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_app_mute),
            summary = stringResource(Res.string.settings_app_mute_summary),
            checked = settings.muteAllSounds,
            onCheckedChange = presenter::setMuteAllSounds,
            enabled = state.appMuteApplies,
            tag = AppSettingsTags.MUTE,
        )
        if (!state.appMuteApplies) {
            SettingsNotice(stringResource(Res.string.settings_app_mute_machine_note), kind = NoticeKind.Info)
        }
    }
}

@Composable
private fun ColumnScope.ConnectionGroup(state: AppSettingsUiState, presenter: AppSettingsPresenter) {
    val settings = state.settings
    SettingsSection(stringResource(Res.string.settings_app_group_connection)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_app_wake_lock),
            summary = stringResource(Res.string.settings_app_wake_lock_summary),
            checked = settings.wakeLockWhileLinked,
            onCheckedChange = presenter::setWakeLock,
            tag = AppSettingsTags.WAKE_LOCK,
        )
        // The setting tells the negatives of the wake lock.
        SettingsNotice(stringResource(Res.string.settings_app_wake_lock_warning), kind = NoticeKind.Warning, tag = AppSettingsTags.WAKE_LOCK_WARNING)
    }
    SettingsSection(stringResource(Res.string.settings_app_link_types)) {
        LinkTypes.SHEET_TYPES.forEach { kind ->
            SettingsSwitch(
                title = stringResource(linkName(kind)),
                checked = state.linkTypeShown(kind),
                onCheckedChange = { on -> presenter.setLinkTypeShown(kind, on) },
                tag = AppSettingsTags.linkType(kind),
            )
        }
    }
    SettingsSection(stringResource(Res.string.settings_app_battery)) {
        SettingsNotice(
            text = stringResource(if (state.batteryExempt) Res.string.settings_app_battery_exempt else Res.string.settings_app_battery_not_exempt),
            kind = if (state.batteryExempt) NoticeKind.Info else NoticeKind.Warning,
            tag = AppSettingsTags.BATTERY_STATE,
        )
        SettingsButtons {
            FilledTonalButton(
                onClick = presenter::openBatteryOptimizationSettings,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AppSettingsTags.BATTERY_OPEN),
            ) { Text(stringResource(Res.string.settings_app_battery_open)) }
        }
    }
}

@Composable
private fun ColumnScope.MachinesGroup(links: AppSettingsLinks) {
    SettingsSection(stringResource(Res.string.settings_app_group_machines)) {
        SettingsLink(
            title = stringResource(Res.string.route_remembered_machines),
            summary = stringResource(Res.string.settings_app_remembered_summary),
            onClick = links.onRememberedMachines,
            tag = "app-link-remembered",
        )
        SettingsLink(
            title = stringResource(Res.string.route_machine_profiles),
            summary = stringResource(Res.string.settings_app_profiles_summary),
            onClick = links.onMachineProfiles,
            tag = "app-link-profiles",
        )
        SettingsLink(
            title = stringResource(Res.string.route_key_mapping),
            summary = stringResource(Res.string.settings_app_keys_summary),
            onClick = links.onKeyMapping,
            tag = "app-link-keys",
        )
        SettingsLink(
            title = stringResource(Res.string.route_logs),
            summary = stringResource(Res.string.settings_app_logs_summary),
            onClick = links.onLogs,
            tag = "app-link-logs",
        )
        SettingsLink(
            title = stringResource(Res.string.route_about),
            summary = stringResource(Res.string.settings_app_about_summary),
            onClick = links.onAbout,
            tag = "app-link-about",
        )
    }
}

@Composable
private fun ColumnScope.DxfGroup(state: AppSettingsUiState, presenter: AppSettingsPresenter) {
    SettingsSection(stringResource(Res.string.settings_app_group_dxf)) {
        SettingsNote(stringResource(Res.string.settings_app_dxf_note))
        DxfLimit.entries.forEach { limit ->
            SettingsTextField(
                title = stringResource(dxfTitle(limit)),
                value = state.dxfTexts[limit].orEmpty(),
                onValueChange = { text -> presenter.setDxfText(limit, text) },
                error = state.dxfErrors[limit]?.resolve(),
                suffix = if (limit == DxfLimit.CurveTolerance) stringResource(Res.string.readout_unit_mm) else null,
                keyboardOptions = KeyboardOptions(keyboardType = if (limit == DxfLimit.CurveTolerance) KeyboardType.Decimal else KeyboardType.Number),
                tag = AppSettingsTags.dxf(limit),
            )
        }
        SettingsButtons {
            OutlinedButton(
                onClick = presenter::resetDxfLimits,
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AppSettingsTags.DXF_RESET),
            ) { Text(stringResource(Res.string.settings_app_dxf_reset)) }
        }
    }
}

@Composable
private fun ColumnScope.DeveloperGroup(state: AppSettingsUiState, presenter: AppSettingsPresenter) {
    SettingsSection(stringResource(Res.string.settings_app_group_developer)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_app_get_sig),
            summary = stringResource(Res.string.settings_app_get_sig_summary),
            checked = state.settings.sendGetSigOnce,
            onCheckedChange = presenter::setSendGetSigOnce,
            tag = AppSettingsTags.GET_SIG,
        )
    }
}

/** Gives the title of the field of [limit]. */
fun dxfTitle(limit: DxfLimit): StringResource = when (limit) {
    DxfLimit.SourceBytes -> Res.string.settings_app_dxf_source_bytes
    DxfLimit.Entities -> Res.string.settings_app_dxf_entities
    DxfLimit.BezierSegments -> Res.string.settings_app_dxf_bezier
    DxfLimit.BlockLevels -> Res.string.settings_app_dxf_blocks
    DxfLimit.ParseTimeMs -> Res.string.settings_app_dxf_time
    DxfLimit.CurveTolerance -> Res.string.settings_app_dxf_tolerance
    DxfLimit.PolylineWarningPoints -> Res.string.settings_app_dxf_polyline
    DxfLimit.SplineControlPoints -> Res.string.settings_app_dxf_spline
}
