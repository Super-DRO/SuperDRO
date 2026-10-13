package org.superdro.core.ui.settings.profiles

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_profiles_deleted
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.text.UiText

/**
 * The state of the delete question of a profile.
 *
 * @property toolCount The number of the tools of the profile library. With 1 or more tools, the
 *   dialog asks "Delete the tools of this profile too?".
 * @property result The text for the snackbar after the delete.
 */
@Immutable
data class DeleteProfileUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val toolCount: Int = 0,
    val busy: Boolean = false,
    val done: Boolean = false,
    val result: UiText? = null,
)

/**
 * The presenter of the delete question of the profile [profileId]. SuperDRO
 * asks "Delete the tools of this profile too?". "Delete the tools" deletes them; "Keep the tools"
 * moves them to the shared library.
 *
 * The delete stops the session of the machine and forgets the remembered machine, so its tab
 * closes. Then the store deletes the profile with all its data in one transaction.
 */
class DeleteProfilePresenter(
    private val profileId: String,
    private val machines: MachineRepository,
    private val tools: ToolRepository,
    private val tabStore: TabStore,
    private val connections: Connections,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private val state = MutableStateFlow(DeleteProfileUiState())

    /** The state of the dialog. */
    val uiState: StateFlow<DeleteProfileUiState> = state.asStateFlow()

    init {
        scope.launch {
            val profile = machines.loadProfile(profileId)
            val count = tools.profileTools(profileId).first().size
            state.update { old -> old.copy(loaded = profile != null, name = profile?.name.orEmpty(), toolCount = count) }
        }
    }

    /** Deletes the profile. [deleteTools] is the answer to the delete question. */
    fun delete(deleteTools: Boolean) {
        val current = state.value
        if (current.busy || current.done || !current.loaded) return
        state.update { old -> old.copy(busy = true) }
        scope.launch {
            connections.remove(DeviceId(profileId))
            tabStore.forget(profileId)
            machines.deleteProfile(profileId, deleteTools)
            state.update { old -> old.copy(busy = false, done = true, result = UiText.of(Res.string.settings_profiles_deleted, current.name)) }
        }
    }
}
