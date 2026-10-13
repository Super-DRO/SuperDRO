package org.superdro.core.connection

/**
 * The cause of a link failure. A transport reports a failure as a
 * [LinkFailureException] with one of these values. The transport does not try again. The session
 * decides about a retry.
 *
 * The session state that follows:
 * - [Refused] and [Lost] go to `Reconnecting`.
 * - [PermissionMissing], [RadioOff], [NetworkOff], and [DeviceGone] go to `WaitingForDevice`.
 *   The session starts again at once when the capability comes back.
 */
sealed interface LinkFailure {

    /** The connect failed or timed out. The TCP connect has a limit of 5000 ms for each attempt. */
    data object Refused : LinkFailure

    /** A read or a write failed. A Bluetooth write that does not end in 4000 ms is also this failure. */
    data object Lost : LinkFailure

    /** A permission is not granted: Bluetooth, the USB device, or the local network. */
    data object PermissionMissing : LinkFailure

    /** The Bluetooth radio is off. */
    data object RadioOff : LinkFailure

    /** No network for a TCP link. */
    data object NetworkOff : LinkFailure

    /** The device is not there: for example, the USB adapter is detached. */
    data object DeviceGone : LinkFailure

    /** True when the session waits for a capability event and not for a timer. */
    val waitsForCapability: Boolean
        get() = this == PermissionMissing || this == RadioOff || this == NetworkOff || this == DeviceGone
}

/**
 * The exception that a [Transport] throws for a link failure. [failure] gives the cause.
 */
class LinkFailureException(
    val failure: LinkFailure,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: failure.toString(), cause)
