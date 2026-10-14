package org.superdro.core.protocol

/**
 * The link phase that the session gives to the queue. The session maps its state as follows:
 *
 * | Session state | Phase |
 * |---|---|
 * | `Connected` | [Connected] |
 * | `Connecting`, `AwaitingData`, `NoData` | [Waiting] |
 * | `Disconnected`, `Reconnecting`, `WaitingForDevice`, `Failed` | [Dropped] |
 */
enum class LinkPhase {
    /** The session can write. */
    Connected,

    /** The link is not ready. The queue writes nothing, but its waits continue. */
    Waiting,

    /** The link is gone. The queue clears all. */
    Dropped,
}

/** The final result of one request. The queue gives one result for each request. */
sealed interface CommandResult {
    /** The answer completed the request. */
    data object Completed : CommandResult

    /** The request ended with a failure. A timeout has the failure [CommandFailure.TIMEOUT]. */
    data class Failed(val failure: CommandFailure) : CommandResult

    /**
     * A mode write, a clear, or a reference command got no result in 10000 ms, or the read-back
     * `#get_mode` did not show the written mode.
     */
    data object NotConfirmed : CommandResult

    /** The session dropped before the request ended. */
    data object Cancelled : CommandResult

    /** Two-way communication is off. The queue sends nothing. */
    data object NotSent : CommandResult
}

/** The outputs of the queue. The session executes them in the order of the list. */
sealed interface QueueOutput {

    /**
     * Write [bytes] now. [transmission] is 1 for the first write and 2 or 3 for a Busy resend.
     * Each write starts the 500 ms of the BEL byte again.
     */
    data class Write(val requestId: Long, val command: AdapterCommand, val transmission: Int) : QueueOutput {
        /** The exact bytes of the command. */
        val bytes: ByteArray get() = command.encode()
    }

    /** Call [CommandQueue.onTimer] at [atMs]. A new timer request replaces the last one. */
    data class Timer(val atMs: Long) : QueueOutput

    /** The final result of the request [requestId]. */
    data class Result(val requestId: Long, val command: AdapterCommand, val result: CommandResult) : QueueOutput

    /** Show the text [CommandFailure.message] in the row of the channel: `Command failed: <text> (<sub>:<code>)`. */
    data class CommandFailed(val requestId: Long, val command: AdapterCommand, val failure: CommandFailure) : QueueOutput

    /** A mode write of [channel] completed. The screen shows "Restart Now". */
    data class RestartNeeded(val channel: InputChannel) : QueueOutput

    /** An event of the reference search of [channel]. */
    data class Reference(val channel: InputChannel, val state: ReferenceState) : QueueOutput

    /** The "Dirty Axes" status `$32<mask>;`. The session logs it. No UI uses it. */
    data class DirtyFlags(val mask: Int, val channels: Set<InputChannel>) : QueueOutput

    /**
     * The adapter accepted `#reboot:1000;` at [ackAtMs]. Close the link at [closeAtMs] and open it
     * again at [connectAtMs].
     */
    data class RestartAdapter(val ackAtMs: Long, val closeAtMs: Long, val connectAtMs: Long) : QueueOutput

    /** A smaller `uptime` arrived: the adapter restarted. The values that are not constant are empty now. */
    data class AdapterRestartDetected(val oldUptime: Long, val newUptime: Long) : QueueOutput

    /** The adapter values changed. Put [values] into the `StateFlow` of the session. */
    data class ValuesChanged(val values: AdapterValues) : QueueOutput

    /** The busy channels changed. The buttons of a busy channel are disabled. */
    data class BusyChannels(val channels: Set<InputChannel>) : QueueOutput

    /** A line for the session log. */
    data class Log(val text: String) : QueueOutput
}

/** The result of [CommandQueue.submit]: the ID of the new request and the outputs. */
data class Submission(val requestId: Long, val outputs: List<QueueOutput>)

