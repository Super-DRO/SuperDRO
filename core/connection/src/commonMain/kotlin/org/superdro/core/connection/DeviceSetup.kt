package org.superdro.core.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.MachineProfile

/**
 * The stored setup of one device: the machine profile and the adapter profile that the tab
 * selects.
 *
 * The session uses it for these functions:
 * - The input stage gets its configuration from it: the encoder profiles of the adapter inputs,
 *   the terms of each axis, and the probe values.
 * - The identity check compares [AdapterProfile.identity] with the identity of the link.
 * - [MachineProfile.twoWayCommunication] turns the writes on or off.
 *
 * @property adapter The adapter profile of the tab, or null when the tab has no adapter profile.
 */
data class DeviceSetup(
    val machine: MachineProfile,
    val adapter: AdapterProfile? = null,
)

/**
 * Gives the stored setup of each device. `core:di` binds it to the repositories of `core:store`, so
 * `core:connection` does not depend on `core:store`.
 *
 * A change in a settings screen goes into the repository. The flow then gives the new setup, and
 * the session uses it at once, also while the link is open. The input stage starts a new filter
 * state only for the channels that changed.
 */
fun interface DeviceSetupSource {

    /**
     * The setup of [id]: the current value first, then a new value at each change. Null means
     * that the device has no stored profile. The session then uses an empty input configuration.
     */
    fun setup(id: DeviceId): Flow<DeviceSetup?>

    companion object {
        /** A source with no stored setup. Each device gets null. */
        val None: DeviceSetupSource = DeviceSetupSource { flowOf(null) }
    }
}
