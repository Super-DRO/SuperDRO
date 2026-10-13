package org.superdro.core.ui.settings.typechange

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.MachineType
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_cancel
import org.superdro.core.resources.settings_type_all_fits
import org.superdro.core.resources.settings_type_archive
import org.superdro.core.resources.settings_type_archive_summary
import org.superdro.core.resources.settings_type_confirm
import org.superdro.core.resources.settings_type_conversion_angle
import org.superdro.core.resources.settings_type_conversion_diameter
import org.superdro.core.resources.settings_type_conversion_mill_tools
import org.superdro.core.resources.settings_type_conversion_mode
import org.superdro.core.resources.settings_type_conversion_plane
import org.superdro.core.resources.settings_type_conversion_restore
import org.superdro.core.resources.settings_type_conversion_title
import org.superdro.core.resources.settings_type_delete
import org.superdro.core.resources.settings_type_delete_summary
import org.superdro.core.resources.settings_type_done
import org.superdro.core.resources.settings_type_from_to
import org.superdro.core.resources.settings_type_mismatch_guides
import org.superdro.core.resources.settings_type_mismatch_machine_datums
import org.superdro.core.resources.settings_type_mismatch_sub_datums
import org.superdro.core.resources.settings_type_mismatch_threads
import org.superdro.core.resources.settings_type_mismatch_title
import org.superdro.core.resources.settings_type_mismatch_tools
import org.superdro.core.resources.settings_type_title
import org.superdro.core.resources.settings_type_warning
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.ConversionKind
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.MachineTypeChangePlan
import org.superdro.core.store.repository.Mismatch
import org.superdro.core.store.repository.MismatchDisposal
import org.superdro.core.store.repository.MismatchKind
import org.superdro.core.ui.settings.components.NoticeKind
import org.superdro.core.ui.settings.components.SettingsNotice
import org.superdro.core.ui.tabs.adddevice.machineTypeLabel
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors

/**
 * The question of a machine type change.
 *
 * @property plan The confirmation list of `MachineRepository.planMachineTypeChange`: the data that
 *   does not fit the new type, and the changes that SuperDRO makes.
 * @property disposal What the store does with the data that does not fit: archive (the default)
 *   or delete.
 */
@Immutable
data class MachineTypeChangeUiState(
    val profileId: String,
    val profileName: String,
    val plan: MachineTypeChangePlan,
    val disposal: MismatchDisposal = MismatchDisposal.Archive,
    val busy: Boolean = false,
) {
    /** The texts of the data that does not fit, one line for each kind. */
    val mismatchTexts: List<UiText> get() = plan.mismatches.map(::mismatchText)

    /** The texts of the changes that SuperDRO makes. */
    val conversionTexts: List<UiText> get() = plan.conversions.map(::conversionText)
}

/**
 * The machine type change of a profile. SuperDRO permits the change also while
 * the profile has tools or sub-datums, with a warning: a confirmation lists the
 * data that does not fit the new type. The store converts what it can and archives or deletes the
 * rest in one transaction.
 *
 * The machine settings and the machine profiles screen use this delegate. Its presenter gives the
 * [scope]. [onDone] gets the text of the result for the snackbar.
 */
class MachineTypeChange(
    private val machines: MachineRepository,
    private val scope: CoroutineScope,
    private val onDone: (UiText) -> Unit = {},
) {
    private val mutable = MutableStateFlow<MachineTypeChangeUiState?>(null)

    /** The open question, or null when no question shows. */
    val state: StateFlow<MachineTypeChangeUiState?> = mutable.asStateFlow()

    /** Asks for the change of the profile [profileId] to [to]. The same type does nothing. */
    fun ask(profileId: String, to: MachineType) {
        scope.launch {
            val profile = machines.loadProfile(profileId) ?: return@launch
            if (profile.machineType == to) return@launch
            val plan = machines.planMachineTypeChange(profileId, to) ?: return@launch
            mutable.value = MachineTypeChangeUiState(profileId, profile.name, plan)
        }
    }

    /** Selects what the store does with the data that does not fit. */
    fun setDisposal(disposal: MismatchDisposal) {
        mutable.update { current -> current?.copy(disposal = disposal) }
    }

    /** Closes the question with no change. */
    fun cancel() {
        mutable.value = null
    }

    /** Changes the machine type, and closes the question. */
    fun confirm() {
        val current = mutable.value ?: return
        if (current.busy) return
        mutable.value = current.copy(busy = true)
        scope.launch {
            val result = machines.changeMachineType(current.profileId, current.plan.to, current.disposal)
            mutable.value = null
            when (result) {
                is StoreResult.Ok -> onDone(doneText(current.profileName, result.value.to))
                is StoreResult.Refused -> onDone(result.refusal.toUiText())
            }
        }
    }

    companion object {
        /** The text of the result: "The machine type of <name> is now <type>." */
        fun doneText(name: String, type: MachineType): UiText =
            UiText.of(Res.string.settings_type_done, name, UiText.of(machineTypeLabel(type)))
    }
}

