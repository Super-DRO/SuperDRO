package org.superdro.core.ui.settings.profiles

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_profiles_copied
import org.superdro.core.resources.settings_profiles_created
import org.superdro.core.resources.settings_profiles_renamed
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.settings.typechange.MachineTypeChange
import org.superdro.core.ui.settings.typechange.MachineTypeChangeUiState
import org.superdro.core.ui.tabs.adddevice.MachineSetup
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** One row of the machine profiles screen. [online] is true while the session of the profile is online. */
@Immutable
data class ProfileRowUiState(
    val id: String,
    val name: String,
    val machineType: MachineType,
    val metric: Boolean,
    val online: Boolean,
)

/** The open dialog of the machine profiles screen, or none. */
sealed interface ProfileDialogUiState {

    /**
     * The create sheet: the name and the machine setup. While the user did not
     * type a name ([nameEdited] false), the name follows the machine type ("Mill" or "Lathe").
     */
    @Immutable
    data class Create(
        val name: String,
        val nameEdited: Boolean = false,
        val setup: MachineSetup = MachineSetup(),
        val nameError: UiText? = null,
        val setupError: UiText? = null,
        val conflicts: List<UiText> = emptyList(),
        val busy: Boolean = false,
    ) : ProfileDialogUiState

    /** The copy dialog: the name of the copy. */
    @Immutable
    data class Copy(
        val sourceId: String,
        val sourceName: String,
        val name: String,
        val error: UiText? = null,
        val busy: Boolean = false,
    ) : ProfileDialogUiState

    /** The rename dialog. */
    @Immutable
    data class Rename(
        val id: String,
        val oldName: String,
        val name: String,
        val error: UiText? = null,
        val busy: Boolean = false,
    ) : ProfileDialogUiState

    /** The choice of the new machine type. The confirmation comes after it. */
    @Immutable
    data class ChooseType(
        val id: String,
        val name: String,
        val current: MachineType,
        val selected: MachineType,
    ) : ProfileDialogUiState
}

/** One message of the screen for the snackbar. [id] keeps two equal texts apart. */
@Immutable
data class ProfilesMessage(val id: Long, val text: UiText)

/**
 * The state of the machine profiles screen.
 *
 * @property menuFor The profile whose action menu is open, or null.
 * @property typeChange The confirmation of a machine type change, or null.
 */
@Immutable
data class MachineProfilesUiState(
    val loaded: Boolean = false,
    val rows: List<ProfileRowUiState> = emptyList(),
    val menuFor: String? = null,
    val dialog: ProfileDialogUiState? = null,
    val typeChange: MachineTypeChangeUiState? = null,
    val message: ProfilesMessage? = null,
)

/**
 * The names of the profiles: unique, and the case does not matter (as the store rule).
 */
object ProfileNames {

    /**
     * Gives [base] when no profile of [taken] has it. Else gives "<base> 2", then "<base> 3". With
     * [alwaysNumber], the first proposal is "<base> 2" (the name of a copy).
     */
    fun unique(base: String, taken: Collection<String>, alwaysNumber: Boolean = false): String {
        val start = base.trim()
        val lower = taken.mapTo(HashSet()) { name -> name.trim().lowercase() }
        if (!alwaysNumber && start.lowercase() !in lower) return start
        var number = 2
        while (true) {
            val candidate = "$start $number"
            if (candidate.lowercase() !in lower) return candidate
            number++
        }
    }

    /** The default name of a new profile of [type]: "Mill" or "Lathe". */
    fun defaultName(type: MachineType): String =
        if (type.isLathe) MachineProfile.DEFAULT_LATHE_NAME else MachineProfile.DEFAULT_MILL_NAME
}

