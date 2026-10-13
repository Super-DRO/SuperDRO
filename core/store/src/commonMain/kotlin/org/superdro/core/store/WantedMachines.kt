package org.superdro.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.superdro.core.model.AdapterAddress
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.MachineRepository

/**
 * One machine that wants a link: a remembered machine that the user did not pause, and that has
 * an adapter address. `core:di` maps it to `WantedLink` of `core:connection`
 * (`deviceId` = `DeviceId(profileId)`), because `core:store` does not depend on
 * `core:connection`.
 */
data class WantedMachine(
    val profileId: String,
    val name: String,
    val address: AdapterAddress,
    val twoWayCommunication: Boolean,
)

/**
 * The machines that want a link, in the order of the tabs: the IDs of [tabs] with the names and
 * the addresses of [machines]. A remembered ID with no profile, or a profile with no adapter
 * address, gives no entry. The flow gives a new list after each change of the tabs or the
 * profiles.
 */
fun wantedMachines(tabs: TabStore, machines: MachineRepository): Flow<List<WantedMachine>> =
    combine(tabs.wantedProfileIds, machines.profiles()) { ids, profiles ->
        val byId = profiles.associateBy { profile -> profile.id }
        ids.mapNotNull { id ->
            val profile = byId[id] ?: return@mapNotNull null
            val address = profile.adapter ?: return@mapNotNull null
            WantedMachine(profile.id, profile.name, address, profile.twoWayCommunication)
        }
    }.distinctUntilChanged()
