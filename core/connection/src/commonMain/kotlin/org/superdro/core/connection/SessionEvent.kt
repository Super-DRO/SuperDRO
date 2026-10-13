package org.superdro.core.connection

import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.CommandFailure
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.ReferenceState

/**
 * An event of one session for the UI and the log.
 * [DeviceSession.events] gives the events in a buffered flow. A collector that starts late does
 * not get the old events: [AdapterInfo] holds the state.
 */
sealed interface SessionEvent {

    /**
     * The connect 2000 ms after the ack of `#reboot:1000;` failed. The tab shows "Could not
     * restart the adapter. Turn the adapter power off and on." The reconnect policy continues.
     */
    data class RestartFailed(val atMs: Long) : SessionEvent

    /**
     * A smaller `uptime` told that the adapter restarted. The values that are not
     * constant are empty now. The machine datums and the error maps can need a new reference.
     */
    data class AdapterRestarted(val oldUptime: Long, val newUptime: Long) : SessionEvent

    /** The identity of the link is not the identity of the adapter profile of the tab. */
    data class IdentityDiffers(val expected: String, val actual: AdapterIdentity) : SessionEvent

    /** A mode write of [channel] completed. The screen shows "Restart Now". */
    data class RestartNeeded(val channel: InputChannel) : SessionEvent

    /**
     * An event of the reference mark search of [channel]: "in progress" on
     * `$<ref>a0;` or `$<ref>aa;`, and the end on `$<ref>a1;` (short sound), `$<ref>a9;`, or
     * `$<ref>16;` / `!<ref>16;` (error sound).
     */
    data class Reference(val channel: InputChannel, val state: ReferenceState) : SessionEvent

    /**
     * A command failed with an adapter error. The row of the channel shows [CommandFailure.message]:
     * `Command failed: <text> (<sub>:<code>)`.
     */
    data class CommandFailed(val command: AdapterCommand, val failure: CommandFailure) : SessionEvent

    /** The "Dirty Axes" status `$32<mask>;`. The session logs it. No UI uses it. */
    data class DirtyFlags(val mask: Int, val channels: Set<InputChannel>) : SessionEvent
}

/** The result of a "Restart Now" request. */
sealed interface RestartResult {

    /**
     * The adapter accepted `#reboot:1000;`. The session closes the link 250 ms after the ack and
     * connects again 2000 ms after the ack. [AdapterInfo.restart] and [SessionEvent.RestartFailed]
     * give the end of the sequence.
     */
    data object Accepted : RestartResult

    /** No session or no open link exists. The session sends nothing. The UI shows "Restart Failed". */
    data object NoSession : RestartResult

    /** The command did not complete. [result] is the result of the queue, for example a timeout. */
    data class NotAccepted(val result: CommandResult) : RestartResult
}
