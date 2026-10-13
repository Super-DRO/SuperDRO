package org.superdro.core.ui.adapter.probe

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_edit
import org.superdro.core.resources.probelib_selector_edit
import org.superdro.core.resources.probelib_selector_new
import org.superdro.core.resources.probelib_selector_none
import org.superdro.core.resources.probelib_selector_none_hint
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.theme.accentTextFieldColors

/** One profile of the probe selector. [editable] is false for a standard tip. */
@Immutable
data class ProbeOption(val id: String, val name: String, val type: ProbeType, val editable: Boolean)

/**
 * The state of the probe selector of one function.
 *
 * @property options The accepted profiles: the last used first, then by name.
 * @property selectedId The selected profile, or null. A selection that is not in the library is null.
 * @property showNoSelection True when the list starts with "No probe selected": only when no
 *   profile is selected.
 * @property canEdit True when the selected profile is an own profile, not a standard tip.
 */
@Immutable
data class ProbeSelectorUiState(
    val function: ProbeFunction,
    val options: List<ProbeOption> = emptyList(),
    val selectedId: String? = null,
    val loaded: Boolean = false,
) {
    val selected: ProbeOption? get() = options.firstOrNull { option -> option.id == selectedId }
    val showNoSelection: Boolean get() = selected == null
    val canEdit: Boolean get() = selected?.editable == true
}

/**
 * The probe selector of one function of the machine profile [device]. A presenter of the
 * function makes it in its own scope. The probe library, the edge finder, the Z reference, and the
 * measurement instrument use it. The presenters of the graphics area give it their own
 * repositories ([MachineRepository], [ProbeRepository]) and clock.
 *
 * Rules:
 * - The list has only the accepted profiles of [function], from the probe library of the machine
 *   profile (its own profiles and the profiles for all profiles).
 * - A selection writes the ID into [MachineProfile.probeRoles] and records the time of use.
 * - "New profile…" opens a new probe profile. The first new accepted profile that comes into the
 *   library after the request becomes the selection.
 */
class ProbeSelectorDelegate(
    private val machines: MachineRepository,
    private val probes: ProbeRepository,
    private val clock: Clock,
    private val device: DeviceId,
    val function: ProbeFunction,
    private val scope: CoroutineScope,
) {

    /** Makes the selector with the repositories and the wall clock of the adapter area [deps]. */
    constructor(deps: AdapterUiDeps, device: DeviceId, function: ProbeFunction, scope: CoroutineScope) :
        this(deps.machines, deps.probes, deps.wallClock, device, function, scope)
    private val mutable = MutableStateFlow(ProbeSelectorUiState(function))

    /** The state of the selector. */
    val state: StateFlow<ProbeSelectorUiState> = mutable.asStateFlow()

    private var known: Set<String>? = null
    private var pendingNew: Boolean = false
    private var library: List<ProbeProfile> = emptyList()

    init {
        scope.launch {
            combine(machines.profile(device.value), probes.library(device.value)) { machine, profiles -> machine to profiles }
                .collect { (machine, profiles) -> onLibrary(machine, profiles) }
        }
    }

    /** Selects the profile [id] for the function. Null clears the selection. */
    fun select(id: String?) {
        scope.launch { write(id) }
    }

    /**
     * The user selected "New profile…". The caller opens the new probe profile screen. The next
     * new accepted profile becomes the selection.
     */
    fun requestNewProfile() {
        known = library.mapTo(HashSet()) { profile -> profile.id }
        pendingNew = true
    }

    private suspend fun onLibrary(machine: MachineProfile?, profiles: List<ProbeProfile>) {
        library = profiles
        val accepted = profiles.filter(function::accepts).sortedForSelector()
        val stored = machine?.let { profile -> function.selected(profile.probeRoles) }
        val selectedId = stored?.takeIf { id -> accepted.any { profile -> profile.id == id } }
        mutable.value = ProbeSelectorUiState(
            function = function,
            options = accepted.map { profile -> ProbeOption(profile.id, profile.name, profile.type, editable = !profile.standardTip) },
            selectedId = selectedId,
            loaded = machine != null,
        )
        val before = known
        if (pendingNew && before != null) {
            val created = accepted.firstOrNull { profile -> profile.id !in before }
            if (created != null) {
                pendingNew = false
                known = null
                write(created.id)
            }
        }
    }

    private suspend fun write(id: String?) {
        machines.updateProfile(device.value) { profile -> profile.copy(probeRoles = function.withSelection(profile.probeRoles, id)) }
        if (id != null) probes.markUsed(id, clock.now())
    }
}

/** The test tag of the field of the probe selector of [function]. */
fun probeSelectorTag(function: ProbeFunction): String = "probe-selector-${function.name}"

/** The test tag of the menu item of the profile [id], or of "New profile…" for null. */
fun probeOptionTag(function: ProbeFunction, id: String?): String = "probe-option-${function.name}-${id ?: "new"}"

/**
 * The probe selector of one function: a drop-down field with "No probe selected" (only
 * with no selection), the accepted profiles, and "New profile…", and an edit button for an own
 * profile. The composable is stateless except for the open state of the menu.
 */
@Composable
fun ProbeSelector(
    state: ProbeSelectorUiState,
    onSelect: (String?) -> Unit,
    onNewProfile: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(Res.string.probelib_selector_none)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { open -> expanded = open }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = state.selected?.name ?: none,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(state.function.label)) },
                supportingText = if (state.selected == null) {
                    { Text(stringResource(Res.string.probelib_selector_none_hint)) }
                } else {
                    null
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = accentTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag(probeSelectorTag(state.function)),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (state.showNoSelection) {
                    DropdownMenuItem(
                        text = { Text(none) },
                        onClick = {
                            expanded = false
                            onSelect(null)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                state.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            expanded = false
                            onSelect(option.id)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(probeOptionTag(state.function, option.id)),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.probelib_selector_new), color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        expanded = false
                        onNewProfile()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(probeOptionTag(state.function, null)),
                )
            }
        }
        val selected = state.selected
        if (selected != null && selected.editable) {
            IconButton(onClick = { onEdit(selected.id) }, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(Res.drawable.ic_edit), contentDescription = stringResource(Res.string.probelib_selector_edit))
            }
        }
    }
}
