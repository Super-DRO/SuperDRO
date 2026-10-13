package org.superdro.core.connection

import org.superdro.core.model.IdentitySource
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.InputChannel

/**
 * The adapter values of one session with their read states, and the adapter state that the
 * session adds. [DeviceSession.adapterInfo] holds it.
 * The store adds no bytes on the wire.
 *
 * @property values The register model of `core:protocol`: the value and the read state ("Empty",
 *   "Fetching", "Reported", "Failed") of each adapter field, mode, reference state, and dirty flag.
 *   The values stay from one link to the next link of the session. A link drop marks each
 *   "Fetching" value "Failed".
 * @property twoWayCommunication False when the session writes no byte. The values then
 *   stay "Empty".
 * @property identity The identity of the adapter on the current link, or null while it is
 *   not known.
 * @property identityCheck The result of the identity check against the adapter profile of the tab.
 *   The readout is live in `Connected` before the check.
 * @property busyChannels The channels with a command that did not end. The UI disables their
 *   buttons.
 * @property restartNeeded The channels with a mode write that completed. The screen shows "Some
 *   changes will not take effect until the adapter restarts." and "Restart Now".
 * @property restart The state of the "Restart Now" sequence.
 * @property adapterRestarts The number of adapter restarts that a smaller `uptime` told.
 * @property detailsOpen True while the Adapter Details screen is open.
 * @property queueActive True while the command queue can send.
 */
data class AdapterInfo(
    val values: AdapterValues = AdapterValues(),
    val twoWayCommunication: Boolean = true,
    val identity: AdapterIdentity? = null,
    val identityCheck: IdentityCheck = IdentityCheck.Pending,
    val busyChannels: Set<InputChannel> = emptySet(),
    val restartNeeded: Set<InputChannel> = emptySet(),
    val restart: AdapterRestartState = AdapterRestartState.Idle,
    val adapterRestarts: Int = 0,
    val detailsOpen: Boolean = false,
    val queueActive: Boolean = false,
)

/**
 * The identity of one adapter: the Bluetooth address of an SPP link, else the
 * reported MAC + 2 with the carry, else the reported UUID. No signature result is necessary.
 *
 * @property value The identity text. A Bluetooth address and a MAC + 2 have 6 bytes of 2 lower case
 *   hex digits, joined by `:`, for example `24:6f:28:a1:b2:c5`. A UUID is lower case.
 * @property source [IdentitySource.BtMac] for a Bluetooth address and for a MAC + 2, else
 *   [IdentitySource.AdapterReported].
 */
data class AdapterIdentity(val value: String, val source: IdentitySource) {

    /** True when [identity] is the same identity. The case of the letters does not matter. */
    fun matches(identity: String?): Boolean = identity != null && identity.equals(value, ignoreCase = true)
}

/**
 * The identity check of the adapter. The tab selects the adapter profile, and thus the encoder
 * profiles. The check only confirms the profile. It never stops the readout.
 */
sealed interface IdentityCheck {

    /** The identity of the link is not known yet. */
    data object Pending : IdentityCheck

    /** The adapter profile of the tab has no stored identity, or the tab has no adapter profile. */
    data object NotStored : IdentityCheck

    /** The identity of the link is the identity of the adapter profile. */
    data object Confirmed : IdentityCheck

    /** The identity of the link is not the identity of the adapter profile. The UI shows a warning. */
    data class Differs(val expected: String, val actual: AdapterIdentity) : IdentityCheck
}

/** The state of the "Restart Now" sequence. */
enum class AdapterRestartState {
    /** No restart is in progress. */
    Idle,

    /** The adapter accepted `#reboot:1000;`. The session closes the link and connects again. */
    Restarting,

    /**
     * The connect 2000 ms after the ack failed. The tab shows "Could not restart the adapter. Turn
     * the adapter power off and on." The reconnect policy continues.
     */
    Failed,
}
