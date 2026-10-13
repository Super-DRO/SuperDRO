package org.superdro.core.connection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandResult
import org.superdro.core.readout.input.ChannelValue
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.ProbeEvent

/**
 * The run-time object that owns the link to one device. [ConnectionManager] makes one session for
 * each device.
 *
 * Each session has its own transport, parser, input stage, command queue, adapter value store,
 * time base, BEL byte timer, and reconnect state. A new link gets a new parser, a new
 * queue, and a new time offset. The session runs in its own child scope of the manager: an
 * exception in one session stops only that session.
 *
 * The UI does not select a session in the manager. Each tab collects the flows of its own session.
 *
 * The input stage and its output types are in `core:readout` (package
 * `org.superdro.core.readout.input`): [InputSnapshot], [ProbeEvent], and [InputHistory].
 */
interface DeviceSession {

    /** The device of the session. */
    val id: DeviceId

    /** The device with its name and its link. [ConnectionManager.connect] can change it. */
    val device: StateFlow<DeviceRef>

    /** The state of the session. */
    val state: StateFlow<SessionState>

    /**
     * The latest output of the input stage. The flow is conflated: the UI reads the latest value
     * for each frame. The input stage itself sees each sample in stream order.
     */
    val inputs: StateFlow<InputSnapshot>

    /**
     * Each accepted sample of each encoder input (`x y z w u v t`), in stream order, as the input
     * stage gives it: the raw count, the corrected count, the value, and the time. The
     * flow is not conflated, unlike [inputs]. It has a buffer of [SAMPLE_BUFFER] samples for a
     * collector that is slow: when a collector lags by more, it loses the oldest samples. The
     * flow has no replay: collect it before the samples come. A fake session gives no sample.
     */
    val samples: SharedFlow<ChannelValue> get() = NO_SAMPLES

    /**
     * Each accepted probe activation with the positions that the input stage latched in stream
     * order. The flow is buffered and not conflated, so that two closures close together
     * are both delivered.
     */
    val probeEvents: SharedFlow<ProbeEvent>

    /** The adapter values with their read states, and the identity and restart state. */
    val adapterInfo: StateFlow<AdapterInfo>

    /**
     * The input history of the session. The input stage writes it in the session
     * context. Read it with [readHistory].
     */
    val history: InputHistory

    /** The status, error, information, and signature messages of the adapter, in stream order. */
    val messages: SharedFlow<AdapterMessage>

    /** The events of the session: restart, identity, reference marks, and command failures. */
    val events: SharedFlow<SessionEvent>

    /**
     * Puts [command] into the command queue and waits for its result.
     *
     * - A mode write, a clear, and a reference command give [CommandResult.NotConfirmed] when no
     *   result comes in 10000 ms. A mode write completes when the read-back `#get_mode`
     *   shows the new mode.
     * - With two-way communication off, the result is [CommandResult.NotSent] at once.
     * - `#set_mode`, `#ref_axis`, `#clear_counter`, and `#reboot` give [CommandResult.NotSent] at
     *   once when the switch "Adapter configuration commands" of the machine profile is off.
     *   With no stored profile ([DeviceSetupSource] gives null), the switch does not apply.
     * - With no open link, or when the link drops first, the result is [CommandResult.Cancelled].
     */
    suspend fun send(command: AdapterCommand): CommandResult

    /**
     * Sends `#reboot:1000;` ("Restart Now"). After the ack, the session closes the
     * link after 250 ms and connects again 2000 ms after the ack, on each link type. When
     * that connect fails, [AdapterInfo.restart] becomes [AdapterRestartState.Failed], the session
     * sends [SessionEvent.RestartFailed], and the reconnect policy continues.
     */
    suspend fun restartAdapter(): RestartResult

    /**
     * The Adapter Details screen opens. The session queues `"#get:name;"`,
     * `"#get:version;"`, `"#get:build;"`, `"#get:date;"`, then `#get_mode:` for `x y z w u v`, and
     * does that again at each change to `Connected` while the screen is open.
     */
    fun openAdapterDetails()

    /** The Adapter Details screen closes. */
    fun closeAdapterDetails()

    /** Runs [block] with the input history in the session context and gives its result. */
    suspend fun <T> readHistory(block: (InputHistory) -> T): T

    companion object {
        /** The buffer of [samples]: about 30 s of 7 inputs at 20 frames each second. */
        const val SAMPLE_BUFFER: Int = 4096

        private val NO_SAMPLES: SharedFlow<ChannelValue> = MutableSharedFlow<ChannelValue>().asSharedFlow()
    }
}