/**
 * The presenter of the machine profiles screen.
 * Each profile is one machine. The screen lists the profiles and creates, copies, renames,
 * and deletes them. The delete question is its own dialog ([DeleteProfilePresenter]). The machine
 * type change uses the delegate [MachineTypeChange] with the warning and the list of the changed settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineProfilesPresenter(
    private val machines: MachineRepository,
    private val connections: Connections,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private data class Local(
        val menuFor: String? = null,
        val dialog: ProfileDialogUiState? = null,
        val message: ProfilesMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** The machine type change. The screen shows its dialog while its state is not null. */
    val typeChange: MachineTypeChange = MachineTypeChange(machines, scope, onDone = ::showMessage)

    private val profiles: StateFlow<List<MachineProfile>?> = machines.profiles().stateIn(scope, SharingStarted.Eagerly, null)

    private val online: Flow<Set<String>> = connections.sessions.flatMapLatest { sessions ->
        if (sessions.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(sessions.map { (id, session) -> session.state.map { state -> id.value to state.isOnline } }) { pairs ->
                pairs.filter { (_, isOnline) -> isOnline }.mapTo(HashSet()) { (id, _) -> id }
            }
        }
    }

    /** The state of the screen. */
    val uiState: StateFlow<MachineProfilesUiState> = combine(profiles, online, local, typeChange.state) { list, onlineIds, current, change ->
        MachineProfilesUiState(
            loaded = list != null,
            rows = list.orEmpty().map { profile ->
                ProfileRowUiState(profile.id, profile.name, profile.machineType, profile.metricDefault, profile.id in onlineIds)
            },
            menuFor = current.menuFor,
            dialog = current.dialog,
            typeChange = change,
            message = current.message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, MachineProfilesUiState())

    private val names: List<String> get() = profiles.value.orEmpty().map { profile -> profile.name }

    private fun profileOf(id: String): MachineProfile? = profiles.value?.firstOrNull { profile -> profile.id == id }

    /** Opens the action menu of [id]. */
    fun openMenu(id: String) = local.update { old -> old.copy(menuFor = id) }

    /** Closes the action menu. */
    fun closeMenu() = local.update { old -> old.copy(menuFor = null) }

    /** Closes the open dialog with no change. */
    fun closeDialog() = local.update { old -> old.copy(dialog = null) }

    // Create

    /** Opens the create sheet with the defaults of a vertical mill. */
    fun openCreate() {
        val setup = MachineSetup()
        local.update { old ->
            old.copy(
                menuFor = null,
                dialog = ProfileDialogUiState.Create(
                    name = ProfileNames.unique(ProfileNames.defaultName(setup.machineType), names),
                    setup = setup,
                    conflicts = setup.conflicts(),
                ),
            )
        }
    }

    /** The user typed the name of the new profile. */
    fun setCreateName(text: String) = updateCreate { old -> old.copy(name = text, nameEdited = true, nameError = null) }

    /** Changes the answers of the machine setup. The input conflict check runs again. */
    fun updateCreateSetup(transform: (MachineSetup) -> MachineSetup) = updateCreate { old ->
        val setup = transform(old.setup)
        val name = if (!old.nameEdited && setup.machineType.isLathe != old.setup.machineType.isLathe) {
            ProfileNames.unique(ProfileNames.defaultName(setup.machineType), names)
        } else {
            old.name
        }
        old.copy(setup = setup, name = name, setupError = null, conflicts = setup.conflicts())
    }

    /**
     * Writes the new profile with the default settings and the answers of the setup. The refusals: the setup rules ("At least one output axis must be enabled", "Primary
     * and secondary inputs can't use the same prefix") and the name rules of the store.
     */
    fun confirmCreate() {
        val current = local.value.dialog as? ProfileDialogUiState.Create ?: return
        if (current.busy) return
        current.setup.refusal()?.let { refusal ->
            updateCreate { old -> old.copy(setupError = refusal) }
            return
        }
        updateCreate { old -> old.copy(busy = true) }
        scope.launch {
            when (val result = machines.createProfile(current.setup.toProfile(current.name))) {
                is StoreResult.Ok -> {
                    closeDialog()
                    showMessage(UiText.of(Res.string.settings_profiles_created, result.value.name))
                }
                is StoreResult.Refused -> updateCreate { old -> old.copy(busy = false, nameError = result.refusal.toUiText()) }
            }
        }
    }

    private fun updateCreate(transform: (ProfileDialogUiState.Create) -> ProfileDialogUiState.Create) =
        local.update { old -> (old.dialog as? ProfileDialogUiState.Create)?.let { dialog -> old.copy(dialog = transform(dialog)) } ?: old }

    // Copy

    /** Opens the copy dialog of [id] with a unique name: "<name> 2", "<name> 3". */
    fun openCopy(id: String) {
        val profile = profileOf(id) ?: return
        local.update { old ->
            old.copy(
                menuFor = null,
                dialog = ProfileDialogUiState.Copy(profile.id, profile.name, ProfileNames.unique(profile.name, names, alwaysNumber = true)),
            )
        }
    }

    /** The user typed the name of the copy. */
    fun setCopyName(text: String) = local.update { old ->
        (old.dialog as? ProfileDialogUiState.Copy)?.let { dialog -> old.copy(dialog = dialog.copy(name = text, error = null)) } ?: old
    }

    /** Makes the copy: the settings and the tools, not the workspaces. */
    fun confirmCopy() {
        val current = local.value.dialog as? ProfileDialogUiState.Copy ?: return
        if (current.busy) return
        local.update { old -> old.copy(dialog = current.copy(busy = true)) }
        scope.launch {
            when (val result = machines.copyProfile(current.sourceId, current.name)) {
                is StoreResult.Ok -> {
                    closeDialog()
                    showMessage(UiText.of(Res.string.settings_profiles_copied, result.value.name, current.sourceName))
                }
                is StoreResult.Refused -> local.update { old ->
                    old.copy(dialog = current.copy(busy = false, error = result.refusal.toUiText()))
                }
            }
        }
    }

    // Rename

    /** Opens the rename dialog of [id]. */
    fun openRename(id: String) {
        val profile = profileOf(id) ?: return
        local.update { old -> old.copy(menuFor = null, dialog = ProfileDialogUiState.Rename(profile.id, profile.name, profile.name)) }
    }

    /** The user typed the new name. */
    fun setRenameName(text: String) = local.update { old ->
        (old.dialog as? ProfileDialogUiState.Rename)?.let { dialog -> old.copy(dialog = dialog.copy(name = text, error = null)) } ?: old
    }

    /** Renames the profile. A name that a different profile has gives "A profile with this name already exists.". */
    fun confirmRename() {
        val current = local.value.dialog as? ProfileDialogUiState.Rename ?: return
        if (current.busy) return
        local.update { old -> old.copy(dialog = current.copy(busy = true)) }
        scope.launch {
            when (val result = machines.renameProfile(current.id, current.name)) {
                is StoreResult.Ok -> {
                    closeDialog()
                    showMessage(UiText.of(Res.string.settings_profiles_renamed, result.value.name))
                }
                is StoreResult.Refused -> local.update { old ->
                    old.copy(dialog = current.copy(busy = false, error = result.refusal.toUiText()))
                }
            }
        }
    }

    // Machine type

    /** Opens the choice of the machine type of [id]. */
    fun openChangeType(id: String) {
        val profile = profileOf(id) ?: return
        local.update { old ->
            old.copy(menuFor = null, dialog = ProfileDialogUiState.ChooseType(profile.id, profile.name, profile.machineType, profile.machineType))
        }
    }

    /** Selects the new machine type. */
    fun selectType(type: MachineType) = local.update { old ->
        (old.dialog as? ProfileDialogUiState.ChooseType)?.let { dialog -> old.copy(dialog = dialog.copy(selected = type)) } ?: old
    }

    /** Closes the choice. A different type opens the confirmation with the warning and the list. */
    fun confirmType() {
        val current = local.value.dialog as? ProfileDialogUiState.ChooseType ?: return
        closeDialog()
        if (current.selected != current.current) typeChange.ask(current.id, current.selected)
    }

    // Messages

    /** The snackbar showed the message [id]. */
    fun onMessageShown(id: Long) = local.update { old -> if (old.message?.id == id) old.copy(message = null) else old }

    private fun showMessage(text: UiText) {
        val id = ++messageCounter
        local.update { old -> old.copy(message = ProfilesMessage(id, text)) }
    }
}