/** Gives the line of one kind of data that does not fit. */
fun mismatchText(mismatch: Mismatch): UiText = UiText.of(
    when (mismatch.kind) {
        MismatchKind.ProfileTools -> Res.string.settings_type_mismatch_tools
        MismatchKind.SubDatumYCoordinates -> Res.string.settings_type_mismatch_sub_datums
        MismatchKind.YGuides -> Res.string.settings_type_mismatch_guides
        MismatchKind.MachineDatumYCoordinates -> Res.string.settings_type_mismatch_machine_datums
        MismatchKind.LatheThreads -> Res.string.settings_type_mismatch_threads
    },
    mismatch.count,
)

/** Gives the line of one change that SuperDRO makes. */
fun conversionText(conversion: ConversionKind): UiText = UiText.of(
    when (conversion) {
        ConversionKind.SharedMillTools -> Res.string.settings_type_conversion_mill_tools
        ConversionKind.WorkspacePlane -> Res.string.settings_type_conversion_plane
        ConversionKind.DiameterMode -> Res.string.settings_type_conversion_diameter
        ConversionKind.MachineMode -> Res.string.settings_type_conversion_mode
        ConversionKind.PatternAngleConvention -> Res.string.settings_type_conversion_angle
        ConversionKind.RestoreFromArchive -> Res.string.settings_type_conversion_restore
    },
)

/** The test tag of the confirm button of the type change. */
const val TYPE_CHANGE_CONFIRM_TAG: String = "type-change-confirm"

/**
 * The confirmation of a machine type change: the warning, the list of the data that does
 * not fit, the changes that SuperDRO makes, and the choice "Archive" or "Delete" for the data that
 * does not fit.
 */
@Composable
fun MachineTypeChangeDialog(
    state: MachineTypeChangeUiState,
    onDisposal: (MismatchDisposal) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = LocalTouchTargets.current.button
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_type_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        Res.string.settings_type_from_to,
                        state.profileName,
                        stringResource(machineTypeLabel(state.plan.from)),
                        stringResource(machineTypeLabel(state.plan.to)),
                    ),
                )
                if (state.plan.mismatches.isEmpty()) {
                    SettingsNotice(stringResource(Res.string.settings_type_all_fits), kind = NoticeKind.Info)
                } else {
                    SettingsNotice(stringResource(Res.string.settings_type_warning), kind = NoticeKind.Warning, tag = "type-change-warning")
                    ListTitle(stringResource(Res.string.settings_type_mismatch_title))
                    state.mismatchTexts.forEach { line -> Bullet(line.resolve()) }
                }
                if (state.plan.conversions.isNotEmpty()) {
                    ListTitle(stringResource(Res.string.settings_type_conversion_title))
                    state.conversionTexts.forEach { line -> Bullet(line.resolve()) }
                }
                if (state.plan.mismatches.isNotEmpty()) {
                    Spacer(Modifier.heightIn(min = 8.dp))
                    DisposalRow(
                        title = stringResource(Res.string.settings_type_archive),
                        summary = stringResource(Res.string.settings_type_archive_summary),
                        selected = state.disposal == MismatchDisposal.Archive,
                        onClick = { onDisposal(MismatchDisposal.Archive) },
                    )
                    DisposalRow(
                        title = stringResource(Res.string.settings_type_delete),
                        summary = stringResource(Res.string.settings_type_delete_summary),
                        selected = state.disposal == MismatchDisposal.Delete,
                        onClick = { onDisposal(MismatchDisposal.Delete) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.busy,
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = target).testTag(TYPE_CHANGE_CONFIRM_TAG),
            ) { Text(stringResource(Res.string.settings_type_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target)) {
                Text(stringResource(Res.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun ListTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Bullet(text: String) {
    Text("• $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
}

@Composable
private fun DisposalRow(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = accentRadioColors())
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