/**
 * The command queue of one link. It is a pure state machine. The
 * session gives it the time, the link phase, and the inbound messages. The session executes the
 * outputs: the writes, the timer requests, and the results. The queue sends one request at a time.
 *
 * The session makes a new queue for each link. After [LinkPhase.Dropped], a queue that
 * gets [LinkPhase.Connected] again starts as a new link.
 *
 * Each call first runs the timers that were due before the call time, then the input, then the
 * timers that are due at the call time. Thus a message that arrives at the end time of a wait is
 * in time.
 *
 * Details of the rules:
 * - The "id" retry: 5000 ms after the queue start, each
 *   "id" field with no value and with no query in the queue or on the wire gets one more query.
 *   A field whose query is still in the queue or on the wire gets the one more query only when
 *   that query ends with no value. Each field gets one more query or less for each link.
 * - A request with a result limit that did not go out at the limit leaves the queue.
 * - When a request showed a `Command failed` text and then ends with a failure, its result has
 *   the failure of the text, not the later timeout.
 *
 * @param twoWay False when the user turned off two-way communication. Then the queue writes
 *   nothing, sends no start query, and the values stay empty.
 * @param timing The times of the dialect.
 * @param initialValues The values at the start of the link.
 */
class CommandQueue(
    val twoWay: Boolean = true,
    val timing: AdapterTiming = AdapterDialect.DEFAULT_TIMING,
    initialValues: AdapterValues = AdapterValues(),
) {

    private class Request(val id: Long, val command: AdapterCommand) {
        var transmissions = 0
        var waitUntilMs: Long? = null
        var resendAtMs: Long? = null
        var shownFailure: CommandFailure? = null
        var resultGiven = false
    }

    private class Operation(val request: Request, val deadlineMs: Long) {
        val command: AdapterCommand get() = request.command
        var readBackId: Long? = null
    }

    private enum class Due { Activate, WaitEnd, Resend, Send, IdRetry, Limit }

    private enum class Outcome { Keep, Complete, NotHandled }

    /** The adapter values with their read states. */
    var values: AdapterValues = initialValues
        private set

    /** The last link phase. */
    var phase: LinkPhase = LinkPhase.Waiting
        private set

    /** The time when the queue became active the first time on this link, or null. */
    var startedAtMs: Long? = null
        private set

    /** True while the Adapter Details screen is open. */
    var detailsOpen: Boolean = false
        private set

    /**
     * The busy channels: a channel is busy while a command or
     * a query of that channel is current or in the queue.
     */
    var busyChannels: Set<InputChannel> = emptySet()
        private set

    /** True when the queue can send: it started, and the phase is [LinkPhase.Connected]. */
    val isActive: Boolean get() = startedAtMs != null && phase == LinkPhase.Connected

    /** The command of the current request, or null. */
    val currentCommand: AdapterCommand? get() = current?.command

    /** The commands in the queue that did not go out yet, in their order. */
    val queuedCommands: List<AdapterCommand> get() = queue.map { it.command }

    /** The time of the next timer, or null. */
    val nextTimerAtMs: Long?
        get() {
            var next: Long? = null
            forEachDeadline { atMs, _, _ ->
                val earliest = next
                if (earliest == null || atMs < earliest) next = atMs
            }
            return next
        }

    private val queue = ArrayDeque<Request>()
    private var current: Request? = null
    private val operations = mutableListOf<Operation>()
    private var activationAtMs: Long? = null
    private var idRetryDone = false
    private val idRetryWaits = mutableSetOf<AdapterField>()
    private val idRetried = mutableSetOf<AdapterField>()
    private var lastWriteAtMs: Long? = null
    private var nextId = 1L

    /**
     * Gives a new link phase at [nowMs]. The first [LinkPhase.Connected] of a link starts the
     * 2000 ms to the queue start. A later change to [LinkPhase.Connected] queues the Adapter
     * Details group when the screen is open. [LinkPhase.Dropped] clears the queue, the current
     * request, and all timers.
     */
    fun onLinkPhase(newPhase: LinkPhase, nowMs: Long): List<QueueOutput> = step(nowMs) { out ->
        val old = phase
        if (newPhase == old) return@step
        phase = newPhase
        when (newPhase) {
            LinkPhase.Connected -> {
                if (activationAtMs == null) {
                    activationAtMs = nowMs + timing.queueStartMs
                } else if (startedAtMs != null && detailsOpen && twoWay) {
                    queueDetails(nowMs)
                }
            }
            LinkPhase.Waiting -> Unit
            LinkPhase.Dropped -> drop(nowMs, out)
        }
    }

    /** Gives an inbound message at [nowMs]. The queue uses the info, signature, status, and error messages. */
    fun onMessage(message: AdapterMessage, nowMs: Long): List<QueueOutput> = step(nowMs) { out ->
        when (message) {
            is AdapterMessage.Info -> info(message.fieldId, message.text, nowMs, out)
            is AdapterMessage.Signature -> signature(message.base64, nowMs, out)
            is AdapterMessage.Status -> status(message.subsystem, message.code, nowMs, out)
            is AdapterMessage.Error -> errorPath(message.subsystem, message.code, nowMs, out)
            is AdapterMessage.AxisCount, is AdapterMessage.BinaryInput,
            is AdapterMessage.Timestamp, is AdapterMessage.Unknown,
            -> Unit
        }
    }

    /** Runs the timers that are due at [nowMs]. The session calls it at the time of each [QueueOutput.Timer]. */
    fun onTimer(nowMs: Long): List<QueueOutput> = step(nowMs) { }

    /**
     * Puts [command] at the end of the queue at [nowMs]. A query marks its value "Fetching". A mode
     * write, a clear, and a reference command get the result limit of 10000 ms. With
     * two-way communication off, the result is [CommandResult.NotSent] at once. After a drop, the
     * result is [CommandResult.Cancelled] at once.
     */
    fun submit(command: AdapterCommand, nowMs: Long): Submission {
        require(command.kind != RequestKind.Raw) { "The BEL byte does not go through the queue." }
        val request = Request(nextId++, command)
        val outputs = step(nowMs) { out ->
            when {
                !twoWay -> giveResult(request, CommandResult.NotSent, out)
                phase == LinkPhase.Dropped -> giveResult(request, CommandResult.Cancelled, out)
                else -> {
                    queue.addLast(request)
                    keyOf(command)?.let { key -> values = values.fetching(key, nowMs) }
                    if (command is AdapterCommand.SetMode || command is AdapterCommand.ClearCounter || command is AdapterCommand.RefAxis) {
                        operations += Operation(request, nowMs + timing.resultLimitMs)
                    }
                }
            }
        }
        return Submission(request.id, outputs)
    }

    /**
     * The Adapter Details screen opens at [nowMs]. The queue gets `"#get:name;"`,
     * `"#get:version;"`, `"#get:build;"`, `"#get:date;"`, then `#get_mode:` for `x y z w u v`. A
     * value that is "Fetching" for less than 5000 ms gets no second query. Before the queue start,
     * the group goes in after the start queries.
     */
    fun openAdapterDetails(nowMs: Long): List<QueueOutput> = step(nowMs) {
        detailsOpen = true
        if (twoWay && startedAtMs != null) queueDetails(nowMs)
    }

    /** The Adapter Details screen closes. */
    fun closeAdapterDetails() {
        detailsOpen = false
    }

    private fun step(nowMs: Long, block: (MutableList<QueueOutput>) -> Unit): List<QueueOutput> {
        val out = ArrayList<QueueOutput>()
        val valuesBefore = values
        advance(nowMs, inclusive = false, out)
        block(out)
        advance(nowMs, inclusive = true, out)
        // The values go first, so that a result never comes before the values that it needs.
        if (values != valuesBefore) out.add(0, QueueOutput.ValuesChanged(values))
        val busy = computeBusy()
        if (busy != busyChannels) {
            busyChannels = busy
            out += QueueOutput.BusyChannels(busy)
        }
        nextTimerAtMs?.let { out += QueueOutput.Timer(it) }
        return out
    }

    private fun advance(nowMs: Long, inclusive: Boolean, out: MutableList<QueueOutput>) {
        while (true) {
            // One pass: the due deadline with the earliest time, then the lowest kind. For equal
            // values, the first deadline of the pass wins.
            var dueAtMs = 0L
            var due: Due? = null
            var operation: Operation? = null
            forEachDeadline { atMs, kind, limitOperation ->
                val isDue = if (inclusive) atMs <= nowMs else atMs < nowMs
                val best = due
                if (isDue && (best == null || atMs < dueAtMs || (atMs == dueAtMs && kind.ordinal < best.ordinal))) {
                    dueAtMs = atMs
                    due = kind
                    operation = limitOperation
                }
            }
            when (due ?: return) {
                Due.Activate -> activate(nowMs)
                Due.WaitEnd -> timeout(nowMs, out)
                Due.Resend -> current?.let { request -> write(request, nowMs, out) }
                Due.Send -> send(nowMs, out)
                Due.IdRetry -> idRetry(nowMs)
                Due.Limit -> limit(checkNotNull(operation), out)
            }
        }
    }

    /**
     * Gives each deadline of the queue to [visit]: the time, the kind, and the operation of a
     * [Due.Limit]. No list is made, because the queue looks for the next deadline at each step.
     */
    private inline fun forEachDeadline(visit: (atMs: Long, due: Due, operation: Operation?) -> Unit) {
        if (!twoWay) return
        val started = startedAtMs
        val activation = activationAtMs
        if (started == null && activation != null && phase == LinkPhase.Connected) visit(activation, Due.Activate, null)
        current?.waitUntilMs?.let { visit(it, Due.WaitEnd, null) }
        if (phase == LinkPhase.Connected) current?.resendAtMs?.let { visit(it, Due.Resend, null) }
        if (isActive && current == null && queue.isNotEmpty()) {
            visit(lastWriteAtMs?.plus(timing.queueGapMs) ?: Long.MIN_VALUE, Due.Send, null)
        }
        if (started != null && !idRetryDone) visit(started + timing.idRetryMs, Due.IdRetry, null)
        for (index in operations.indices) {
            val limitOperation = operations[index]
            visit(limitOperation.deadlineMs, Due.Limit, limitOperation)
        }
    }

    /** The start queries go in once for each link. */
    private fun activate(nowMs: Long) {
        startedAtMs = nowMs
        AdapterField.idGroup.forEach { field -> queueQuery(AdapterCommand.GetField(field), nowMs) }
        InputChannel.entries.forEach { channel -> queueQuery(AdapterCommand.GetMode(channel), nowMs) }
        if (detailsOpen) queueDetails(nowMs)
    }

    /**
     * A field of the "id" group with no value 5000 ms after the queue start
     * gets one more query. When its query is still in the queue or on the wire, the field waits for
     * the end of that query ([idRetryAfterEnd]).
     */
    private fun idRetry(nowMs: Long) {
        idRetryDone = true
        AdapterField.idGroup.forEach { field ->
            if (values[ValueKey.Field(field)].value != null) return@forEach
            if (hasRequest(AdapterCommand.GetField(field))) idRetryWaits += field else queueIdRetry(field, nowMs)
        }
    }

    /** A waiting "id" field gets its one more query when its last query ends with no value. */
    private fun idRetryAfterEnd(request: Request, nowMs: Long) {
        val command = request.command as? AdapterCommand.GetField ?: return
        val field = command.field
        if (field !in idRetryWaits) return
        when {
            values[ValueKey.Field(field)].value != null -> idRetryWaits -= field
            !hasRequest(command) -> {
                idRetryWaits -= field
                queueIdRetry(field, nowMs)
            }
        }
    }

    /** Queues the one more query of an "id" field. Each field gets it once or less for each link. */
    private fun queueIdRetry(field: AdapterField, nowMs: Long) {
        if (idRetried.add(field)) queueQuery(AdapterCommand.GetField(field), nowMs)
    }

    private fun queueDetails(nowMs: Long) {
        val keys = AdapterField.secondaryGroup.map { ValueKey.Field(it) } + InputChannel.entries.map { ValueKey.Mode(it) }
        keys.forEach { key ->
            val command = key.command ?: return@forEach
            if (values.shouldQuery(key, nowMs, timing.readAgainLimitMs) && !hasRequest(command)) queueQuery(command, nowMs)
        }
    }

    private fun queueQuery(command: AdapterCommand, nowMs: Long) {
        queue.addLast(Request(nextId++, command))
        keyOf(command)?.let { key -> values = values.fetching(key, nowMs) }
    }

    private fun hasRequest(command: AdapterCommand): Boolean =
        current?.command == command || queue.any { it.command == command }

    /** Takes the head, writes it, sets the transmission count to 1, and starts the first wait. */
    private fun send(nowMs: Long, out: MutableList<QueueOutput>) {
        val request = queue.removeFirst()
        current = request
        request.transmissions = 1
        write(request, nowMs, out)
    }

    private fun write(request: Request, nowMs: Long, out: MutableList<QueueOutput>) {
        val command = request.command
        if (command is AdapterCommand.GetMode) values = values.withModeUnknown(command.channel)
        out += QueueOutput.Write(request.id, command, request.transmissions)
        lastWriteAtMs = nowMs
        request.resendAtMs = null
        request.waitUntilMs = nowMs + command.firstWaitMs
    }

    /** Clears the request, then calls its error function with (−1, 256). */
    private fun timeout(nowMs: Long, out: MutableList<QueueOutput>) {
        val request = current ?: return
        clearCurrent(request)
        errorFunction(request, CommandFailure.TIMEOUT, nowMs, out)
        finishRequest(request, CommandResult.Failed(CommandFailure.TIMEOUT), nowMs, out)
    }

    /** Stores the value first, also with no request. Then a current query completes. */
    private fun info(fieldId: Int, text: String, nowMs: Long, out: MutableList<QueueOutput>) {
        if (twoWay) {
            if (fieldId == AdapterField.Uptime.id) {
                val uptime = AdapterFieldValues.parseHex(text)
                val stored = values.uptime.value
                if (uptime != null && stored != null && values.isRestart(uptime)) {
                    out += QueueOutput.AdapterRestartDetected(stored, uptime)
                    out += QueueOutput.Log("Adapter restart: uptime $uptime is less than $stored")
                }
            }
            values = values.withInfo(fieldId, text, nowMs)
            if (fieldId == AdapterField.Mode.id) confirmModeWrites(out)
        }
        completeQuery(nowMs, out)
    }

    /** A signature answer: the values do not keep it. `#get_sig` puts the text in the log. */
    private fun signature(base64: String, nowMs: Long, out: MutableList<QueueOutput>) {
        if (current?.command is AdapterCommand.GetSig) out += QueueOutput.Log("Signature answer at $nowMs ms: $base64")
        completeQuery(nowMs, out)
    }

    private fun completeQuery(nowMs: Long, out: MutableList<QueueOutput>) {
        val request = current ?: return
        if (request.command.kind != RequestKind.Query) return
        clearCurrent(request)
        finishRequest(request, CommandResult.Completed, nowMs, out)
    }

    /** Applies a status message: the "Dirty Axes" mask, the error path, the current command, and the channels. */
    private fun status(subsystem: Int, code: Int, nowMs: Long, out: MutableList<QueueOutput>) {
        if (subsystem == Subsystem.DIRTY_AXES) {
            dirty(code, nowMs, out)
            return
        }
        if (StatusCode.isErrorPath(code)) {
            errorPath(subsystem, code, nowMs, out)
            return
        }
        val request = current
        var complete = false
        if (request != null && request.command.kind == RequestKind.Command && statusFunction(request, subsystem, code, nowMs, out)) {
            clearCurrent(request)
            finishRequest(request, CommandResult.Completed, nowMs, out)
            complete = true
        }
        if (!complete && request != null && subsystem == Subsystem.RX && code == StatusCode.RECEIVED) {
            request.waitUntilMs = nowMs + request.command.fullWaitMs
        }
        channelHandlers(subsystem, code, nowMs, out)
    }

    /** The error path: each `!` message, and a `$` message with a code from 1 to 159. */
    private fun errorPath(subsystem: Int, code: Int, nowMs: Long, out: MutableList<QueueOutput>) {
        val request = current
        if (request == null) {
            channelHandlers(subsystem, code, nowMs, out)
            return
        }
        request.waitUntilMs = null
        val failure = CommandFailure.of(subsystem, code)
        when {
            subsystem == Subsystem.RX && code == StatusCode.BUSY &&
                request.transmissions < timing.maxTransmissions && request.command.resendOnBusy -> {
                // The resend starts a new first wait (see write()).
                request.resendAtMs = nowMs + (1L shl request.transmissions) * timing.busyDelayBaseMs
                request.transmissions += 1
            }
            subsystem == Subsystem.RX && (code == StatusCode.BAD_DATA || code == StatusCode.FAILED) ->
                applyErrorFunction(request, failure, nowMs, out)
            subsystem == Subsystem.RX -> endWithFailure(request, failure, nowMs, out)
            request.command.kind == RequestKind.Command -> applyErrorFunction(request, failure, nowMs, out)
            else -> endWithFailure(request, failure, nowMs, out)
        }
    }

    private fun applyErrorFunction(request: Request, failure: CommandFailure, nowMs: Long, out: MutableList<QueueOutput>) {
        when (errorFunction(request, failure, nowMs, out)) {
            // A request that stays gets a new full wait.
            Outcome.Keep -> request.waitUntilMs = nowMs + request.command.fullWaitMs
            Outcome.Complete -> {
                clearCurrent(request)
                finishRequest(request, CommandResult.Completed, nowMs, out)
                channelHandlers(failure.subsystem, failure.code, nowMs, out)
            }
            Outcome.NotHandled -> endWithFailure(request, failure, nowMs, out)
        }
    }

    /** "Not handled": clear the request and give the message to the channel handlers. */
    private fun endWithFailure(request: Request, failure: CommandFailure, nowMs: Long, out: MutableList<QueueOutput>) {
        clearCurrent(request)
        finishRequest(request, CommandResult.Failed(failure), nowMs, out)
        channelHandlers(failure.subsystem, failure.code, nowMs, out)
    }

    /** The status functions of the commands. True means "complete". */
    private fun statusFunction(request: Request, subsystem: Int, code: Int, nowMs: Long, out: MutableList<QueueOutput>): Boolean =
        when (val command = request.command) {
            is AdapterCommand.SetMode -> {
                if (subsystem == Subsystem.INPUTS && code == StatusCode.OK) {
                    val readBack = Request(nextId++, AdapterCommand.GetMode(command.channel))
                    queue.addFirst(readBack)
                    values = values.fetching(ValueKey.Mode(command.channel), nowMs)
                    operations.firstOrNull { it.request === request }?.readBackId = readBack.id
                    out += QueueOutput.RestartNeeded(command.channel)
                    true
                } else {
                    false
                }
            }
            is AdapterCommand.RefAxis -> {
                if (subsystem != command.channel.referenceSubsystem) {
                    false
                } else if (code in REFERENCE_COMPLETE_CODES) {
                    true
                } else {
                    show(request, CommandFailure.of(subsystem, code), out)
                    false
                }
            }
            is AdapterCommand.ClearCounter -> subsystem == Subsystem.RX && code == StatusCode.RECEIVED
            AdapterCommand.Reboot -> {
                if (subsystem == Subsystem.RX && code == StatusCode.RECEIVED) {
                    out += QueueOutput.RestartAdapter(nowMs, nowMs + timing.restartCloseMs, nowMs + timing.restartConnectMs)
                    true
                } else {
                    false
                }
            }
            is AdapterCommand.GetField, is AdapterCommand.GetMode, is AdapterCommand.GetSig, AdapterCommand.Bel -> false
        }

    /** The error functions of the commands. */
    private fun errorFunction(request: Request, failure: CommandFailure, nowMs: Long, out: MutableList<QueueOutput>): Outcome =
        when (val command = request.command) {
            is AdapterCommand.SetMode -> {
                if (failure.subsystem == Subsystem.INPUTS) {
                    show(request, failure, out)
                    Outcome.Keep
                } else {
                    Outcome.NotHandled
                }
            }
            is AdapterCommand.RefAxis ->
                if (failure.subsystem == command.channel.referenceSubsystem && failure.code == StatusCode.USER_TIMEOUT) {
                    Outcome.Complete
                } else {
                    Outcome.NotHandled
                }
            is AdapterCommand.ClearCounter -> {
                show(request, failure, out)
                Outcome.NotHandled
            }
            is AdapterCommand.GetField -> {
                markFailed(ValueKey.Field(command.field), failure, nowMs)
                Outcome.NotHandled
            }
            is AdapterCommand.GetMode -> {
                markFailed(ValueKey.Mode(command.channel), failure, nowMs)
                Outcome.NotHandled
            }
            AdapterCommand.Reboot, is AdapterCommand.GetSig, AdapterCommand.Bel -> Outcome.NotHandled
        }

    private fun markFailed(key: ValueKey, failure: CommandFailure, nowMs: Long) {
        if (!twoWay) return
        val cause = if (failure.isTimeout) FailureCause.Timeout else FailureCause.Status(failure.code)
        values = values.failed(key, cause, nowMs)
    }

    private fun show(request: Request, failure: CommandFailure, out: MutableList<QueueOutput>) {
        if (request.shownFailure == null) request.shownFailure = failure
        out += QueueOutput.CommandFailed(request.id, request.command, failure)
    }

    /** The channel handlers: the reference marks of the channels `x y z w`. */
    private fun channelHandlers(subsystem: Int, code: Int, nowMs: Long, out: MutableList<QueueOutput>) {
        val channel = InputChannel.forReferenceSubsystem(subsystem) ?: return
        val state = when (code) {
            StatusCode.INITIALIZED, StatusCode.NOOP -> ReferenceState.InProgress
            StatusCode.FINISHED -> ReferenceState.Finished
            StatusCode.CANCELLED -> ReferenceState.Cancelled
            StatusCode.USER_TIMEOUT -> ReferenceState.TimedOut
            else -> return
        }
        if (twoWay) values = values.withReference(channel, state, nowMs)
        out += QueueOutput.Reference(channel, state)
    }

    /** Decodes the mask, stores the flags, and logs them. No effect on the queue. */
    private fun dirty(mask: Int, nowMs: Long, out: MutableList<QueueOutput>) {
        val channels = InputChannel.dirtyChannels(mask)
        if (twoWay) values = values.withDirty(mask, nowMs)
        out += QueueOutput.DirtyFlags(mask, channels)
        val hex = mask.toString(16).padStart(2, '0')
        out += QueueOutput.Log("Dirty axes mask 0x$hex: ${channels.joinToString(", ") { it.letter.toString() }}")
    }

    private fun clearCurrent(request: Request) {
        if (current === request) current = null
        request.waitUntilMs = null
        request.resendAtMs = null
    }

    /** A request ended. Gives its result, or the result of its command result limit. */
    private fun finishRequest(request: Request, result: CommandResult, nowMs: Long, out: MutableList<QueueOutput>) {
        idRetryAfterEnd(request, nowMs)
        val operation = operations.firstOrNull { it.request === request }
        if (operation != null) {
            // A mode write that completed waits for the read-back.
            if (result == CommandResult.Completed && operation.command is AdapterCommand.SetMode) return
            operations.remove(operation)
        }
        giveResult(request, result, out)
        val writeOperation = operations.firstOrNull { it.readBackId == request.id }
        if (writeOperation != null) {
            operations.remove(writeOperation)
            giveResult(writeOperation.request, if (modeConfirmed(writeOperation)) CommandResult.Completed else CommandResult.NotConfirmed, out)
        }
    }

    /** A mode write succeeds when the mode value shows the written mode. */
    private fun confirmModeWrites(out: MutableList<QueueOutput>) {
        operations.filter { it.readBackId != null && modeConfirmed(it) }.forEach { operation ->
            operations.remove(operation)
            giveResult(operation.request, CommandResult.Completed, out)
        }
    }

    private fun modeConfirmed(operation: Operation): Boolean {
        val command = operation.command as? AdapterCommand.SetMode ?: return false
        val value = values.modes.getValue(command.channel)
        return value.state is ValueState.Reported && value.value?.currentId == command.mode.id
    }

    /** The result limit: no result in 10000 ms. A request that did not go out leaves the queue. */
    private fun limit(operation: Operation, out: MutableList<QueueOutput>) {
        operations.remove(operation)
        queue.remove(operation.request)
        giveResult(operation.request, CommandResult.NotConfirmed, out)
    }

    private fun giveResult(request: Request, result: CommandResult, out: MutableList<QueueOutput>) {
        if (request.resultGiven) return
        request.resultGiven = true
        val given = if (result is CommandResult.Failed) request.shownFailure?.let { CommandResult.Failed(it) } ?: result else result
        out += QueueOutput.Result(request.id, request.command, given)
    }

    /**
     * A session drop: each request gets [CommandResult.Cancelled], the queue and the timers clear,
     * and each "Fetching" value becomes "Failed".
     */
    private fun drop(nowMs: Long, out: MutableList<QueueOutput>) {
        val requests = listOfNotNull(current) + queue
        current = null
        queue.clear()
        requests.forEach { giveResult(it, CommandResult.Cancelled, out) }
        operations.forEach { giveResult(it.request, CommandResult.Cancelled, out) }
        operations.clear()
        if (twoWay) values = values.dropped(nowMs)
        activationAtMs = null
        startedAtMs = null
        idRetryDone = false
        idRetryWaits.clear()
        idRetried.clear()
        lastWriteAtMs = null
    }

    private fun computeBusy(): Set<InputChannel> {
        val channels = LinkedHashSet<InputChannel>()
        current?.command?.channel?.let { channels += it }
        queue.forEach { request -> request.command.channel?.let { channels += it } }
        return channels
    }

    private fun keyOf(command: AdapterCommand): ValueKey? = when (command) {
        is AdapterCommand.GetField -> ValueKey.Field(command.field)
        is AdapterCommand.GetMode -> ValueKey.Mode(command.channel)
        else -> null
    }

    private companion object {
        /** The codes that complete `#ref_axis` on its reference subsystem. */
        val REFERENCE_COMPLETE_CODES: Set<Int> =
            setOf(StatusCode.INITIALIZED, StatusCode.NOOP, StatusCode.FINISHED, StatusCode.CANCELLED)
    }
}
