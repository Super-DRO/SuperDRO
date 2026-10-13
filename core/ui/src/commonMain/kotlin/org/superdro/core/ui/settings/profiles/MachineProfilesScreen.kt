package org.superdro.core.ui.settings.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.MachineType
import org.superdro.core.resources.Res
import org.superdro.core.resources.add_device_units_inch
import org.superdro.core.resources.add_device_units_mm
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.ic_more_vert
import org.superdro.core.resources.route_machine_profiles
import org.superdro.core.resources.route_machine_settings
import org.superdro.core.resources.settings_cancel
import org.superdro.core.resources.settings_delete
import org.superdro.core.resources.settings_profiles_actions
import org.superdro.core.resources.settings_profiles_change_type
import org.superdro.core.resources.settings_profiles_copy
import org.superdro.core.resources.settings_profiles_copy_note
import org.superdro.core.resources.settings_profiles_copy_title
import org.superdro.core.resources.settings_profiles_create
import org.superdro.core.resources.settings_profiles_create_title
import org.superdro.core.resources.settings_profiles_empty
import org.superdro.core.resources.settings_profiles_name
import org.superdro.core.resources.settings_profiles_new
import org.superdro.core.resources.settings_profiles_online
import org.superdro.core.resources.settings_profiles_rename
import org.superdro.core.resources.settings_profiles_rename_title
import org.superdro.core.resources.settings_profiles_save
import org.superdro.core.resources.settings_profiles_summary
import org.superdro.core.resources.settings_profiles_type_continue
import org.superdro.core.resources.settings_profiles_type_title
import org.superdro.core.ui.StatusChip
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.settings.components.SettingsButtons
import org.superdro.core.ui.settings.components.SettingsColumn
import org.superdro.core.ui.settings.components.SettingsNote
import org.superdro.core.ui.settings.help.HelpButton
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.settings.typechange.MachineTypeChangeDialog
import org.superdro.core.ui.tabs.StatusRoles
import org.superdro.core.ui.tabs.TabStatus
import org.superdro.core.ui.tabs.TabStatusIcon
import org.superdro.core.ui.tabs.adddevice.MachineSetupForm
import org.superdro.core.ui.tabs.adddevice.machineTypeLabel
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.accentTextFieldColors

/** The test tags of the machine profiles screen. */
object ProfilesTags {
    const val NEW = "profiles-new"
    const val CREATE_SHEET = "profiles-create-sheet"
    const val CREATE_NAME = "profiles-create-name"
    const val CREATE_CONFIRM = "profiles-create-confirm"
    const val NAME_FIELD = "profiles-name-field"
    const val NAME_CONFIRM = "profiles-name-confirm"
    const val TYPE_CONTINUE = "profiles-type-continue"

    /** The tag of the row of [id]. */
    fun row(id: String): String = "profile:$id"

    /** The tag of the menu button of [id]. */
    fun menu(id: String): String = "profile-menu:$id"

    /** The tag of the menu item [item] of the open menu. */
    fun item(item: String): String = "profile-item:$item"

    /** The tag of the type option [type] of the type choice. */
    fun type(type: MachineType): String = "profile-type:${type.name}"
}

/** The events of the screen that leave it: the delete question and the machine settings of a profile. */
class MachineProfilesNavigation(
    /** Opens the delete question of the profile. */
    val onDelete: (String) -> Unit = {},
    /** Opens the machine settings of the profile: in its tab, or with no tab when the profile has no tab. */
    val onMachineSettings: (String) -> Unit = {},
)

/**
 * The machine profiles screen. Each row shows
 * the name, the machine type, the unit, and the state "Online", with an action menu: Machine
 * settings, Rename, Copy, Machine type, and Delete. "New profile"
 * opens the create sheet with the machine setup.
 */
