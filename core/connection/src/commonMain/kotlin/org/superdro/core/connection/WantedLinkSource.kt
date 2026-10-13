package org.superdro.core.connection

import kotlinx.coroutines.flow.Flow
import org.superdro.core.model.AdapterAddress

/**
 * Gives the links that the user wants. Each remembered machine that the user did not pause wants a
 * link.
 *
 * `ConnectionManager.restore()` reads this source. `core:di` binds it to `TabStore` and the
 * machine profiles, so `core:connection` does not depend on `core:store`.
 */
interface WantedLinkSource {

    /** The wanted links. The flow gives the current list first, and then a new list at each change. */
    val wantedLinks: Flow<List<WantedLink>>
}

/**
 * One wanted link.
 *
 * @property deviceId The device: the ID of the machine profile.
 * @property name The name of the machine.
 * @property twoWayCommunication False when the session must write no byte.
 */
data class WantedLink(
    val deviceId: DeviceId,
    val name: String,
    val address: AdapterAddress,
    val twoWayCommunication: Boolean = true,
)
