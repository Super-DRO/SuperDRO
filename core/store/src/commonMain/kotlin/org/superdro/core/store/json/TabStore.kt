package org.superdro.core.store.json

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.superdro.core.model.RememberedMachine
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.TabState
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.storeIoDispatcher

/**
 * The tab state in `tabs.json`: the remembered
 * machines, their pause flags, and the last selected machine. The rules of [JsonFileStore]
 * apply.
 *
 * `core:store` does not depend on `core:connection`. Thus `core:di` adapts this store to the
 * contract `WantedLinkSource` with [wantedProfileIds] or `wantedMachines`.
 */
class TabStore(
    storeIO: StoreIOProvider,
    json: Json = SuperDroJson,
    dispatcher: CoroutineDispatcher = storeIoDispatcher,
    clock: Clock = Clock.System,
) : JsonFileStore<TabState>(storeIO, FILE_NAME, TabState.serializer(), TabState(), json, dispatcher, clock) {

    /**
     * The IDs of the remembered machines that the user did not pause, in the order of the tabs:
     * the machines that want a link. The flow waits for the load, so its first value is
     * the value of the file.
     */
    val wantedProfileIds: Flow<List<String>> = flow {
        awaitLoaded()
        emitAll(state.map { tabs -> tabs.remembered.filterNot { machine -> machine.paused }.map { machine -> machine.profileId } })
    }.distinctUntilChanged()

    /** Remembers the machine [profileId]. A machine that is remembered already does not change. */
    fun remember(profileId: String) {
        update { tabs ->
            if (tabs.remembered.any { it.profileId == profileId }) tabs
            else tabs.copy(remembered = tabs.remembered + RememberedMachine(profileId = profileId))
        }
    }

    /** Removes the machine [profileId]. When it was the selected machine, no machine is selected. */
    fun forget(profileId: String) {
        update { tabs ->
            tabs.copy(
                remembered = tabs.remembered.filterNot { it.profileId == profileId },
                selectedProfileId = tabs.selectedProfileId.takeUnless { it == profileId },
            )
        }
    }

    /** Sets the pause flag of the machine [profileId]. A paused machine does not connect automatically. */
    fun setPaused(profileId: String, paused: Boolean) {
        update { tabs ->
            tabs.copy(remembered = tabs.remembered.map { machine -> if (machine.profileId == profileId) machine.copy(paused = paused) else machine })
        }
    }

    /** Records the selected machine, or null for no selection. */
    fun select(profileId: String?) {
        update { tabs -> tabs.copy(selectedProfileId = profileId) }
    }

    companion object {
        /** The store name of the file. */
        const val FILE_NAME: String = "tabs.json"
    }
}