@Composable
fun MachineProfilesScreen(
    state: MachineProfilesUiState,
    presenter: MachineProfilesPresenter,
    navigation: MachineProfilesNavigation,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(Res.string.route_machine_profiles),
        onBack = onBack,
        actions = { HelpButton(HelpTopic.MachineProfiles) },
    ) {
        SettingsColumn {
            SettingsButtons {
                FilledTonalButton(
                    onClick = presenter::openCreate,
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(ProfilesTags.NEW),
                ) {
                    Icon(painterResource(Res.drawable.ic_add), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.settings_profiles_new))
                }
            }
            if (state.loaded && state.rows.isEmpty()) SettingsNote(stringResource(Res.string.settings_profiles_empty))
            state.rows.forEach { row ->
                ProfileRow(
                    row = row,
                    menuOpen = state.menuFor == row.id,
                    presenter = presenter,
                    navigation = navigation,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    when (val dialog = state.dialog) {
        is ProfileDialogUiState.Create -> CreateProfileSheet(dialog, presenter)
        is ProfileDialogUiState.Copy -> NameDialog(
            title = stringResource(Res.string.settings_profiles_copy_title, dialog.sourceName),
            note = stringResource(Res.string.settings_profiles_copy_note),
            name = dialog.name,
            error = dialog.error,
            busy = dialog.busy,
            confirm = stringResource(Res.string.settings_profiles_copy),
            onName = presenter::setCopyName,
            onConfirm = presenter::confirmCopy,
            onDismiss = presenter::closeDialog,
        )
        is ProfileDialogUiState.Rename -> NameDialog(
            title = stringResource(Res.string.settings_profiles_rename_title, dialog.oldName),
            note = null,
            name = dialog.name,
            error = dialog.error,
            busy = dialog.busy,
            confirm = stringResource(Res.string.settings_profiles_save),
            onName = presenter::setRenameName,
            onConfirm = presenter::confirmRename,
            onDismiss = presenter::closeDialog,
        )
        is ProfileDialogUiState.ChooseType -> ChooseTypeDialog(dialog, presenter)
        null -> Unit
    }
    state.typeChange?.let { change ->
        MachineTypeChangeDialog(
            state = change,
            onDisposal = presenter.typeChange::setDisposal,
            onConfirm = presenter.typeChange::confirm,
            onDismiss = presenter.typeChange::cancel,
        )
    }
}

@Composable
private fun ProfileRow(
    row: ProfileRowUiState,
    menuOpen: Boolean,
    presenter: MachineProfilesPresenter,
    navigation: MachineProfilesNavigation,
) {
    val target = LocalTouchTargets.current.button
    val type = stringResource(machineTypeLabel(row.machineType))
    val unit = stringResource(if (row.metric) Res.string.add_device_units_mm else Res.string.add_device_units_inch)
    ListItem(
        headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(stringResource(Res.string.settings_profiles_summary, type, unit)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.online) {
                    val online = stringResource(Res.string.settings_profiles_online)
                    StatusChip(TabStatus(TabStatusIcon.Connected, UiText.Plain(online), StatusRoles.Connected), online)
                    Spacer(Modifier.width(8.dp))
                }
                Box {
                    IconButton(
                        onClick = { presenter.openMenu(row.id) },
                        modifier = Modifier.size(target).testTag(ProfilesTags.menu(row.id)),
                    ) {
                        Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = stringResource(Res.string.settings_profiles_actions, row.name))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = presenter::closeMenu,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        MenuItem(stringResource(Res.string.route_machine_settings), "settings") {
                            presenter.closeMenu()
                            navigation.onMachineSettings(row.id)
                        }
                        MenuItem(stringResource(Res.string.settings_profiles_rename), "rename") { presenter.openRename(row.id) }
                        MenuItem(stringResource(Res.string.settings_profiles_copy), "copy") { presenter.openCopy(row.id) }
                        MenuItem(stringResource(Res.string.settings_profiles_change_type), "type") { presenter.openChangeType(row.id) }
                        MenuItem(stringResource(Res.string.settings_delete), "delete") {
                            presenter.closeMenu()
                            navigation.onDelete(row.id)
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = target).testTag(ProfilesTags.row(row.id)),
    )
}

@Composable
private fun MenuItem(text: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(ProfilesTags.item(tag)),
    )
}

/** The create sheet: the name and the machine setup. */
@Composable
private fun CreateProfileSheet(dialog: ProfileDialogUiState.Create, presenter: MachineProfilesPresenter) {
    val target = LocalTouchTargets.current.button
    AdaptiveSheet(
        onDismissRequest = presenter::closeDialog,
        title = stringResource(Res.string.settings_profiles_create_title),
        modifier = Modifier.testTag(ProfilesTags.CREATE_SHEET),
    ) {
        OutlinedTextField(
            value = dialog.name,
            onValueChange = presenter::setCreateName,
            label = { Text(stringResource(Res.string.settings_profiles_name)) },
            singleLine = true,
            isError = dialog.nameError != null,
            supportingText = dialog.nameError?.let { error -> { Text(error.resolve()) } },
            colors = accentTextFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag(ProfilesTags.CREATE_NAME),
        )
        MachineSetupForm(
            setup = dialog.setup,
            setupError = dialog.setupError,
            conflicts = dialog.conflicts,
            onUpdate = presenter::updateCreateSetup,
        )
        SettingsButtons {
            TextButton(onClick = presenter::closeDialog, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target)) {
                Text(stringResource(Res.string.settings_cancel))
            }
            FilledTonalButton(
                onClick = presenter::confirmCreate,
                enabled = !dialog.busy,
                modifier = Modifier.heightIn(min = target).testTag(ProfilesTags.CREATE_CONFIRM),
            ) { Text(stringResource(Res.string.settings_profiles_create)) }
        }
    }
}

/** A dialog with one name field: the copy and the rename. */
@Composable
private fun NameDialog(
    title: String,
    note: String?,
    name: String,
    error: UiText?,
    busy: Boolean,
    confirm: String,
    onName: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = LocalTouchTargets.current.button
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(Res.string.settings_profiles_name)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { text -> { Text(text.resolve()) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = accentTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag(ProfilesTags.NAME_FIELD),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = target).testTag(ProfilesTags.NAME_CONFIRM),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target)) {
                Text(stringResource(Res.string.settings_cancel))
            }
        },
    )
}

/** The choice of the new machine type. "Continue" opens the confirmation. */
@Composable
private fun ChooseTypeDialog(dialog: ProfileDialogUiState.ChooseType, presenter: MachineProfilesPresenter) {
    val target = LocalTouchTargets.current.button
    AlertDialog(
        onDismissRequest = presenter::closeDialog,
        title = { Text(stringResource(Res.string.settings_profiles_type_title, dialog.name)) },
        text = {
            Column {
                listOf(MachineType.VerticalMill, MachineType.Lathe, MachineType.HorizontalMill).forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = target)
                            .testTag(ProfilesTags.type(type))
                            .clickable(role = Role.RadioButton) { presenter.selectType(type) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == dialog.selected, onClick = null, colors = accentRadioColors())
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(machineTypeLabel(type)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = presenter::confirmType,
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = target).testTag(ProfilesTags.TYPE_CONTINUE),
            ) { Text(stringResource(Res.string.settings_profiles_type_continue)) }
        },
        dismissButton = {
            TextButton(onClick = presenter::closeDialog, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target)) {
                Text(stringResource(Res.string.settings_cancel))
            }
        },
    )
}
