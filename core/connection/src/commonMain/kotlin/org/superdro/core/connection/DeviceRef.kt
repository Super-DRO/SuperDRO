package org.superdro.core.connection

import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * The device that a session connects to: one machine with its adapter link.
 * [ConnectionManager.connect] takes it. [ConnectionManager.restore] makes one from
 * each [WantedLink].
 *
 * @property id The device: the ID of the machine profile.
 * @property name The name of the machine. The notification shows it.
 * @property address The adapter link. A change of the address starts a new link at once.
 * @property twoWayCommunication False when the session must write no byte: no BEL byte, no start
 *   query, and no command. When [DeviceSetupSource] gives a machine profile, the value of
 *   the profile applies.
 * @property recordStream True when the session writes each received chunk with its arrival time
 *   to the [StreamRecorder] of the connection manager.
 *   [ConnectionManager.connect] with true turns the record of the device on. The manager keeps the
 *   flag ([ConnectionManager.recording]), so a later connect with false does not stop the record:
 *   use [ConnectionManager.setRecordStream].
 */
data class DeviceRef(
    val id: DeviceId,
    val name: String,
    val address: AdapterAddress,
    val twoWayCommunication: Boolean = true,
    val recordStream: Boolean = false,
) {
    /** The link type of [address]. */
    val transport: TransportKind get() = address.kind
}

/** Gives the [DeviceRef] of a wanted link. */
fun WantedLink.toDeviceRef(): DeviceRef = DeviceRef(
    id = deviceId,
    name = name,
    address = address,
    twoWayCommunication = twoWayCommunication,
)
