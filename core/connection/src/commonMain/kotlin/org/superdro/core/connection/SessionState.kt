package org.superdro.core.connection

/**
 * The state of one device session.
 *
 * The times are milliseconds of the session clock (`org.superdro.core.util.Clock`), not wall
 * clock times. The no-data watchdog, the 30 s limits, and the BEL byte use the same clock. Tests
 * use virtual time.
 *
 * Transitions:
 *
 * | From | To | Trigger |
 * |---|---|---|
 * | [Disconnected] | [Connecting] (1) | A user connect, or the restore |
 * | [Connecting] | [AwaitingData] | The open succeeds |
 * | [Connecting] | [Reconnecting] | The open fails ([LinkFailure.Refused]) |
 * | [Connecting], an open state | [WaitingForDevice] | [LinkFailure.RadioOff], [LinkFailure.PermissionMissing], [LinkFailure.DeviceGone] |
 * | [AwaitingData], [NoData] | [Connected] | A valid message |
 * | [Connected] | [NoData] | No valid message for 7 s |
 * | [NoData] (SPP, TCP) | [Reconnecting] (first delay 750 ms) | At once: the `NO_DATA` recovery |
 * | [AwaitingData], [NoData] (USB) | [Reconnecting] | 30 s in the state |
 * | an open state | [Reconnecting] | A read or write failure ([LinkFailure.Lost]) |
 * | [Reconnecting] | [Connecting] (attempt + 1) | The delay ends |
 * | [WaitingForDevice] | [Connecting] (1) | The capability comes back |
 * | each state | [Disconnected] | A user disconnect, or "Disconnect all" |
 *
 * The session writes only in [Connected]: the BEL byte and the command queue. In each other
 * state it writes no byte.
 */
sealed interface SessionState {

    /** The user does not want a link. A paused remembered machine is in this state. */
    data object Disconnected : SessionState

    /** The transport opens. [attempt] starts at 1. */
    data class Connecting(val attempt: Int) : SessionState

    /** The link is open. No valid message arrived yet. The session sends no byte. */
    data object AwaitingData : SessionState

    /**
     * Valid messages arrive. A valid message is a readout, status, error, or information
     * message. A time stamp (`@`) message is not a valid message for this rule.
     *
     * @property sinceMs The session clock time of the first valid message of this period.
     */
    data class Connected(val sinceMs: Long) : SessionState

    /** The link is open, but no valid message arrived for 7 s. */
    data object NoData : SessionState

    /**
     * The link failed. The session waits, then opens again.
     *
     * @property attempt The number of the attempt that failed. The next state is
     *   [Connecting] with the number `attempt + 1`. The tab shows this number.
     *   After 10 s in [Connected], a new series starts: the link counts as attempt 1.
     * @property retryAtMs The session clock time of the next attempt. The delays are 1, 2, 4,
     *   8, 16, then 30 s, each ±20 %. They start at 1 s again after 10 s in [Connected].
     * @property cause The failure of the last attempt.
     */
    data class Reconnecting(val attempt: Int, val retryAtMs: Long, val cause: LinkFailure) : SessionState

    /**
     * The radio is off, a permission is not granted, the USB device is detached, or a TCP link
     * has no network. The session waits for a capability event, not for a timer.
     */
    data class WaitingForDevice(val cause: LinkFailure) : SessionState

    /** The policy stopped. The user must act. */
    data class Failed(val cause: LinkFailure) : SessionState

    /**
     * True while the session has an open link: [AwaitingData], [Connected], or [NoData]. The tab
     * row shows the machines whose session is online.
     */
    val isOnline: Boolean
        get() = this == AwaitingData || this is Connected || this == NoData

    /**
     * True while the session wants the process to stay alive: [Connecting], [AwaitingData],
     * [Connected], [NoData], [Reconnecting], or [WaitingForDevice]. The keep-alive summary is not
     * null while one or more sessions have this value.
     */
    val keepsAlive: Boolean
        get() = this != Disconnected && this !is Failed
}
