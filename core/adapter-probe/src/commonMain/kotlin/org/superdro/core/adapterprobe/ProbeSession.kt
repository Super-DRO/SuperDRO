package org.superdro.core.adapterprobe

import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.Transport
import org.superdro.core.model.AdapterAddress
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.CommandQueue
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.LinkPhase
import org.superdro.core.protocol.QueueOutput
import org.superdro.core.protocol.ValueKey
import org.superdro.core.protocol.ValueState
import org.superdro.core.util.Bytes

/** The state of the link for the Link screen. */
data class LinkInfo(
    val open: Boolean = false,
    val connected: Boolean = false,
    val rxBytes: Long = 0L,
    val validMessages: Long = 0L,
    val lastMessages: List<String> = emptyList(),
    val closedReason: String? = null,
)

/**
 * One run of the probe with one link at a time (`Session` of the tool). It is the link control
 * of the app:
 *
 * - The link is a SuperDRO [Transport] of [spec]. A new link gets a new [MessageFeed] with a new
 *   SuperDRO parser, and a new SuperDRO [CommandQueue].
 * - Each received block goes with its monotonic time (µs) to the `.bin` file and to the `.log`
 *   file of format 1 ([ProbeLogFormat]), then to the parser, the statistics, and the queue.
 * - The session writes nothing before the first valid message. The BEL byte goes out after 500 ms
 *   with no other write, only after the first valid message, and only while [keepAliveOn] is true
 *   (a test can turn it off, T05).
 * - The requests go through the SuperDRO command queue. When [usesQueue] is true, the queue gets
 *   `Connected` at the first valid message and sends the start queries 2000 ms later.
 *
 * The session runs on one thread at a time: all calls must come from [dispatcher] (use [within]).
 *
 * @param spec The link.
 * @param options The options of the test.
 * @param tag The mode tag of the log, for example `T03-idle`.
 * @param keepAlive The BEL byte state at the start.
 * @param level The level of the live messages.
 * @param env The platform parts.
 * @param parentScope The scope of the owner. The session makes its own child scope.
 * @param shell The UI or the test.
 * @param keep True when the phases keep their messages (T09).
 * @param usesQueue True when the queue gets `Connected` at the first valid message.
 */
class ProbeSession(
    val spec: LinkSpec,
    val options: TestOptions,
    val tag: String,
    keepAlive: Boolean,
    level: PrintLevel,
    private val env: ProbeEnvironment,
    parentScope: CoroutineScope,
    private val shell: ProbeShell,
    private val keep: Boolean = false,
    usesQueue: Boolean = false,
) {
    /** The dispatcher of the session: one thread at a time. */
    val dispatcher: CoroutineDispatcher =
        ((parentScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default).limitedParallelism(1)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + dispatcher)
    private val t0 = env.clock.nowNanos()
    private val startEpochMs = env.wallClockMs()
    private val files: ProbeFileSet = env.files.open("${ProbeTexts.fileStamp(startEpochMs, env.timeZone)}-$tag-${spec.kind}")

    /** The live message lines (the printer of the tool). */
    val printer: ProbePrinter = ProbePrinter(level) { line -> shell.output(line) }

    /** The file name of the `.log` file. */
    val logName: String get() = files.logName

    /** The file name of the `.bin` file. */
    val binName: String get() = files.binName

    /** The start time of the run in ms since 1970. */
    val startedAtEpochMs: Long get() = startEpochMs

    /** True while the BEL byte goes out (T05 turns it off). */
    var keepAliveOn: Boolean = keepAlive
        private set

    /** True when the queue gets `Connected` at the first valid message. */
    var usesQueue: Boolean = usesQueue
        private set

    var linkOpen: Boolean = false
        private set
    var openedT: Double? = null
        private set
    var closedT: Double? = null
        private set
    var closedReason: String? = null
        private set
    var connectedT: Double? = null
        private set
    var firstRxT: Double? = null
        private set
    var lastRxT: Double? = null
        private set
    var lastBelT: Double? = null
        private set

    /** The arrival times of the received blocks. */
    val rxTimes: ArrayDeque<Double> = ArrayDeque()

    /** The times of the valid messages. */
    val validTimes: ArrayDeque<Double> = ArrayDeque()

    /** The statistics since the start. */
    val statsAll: PhaseStats = PhaseStats("all", 0.0)

    /** The statistics of the current phase. */
    var statsCur: PhaseStats = PhaseStats("start", 0.0, keep)
        private set

    /** All phases in their order. */
    val phases: MutableList<PhaseStats> = mutableListOf(statsCur)

    /** The command queue of the current link, or null when no link is open. */
    var queue: CommandQueue? = null
        private set

    /**
     * Called before the first write of each request of the queue. T02 uses it to start the phases
     * "id group" and "inputs group" before the first write of each group.
     */
    var beforeWrite: ((AdapterCommand) -> Unit)? = null

    private var transport: Transport? = null
    private var generation = 0
    private var lastWriteT = 0.0
    private var belPending = false
    private val lastValues = LinkedHashMap<String, Pair<Long, Double>>()
    private val readerHold = MutableStateFlow(false)
    private val events = ArrayDeque<Pair<Long, ProbeMessage>>()
    private val eventCount = MutableStateFlow(0L)
    private var eventSeq = 0L
    private val changes = MutableStateFlow(0L)
    private var feed = MessageFeed()
    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var writes: Channel<PendingWrite>? = null
    private val requests = HashMap<Long, ProbeRequest>()
    private val requestOrder = ArrayList<Long>()
    private var currentRequestId: Long? = null
    private var timerJob: Job? = null
    private var queueConnected = false
    private var lastQueueMs = 0L
    private var finished = false
    private var blocksSinceFlush = 0
    private var rxTotal = 0L
    private var validTotal = 0L
    private val lastTexts = ArrayDeque<String>()
    private val _linkInfo = MutableStateFlow(LinkInfo())
    private var mergedValues: AdapterValues = AdapterValues()

    /** The link state for the Link screen. */
    val linkInfo: StateFlow<LinkInfo> = _linkInfo.asStateFlow()

    /** The adapter values of all links of this run: the last reported value of each field. */
    val adapterValues: AdapterValues get() = mergedValues

    private class PendingWrite(val bytes: ByteArray, val bel: Boolean, val onDone: (Double?) -> Unit)

    init {
        // Work-around for core:protocol: `ValueKey.all` gets a null entry when `ValueKey.Dirty` is
        // the first key that the JVM initializes (a `$32` status before each query). Then
        // `AdapterValues.dropped` throws a NullPointerException. The list is made here first.
        check(ValueKey.all.isNotEmpty())
        val wall = ProbeTexts.utcSecond(startEpochMs)
        ProbeLogFormat.header(wall, tag, options.label, spec.describe(), keepAliveOn, env.writer).forEach(files::writeLogLine)
        files.flush()
        scope.launch { keepAliveLoop() }
    }

    /** Runs [block] on the dispatcher of the session. */
    suspend fun <T> within(block: suspend ProbeSession.() -> T): T = withContext(dispatcher) { block() }

    // Time and log.

    /** The time in µs since the start of the run. */
    fun nowUs(): Long = (env.clock.nowNanos() - t0) / 1000L

    /** The time in seconds since the start of the run, as the analysis reads the log ([ProbeLogFormat.seconds]). */
    fun now(): Double = ProbeLogFormat.seconds(nowUs())

    /** The wall clock in ms since 1970. */
    fun wallClockMs(): Long = env.wallClockMs()

    /** The paired Bluetooth devices, for the MAC + 2 check. */
    suspend fun pairedDevices(): List<PairedAdapter> = env.pairedDevices()

    /** Writes a note line in the log at the time [us] (default: now). */
    fun note(text: String, us: Long = nowUs()) {
        files.writeLogLine(ProbeLogFormat.note(us, text))
        files.flush()
    }

    /** Shows [text] and writes it in the log (`say`). */
    fun say(text: String) {
        printer.line(text)
        shell.step(text)
        note(text)
    }

    /**
     * Asks the user. The log gets the question and the answer. The result has no spaces at the
     * ends. A [caution] (with its word `CAUTION:` or `WARNING:`) goes to the log and the step text
     * first, and the dialog shows it above the question.
     */
    suspend fun ask(prompt: String, kind: PromptKind = PromptKind.Next, currentMode: String? = null, caution: String? = null): String {
        if (caution != null) say(caution)
        val answer = shell.ask(PromptRequest(kind, prompt, caution = caution, currentMode = currentMode))
        note("question: ${prompt.trim()} answer: ${PyText.repr(answer)}")
        return answer.trim()
    }

    /**
     * Shows the caution and asks for the typed `yes` before a command that changes the adapter
     * state. Gives true when the user typed `yes`.
     */
    suspend fun confirm(text: String, caution: String, currentMode: String? = null): Boolean {
        say("CAUTION: $caution")
        val prompt = "Type yes to send $text (other text cancels): "
        val answer = shell.ask(PromptRequest(PromptKind.TypedYes, prompt, caution = caution, command = text, currentMode = currentMode))
        note("question: ${prompt.trim()} answer: ${PyText.repr(answer)}")
        val ok = answer.trim().lowercase() == "yes"
        note("confirmation for $text: ${if (ok) "yes" else "cancelled"}")
        if (!ok) say("Cancelled. The tool did not send $text.")
        return ok
    }

    // Statistics.

    private fun targets(): List<PhaseStats> = listOf(statsAll, statsCur)

    /** Ends the current phase and starts the phase [name]. */
    fun phase(name: String): PhaseStats {
        // The note has the start time of the phase, so that the analysis gives the same durations.
        val us = nowUs()
        val t = ProbeLogFormat.seconds(us)
        val old = statsCur
        old.close(t)
        val st = PhaseStats(name, t, keep)
        st.groupMode = old.groupMode
        st.groupId = old.groupId
        phases += st
        statsCur = st
        note("phase $name", us)
        return st
    }

    /** The last value of each input. */
    fun snapshot(): Map<String, Long> = lastValues.mapValues { (_, value) -> value.first }

    // The link.

    /**
     * Opens a new link. Gives the time of the open in seconds. Throws a [LinkFailureException] when
     * the open fails.
     */
    suspend fun open(): Double {
        env.beforeOpen(spec.address)
        val link = spec.factory.create(spec.address)
        val tA = now()
        try {
            withContext(env.linkIo) { link.open() }
        } catch (failure: CancellationException) {
            closeQuietly(link)
            throw failure
        } catch (failure: LinkFailureException) {
            closeQuietly(link)
            throw failure
        } catch (failure: Exception) {
            closeQuietly(link)
            throw LinkFailureException(LinkFailure.Refused, failure.message ?: failure.toString(), failure)
        }
        // The note "link open" has the time of the open, as the link events of the statistics.
        val usB = nowUs()
        val tB = ProbeLogFormat.seconds(usB)
        generation++
        val number = generation
        transport = link
        feed = MessageFeed()
        connectedT = null
        firstRxT = null
        linkOpen = true
        openedT = tB
        closedT = null
        closedReason = null
        lastWriteT = tB
        belPending = false
        lastValues.clear()
        newQueue()
        targets().forEach { st -> st.linkReset("link open", tB) }
        note("link open: ${spec.describe()} (open took ${PyText.ms(tB - tA)} ms)", usB)
        val channel = Channel<PendingWrite>(Channel.UNLIMITED)
        writes = channel
        writerJob = scope.launch { writer(link, channel, number) }
        readerJob = scope.launch { reader(link, number) }
        publishLink()
        changed()
        return tB - tA
    }

    /** Closes the link. [abort] (T15) only changes the note: the Transport contract has no abort. */
    suspend fun close(abort: Boolean = false) {
        val wasOpen = linkOpen
        linkOpen = false
        generation++
        readerJob?.cancel()
        readerJob = null
        val link = transport
        dropLink()
        if (link != null) withContext(env.linkIo + NonCancellable) { runCatching { link.close() } }
        if (wasOpen) {
            closedT = now()
            closedReason = "closed by the tool"
            // The Transport contract has no abort (no SO_LINGER reset), so each close is a normal close.
            note("link closed by the tool (${if (abort) "normal close: the SuperDRO transport has no abort" else "normal close"})")
        }
        publishLink()
        changed()
    }

    /** Opens the link again each [periodS] until it opens or [limitS] ends. Gives (success, attempts). */
    suspend fun reopenLoop(limitS: Double, periodS: Double = 1.0): Pair<Boolean, List<Pair<Double, String>>> {
        val attempts = ArrayList<Pair<Double, String>>()
        val end = now() + limitS
        while (true) {
            try {
                open()
                attempts += now() to "open"
                return true to attempts
            } catch (failure: LinkFailureException) {
                val text = failure.message ?: failure.failure.toString()
                attempts += now() to text
                note("open failed: $text")
            }
            if (now() + periodS > end) return false to attempts
            delay(millis(periodS))
        }
    }

    private suspend fun reader(link: Transport, number: Int) {
        val reason = try {
            link.incoming.flowOn(env.linkIo).collect { bytes ->
                if (readerHold.value) readerHold.first { held -> !held }
                if (number == generation) rx(bytes, nowUs())
            }
            "the adapter closed the link (end of stream)"
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: LinkFailureException) {
            failure.message ?: failure.failure.toString()
        } catch (failure: Exception) {
            failure.message ?: failure.toString()
        }
        closedByLink(reason, number)
    }

    private suspend fun writer(link: Transport, channel: Channel<PendingWrite>, number: Int) {
        for (write in channel) {
            if (number != generation) {
                write.onDone(null)
                continue
            }
            try {
                withContext(env.linkIo) { link.write(write.bytes) }
            } catch (failure: CancellationException) {
                write.onDone(null)
                throw failure
            } catch (failure: Exception) {
                write.onDone(null)
                closedByLink("write failed: ${failure.message ?: failure.toString()}", number)
                return
            }
            val us = nowUs()
            val t = ProbeLogFormat.seconds(us)
            lastWriteT = t
            files.writeLogLine(ProbeLogFormat.block(us, ProbeLogFormat.TX, write.bytes))
            if (write.bel) {
                lastBelT = t
                belPending = false
            }
            targets().forEach { st -> st.addTx(write.bytes.size, write.bel, t) }
            if (!write.bel) printer.line("${PyText.right(PyText.ms(t), 10)} ms  TX ${ProbeTexts.printable(write.bytes)}")
            write.onDone(t)
        }
    }

    private fun closedByLink(reason: String, number: Int) {
        if (number != generation || !linkOpen) return
        linkOpen = false
        val t = now()
        closedT = t
        closedReason = reason
        targets().forEach { st -> st.linkEvents += "${PyText.ms(t)} ms: link closed: $reason" }
        say("${PyText.right(PyText.ms(t), 10)} ms  The link closed: $reason")
        val link = transport
        dropLink()
        if (link != null) scope.launch { withContext(env.linkIo + NonCancellable) { runCatching { link.close() } } }
        publishLink()
        changed()
    }

    /** The queue gets `Dropped`; the writer and the timer stop. */
    private fun dropLink() {
        queue?.let { q -> handle(q.onLinkPhase(LinkPhase.Dropped, queueMs()), null) }
        queue = null
        queueConnected = false
        currentRequestId = null
        timerJob?.cancel()
        timerJob = null
        writes?.close()
        writes = null
        writerJob?.cancel()
        writerJob = null
        transport = null
        belPending = false
    }

    private fun closeQuietly(link: Transport) {
        scope.launch { withContext(env.linkIo + NonCancellable) { runCatching { link.close() } } }
    }

    /** Writes [bytes] and gives the time after the write, or null when the link closed. */
    suspend fun write(bytes: ByteArray, bel: Boolean = false): Double? {
        val result = kotlinx.coroutines.CompletableDeferred<Double?>()
        enqueueWrite(bytes, bel) { t -> result.complete(t) }
        return result.await()
    }

    private fun enqueueWrite(bytes: ByteArray, bel: Boolean, onDone: (Double?) -> Unit) {
        val channel = writes
        if (channel == null || !linkOpen) {
            onDone(null)
            return
        }
        lastWriteT = maxOf(lastWriteT, now())
        if (bel) belPending = true
        if (channel.trySend(PendingWrite(bytes, bel, onDone)).isFailure) onDone(null)
    }

    private suspend fun keepAliveLoop() {
        // The protocol: the byte 0x07 after 500 ms with no other write, only after the first valid
        // message.
        while (true) {
            if (keepAliveOn && linkOpen && connectedT != null && !belPending) {
                val idle = now() - lastWriteT
                if (idle >= BEL_PERIOD_S) {
                    enqueueWrite(byteArrayOf(Bytes.BEL), bel = true) { }
                    continue
                }
                delay(millis(minOf(BEL_PERIOD_S - idle, POLL_S)))
            } else {
                delay(millis(POLL_S))
            }
        }
    }

    /** Turns the BEL byte on or off (T05, the console `keepalive on|off`). */
    fun setKeepAlive(on: Boolean) {
        keepAliveOn = on
        note("keep-alive ${if (on) "on" else "off"}")
    }

    /** Stops or starts the read of the link (T14: the first link with no read and no write). */
    fun holdReader(hold: Boolean) {
        readerHold.value = hold
    }

    private fun rx(data: ByteArray, us: Long) {
        val t = ProbeLogFormat.seconds(us)
        files.writeBin(data)
        files.writeLogLine(ProbeLogFormat.block(us, ProbeLogFormat.RX, data))
        if (++blocksSinceFlush >= FLUSH_BLOCKS) {
            blocksSinceFlush = 0
            files.flush()
        }
        if (firstRxT == null) {
            firstRxT = t
            changed()
        }
        lastRxT = t
        rxTimes.addLast(t)
        if (rxTimes.size > TIME_CAP) rxTimes.removeFirst()
        rxTotal += data.size
        val result = feed.feed(data, t, us / 1000L)
        val targets = targets()
        targets.forEach { st -> st.addRx(data.size, result.dropped, t) }
        for (m in result.messages) {
            targets.forEach { st -> st.add(m) }
            if (m.kind == MessageKind.Count || m.kind == MessageKind.Bool) lastValues[m.key] = (m.number ?: 0L) to m.t
            if (m.valid) {
                validTimes.addLast(m.t)
                if (validTimes.size > TIME_CAP) validTimes.removeFirst()
                validTotal++
            }
            if (m.valid && connectedT == null) {
                connectedT = m.t
                lastWriteT = maxOf(lastWriteT, m.t)
                note("first valid message ${PyText.repr(m.text)}: state Connected")
                if (usesQueue) connectQueue()
                changed()
            }
            if (m.kind != MessageKind.Count && m.kind != MessageKind.Bool && m.kind != MessageKind.Time) pushEvent(m)
            printer.message(m)
            if (m.kind != MessageKind.Time && m.kind != MessageKind.Unknown) {
                lastTexts.addLast(m.text)
                if (lastTexts.size > LAST_TEXTS) lastTexts.removeFirst()
            }
            toQueue(m)
        }
        if (result.rejected.isNotEmpty()) {
            targets.forEach { st -> st.addRejected(result.rejected) }
            printer.rejected(result.rejected, t)
        }
        publishLink()
    }

    private fun publishLink() {
        _linkInfo.value = LinkInfo(linkOpen, linkOpen && connectedT != null, rxTotal, validTotal, lastTexts.toList(), closedReason)
    }

    // The waits.

    private fun changed() {
        changes.value = changes.value + 1
    }

    /** Waits until [condition] is true, or [timeoutS] ends. Gives the value of [condition]. */
    suspend fun waitFor(timeoutS: Double, condition: () -> Boolean): Boolean {
        if (condition()) return true
        withTimeoutOrNull(millis(timeoutS)) { changes.first { condition() } }
        return condition()
    }

    /** Waits until the first valid message. Gives false at a timeout, and at once when the link closes. */
    suspend fun waitConnected(timeoutS: Double): Boolean {
        waitFor(timeoutS) { connectedT != null || !linkOpen }
        return connectedT != null
    }

    /** Waits until 2 s after the first valid message (the queue start). */
    suspend fun waitQueueStart(): Boolean {
        val connected = connectedT ?: return false
        val rest = connected + QUEUE_START_S - now()
        if (rest > 0) delay(millis(rest))
        return linkOpen
    }

    /** Records for [seconds]. Gives false when the link closed (with [stopOnClose]). The UI shows a countdown. */
    suspend fun hold(seconds: Double, stopOnClose: Boolean = true): Boolean {
        shell.countdown(env.clock.nowNanos() + (seconds * 1e9).toLong())
        try {
            if (stopOnClose) {
                if (waitFor(seconds) { !linkOpen }) return false
            } else {
                delay(millis(seconds))
            }
        } finally {
            shell.countdown(null)
        }
        return linkOpen
    }

    private fun pushEvent(m: ProbeMessage) {
        eventSeq++
        events.addLast(eventSeq to m)
        if (events.size > EVENT_LIMIT) events.removeFirst()
        eventCount.value = eventSeq
    }

    /** Gives the next status, error, info, signature, or unknown message, or null after [timeoutS]. */
    suspend fun nextEvent(timeoutS: Double): ProbeMessage? {
        events.removeFirstOrNull()?.let { return it.second }
        if (timeoutS <= 0.0) return null
        withTimeoutOrNull(millis(timeoutS)) { eventCount.first { events.isNotEmpty() } }
        return events.removeFirstOrNull()?.second
    }

    private fun drainEvents() {
        events.clear()
    }

    /** A request of the tool reads the events up to its result. The events after the result stay. */
    private fun dropEventsUntil(seq: Long) {
        while (true) {
            val first = events.firstOrNull() ?: return
            if (first.first > seq) return
            events.removeFirst()
        }
    }

    // The queue.

    private fun queueMs(): Long {
        lastQueueMs = maxOf(lastQueueMs, nowUs() / 1000L)
        return lastQueueMs
    }

    private fun newQueue() {
        queue = CommandQueue()
        queueConnected = false
        requests.clear()
        requestOrder.clear()
        currentRequestId = null
        timerJob?.cancel()
        timerJob = null
    }

    /** Gives the queue `Connected` now, when the link is connected. Then the start queries go out 2000 ms later. */
    fun enableQueue() {
        usesQueue = true
        if (linkOpen && connectedT != null) connectQueue()
    }

    private fun connectQueue() {
        val q = queue ?: return
        if (queueConnected) return
        queueConnected = true
        handle(q.onLinkPhase(LinkPhase.Connected, queueMs()), null)
    }

    /** True when the queue sends nothing now and has nothing to send: it started, and no request is current or queued. */
    val queueIdle: Boolean
        get() {
            val q = queue ?: return true
            if (!queueConnected) return true
            return q.startedAtMs != null && q.currentCommand == null && q.queuedCommands.isEmpty()
        }

    /** Waits until the queue started and sent all requests (the start queries). Gives false at a timeout. */
    suspend fun awaitQueueIdle(timeoutS: Double = QUEUE_IDLE_LIMIT_S): Boolean = waitFor(timeoutS) { queueIdle }

    /** The requests that the queue sent without a call of [request], in their order: the start queries, the read-backs, and the "id" retries. */
    fun autoRequests(): List<ProbeRequest> = requestOrder.mapNotNull { id -> requests[id] }.filter { it.auto }

    /** Gives the first request of this link with [command] that the queue sent by itself, or null. */
    fun startQuery(command: AdapterCommand): ProbeRequest? = autoRequests().firstOrNull { it.command == command }

    private fun toQueue(m: ProbeMessage) {
        val q = queue ?: return
        val current = currentRequestId?.let { id -> requests[id] }
        if (current != null && current.answer == null) {
            if (m.isAck() && current.tAck == null) current.tAck = m.t
            if (m.isBusy()) current.busy++
        }
        handle(q.onMessage(m.message, queueMs()), m)
    }

    private fun handle(outputs: List<QueueOutput>, cause: ProbeMessage?) {
        for (output in outputs) {
            when (output) {
                is QueueOutput.Write -> queueWrite(output)
                is QueueOutput.Timer -> scheduleTimer(output.atMs)
                is QueueOutput.Result -> queueResult(output.requestId, output.result, cause)
                is QueueOutput.CommandFailed -> requests[output.requestId]?.let { r ->
                    if (r.answer == null && cause != null) {
                        r.answer = cause
                        r.error = true
                    }
                }
                is QueueOutput.RestartNeeded -> currentRequestId?.let { id -> requests[id] }?.let { r ->
                    if (r.answer == null && cause != null && r.command is AdapterCommand.SetMode) r.answer = cause
                }
                is QueueOutput.ValuesChanged -> mergedValues = merge(mergedValues, output.values)
                else -> Unit
            }
        }
        changed()
    }

    private fun queueWrite(output: QueueOutput.Write) {
        val request = requests.getOrPut(output.requestId) {
            requestOrder += output.requestId
            ProbeRequest(output.command.text, output.command, auto = true)
        }
        if (output.transmission == 1) {
            currentRequestId = output.requestId
            beforeWrite?.invoke(output.command)
        }
        enqueueWrite(output.bytes, bel = false) { t ->
            if (output.transmission == 1 && request.tSend == null && t != null) request.tSend = t
        }
    }

    private fun scheduleTimer(atMs: Long) {
        timerJob?.cancel()
        val q = queue ?: return
        timerJob = scope.launch {
            val wait = atMs - nowUs() / 1000L
            if (wait > 0) delay(wait)
            if (q === queue) handle(q.onTimer(queueMs()), null)
        }
    }

    private fun queueResult(id: Long, result: CommandResult, cause: ProbeMessage?) {
        val r = requests[id] ?: return
        if (r.result != null) return
        r.result = result
        r.consumedSeq = eventSeq
        when (result) {
            CommandResult.Completed -> if (r.answer == null) r.answer = cause
            is CommandResult.Failed -> {
                if (result.failure.isTimeout && r.answer == null) {
                    r.timeout = true
                } else {
                    if (r.answer == null) r.answer = cause
                    r.error = true
                }
            }
            CommandResult.NotConfirmed -> if (r.answer == null) r.timeout = true
            CommandResult.Cancelled, CommandResult.NotSent -> r.closed = true
        }
        r.tAnswer = r.answer?.t
        if (currentRequestId == id) currentRequestId = null
        r.finish()
        if (r.auto) requestDone(r)
    }

    private fun requestDone(r: ProbeRequest) {
        val text = "request " + r.summary()
        printer.line("${PyText.right(PyText.ms(now()), 10)} ms  $text")
        note(text)
    }

    /**
     * Sends [command] through the SuperDRO command queue and waits for its result. Then it records
     * the messages of the next [tailS] seconds.
     */
    suspend fun request(command: AdapterCommand, tailS: Double = 0.0): ProbeRequest {
        drainEvents()
        val r = ProbeRequest(command.text, command, auto = false)
        val q = queue
        if (q == null || !linkOpen) {
            r.closed = true
            requestDone(r)
            return r
        }
        if (!usesQueue) enableQueue()
        val submission = q.submit(command, queueMs())
        requests[submission.requestId] = r
        requestOrder += submission.requestId
        handle(submission.outputs, null)
        r.awaitDone()
        dropEventsUntil(r.consumedSeq)
        if (tailS > 0) collectTail(r, tailS)
        requestDone(r)
        return r
    }

    /** Sends `"#get:<field>;"` through the queue. */
    suspend fun get(field: AdapterField): ProbeRequest = request(AdapterCommand.GetField(field))

    /**
     * Writes a raw text that is not a SuperDRO command (T06, the console `raw`). The text does not
     * go through the queue, because the queue sends only the SuperDRO commands ([AdapterCommand]).
     * The session waits until the queue is idle, writes the bytes once, and records the answer with
     * the waits of the queue: 500 ms to the first answer, then [fullWaitS] after the ack. A Busy
     * answer ends the request, because only the queue sends a request again.
     */
    suspend fun rawRequest(text: String, data: ByteArray, fullWaitS: Double = 2.0, tailS: Double = 0.0): ProbeRequest {
        awaitQueueIdle()
        drainEvents()
        val r = ProbeRequest(text, null, auto = false)
        val send = write(data)
        if (send == null) {
            r.closed = true
            requestDone(r)
            return r
        }
        r.tSend = send
        var deadline = send + FIRST_WAIT_S
        while (true) {
            val now = now()
            if (now >= deadline) {
                r.timeout = true
                break
            }
            val m = nextEvent(minOf(POLL_S, deadline - now))
            if (m == null) {
                if (!linkOpen) {
                    r.closed = true
                    break
                }
                continue
            }
            if (m.kind == MessageKind.Unknown || (m.isStatusOrError && m.sub == DIRTY_AXES)) continue
            if (m.isAck()) {
                if (r.tAck == null) r.tAck = m.t
                deadline = now() + fullWaitS
                continue
            }
            if (m.isBusy()) {
                r.busy++
                r.answer = m
                r.error = true
                break
            }
            if (m.errorPath()) {
                r.answer = m
                r.error = true
                break
            }
            if (m.kind == MessageKind.Info || m.kind == MessageKind.Signature) {
                r.answer = m
                break
            }
        }
        r.tAnswer = r.answer?.t
        if (tailS > 0) collectTail(r, tailS)
        requestDone(r)
        return r
    }

    private suspend fun collectTail(r: ProbeRequest, tailS: Double) {
        val end = now() + tailS
        while (true) {
            val rest = end - now()
            if (rest <= 0) break
            nextEvent(minOf(POLL_S, rest))?.let { m -> r.tail += m }
        }
    }

    // The second link of T14.

    /** The result of a second link (`second_link_probe`). */
    class SecondLink(
        val error: String?,
        val openS: Double?,
        val firstValidS: Double?,
        val valid: Int,
        val bytes: Long,
    )

    /** Opens a second link to the same adapter while the first link stays open, and records it for [durationS]. */
    suspend fun secondLinkProbe(durationS: Double): SecondLink {
        val link = spec.factory.create(spec.address)
        val tA = now()
        try {
            withContext(env.linkIo) { link.open() }
        } catch (failure: CancellationException) {
            closeQuietly(link)
            throw failure
        } catch (failure: Exception) {
            val text = failure.message ?: failure.toString()
            note("second link: open failed: $text")
            closeQuietly(link)
            return SecondLink(text, null, null, 0, 0L)
        }
        val tB = now()
        note("second link open (open took ${PyText.ms(tB - tA)} ms)")
        val second = MessageFeed()
        var error: String? = null
        var firstValid: Double? = null
        var valid = 0
        var bytes = 0L
        try {
            withTimeoutOrNull(millis(durationS)) {
                try {
                    link.incoming.flowOn(env.linkIo).collect { data ->
                        val us = nowUs()
                        val t = ProbeLogFormat.seconds(us)
                        files.writeLogLine(ProbeLogFormat.block(us, ProbeLogFormat.RX2, data))
                        bytes += data.size
                        for (m in second.feed(data, t, us / 1000L).messages) {
                            if (m.valid) {
                                valid++
                                if (firstValid == null) firstValid = t - tB
                            }
                        }
                    }
                    error = "closed: the adapter closed the link (end of stream)"
                } catch (failure: LinkFailureException) {
                    error = "closed: ${failure.message ?: failure.failure}"
                }
            }
        } finally {
            withContext(env.linkIo + NonCancellable) { runCatching { link.close() } }
            note("second link closed by the tool")
        }
        return SecondLink(error, tB - tA, firstValid, valid, bytes)
    }

    // The end.

    /** Closes the link and the files. The log ends with `# end`. */
    suspend fun finish() {
        if (finished) return
        finished = true
        withContext(NonCancellable) {
            close()
            statsCur.close(now())
            files.writeLogLine(ProbeLogFormat.END_LINE)
            files.close()
            job.cancel()
        }
    }

    /** The address of the link. */
    val address: AdapterAddress get() = spec.address

    private fun millis(seconds: Double): Long = maxOf(1L, ceil(seconds * 1000.0).toLong())

    companion object {
        /** The BEL period: 500 ms with no other write. */
        const val BEL_PERIOD_S: Double = 0.5

        /** The queue becomes active 2000 ms after the first valid message. */
        const val QUEUE_START_S: Double = 2.0

        /** The first wait of a request (500 ms). */
        const val FIRST_WAIT_S: Double = 0.5

        private const val POLL_S = 0.05
        private const val TIME_CAP = 200_000
        private const val LAST_TEXTS = 12
        private const val FLUSH_BLOCKS = 64
        private const val DIRTY_AXES = 0x32
        private const val QUEUE_IDLE_LIMIT_S = 90.0
        private const val EVENT_LIMIT = 10_000

        /** Keeps each reported value of [old], and each new reported value of [new]. */
        internal fun merge(old: AdapterValues, new: AdapterValues): AdapterValues {
            fun <T> pick(a: org.superdro.core.protocol.AdapterValue<T>, b: org.superdro.core.protocol.AdapterValue<T>) =
                if (b.value != null && b.state is ValueState.Reported) b else if (a.value != null) a else b
            return AdapterValues(
                uuid = pick(old.uuid, new.uuid),
                name = pick(old.name, new.name),
                model = pick(old.model, new.model),
                mac = pick(old.mac, new.mac),
                version = pick(old.version, new.version),
                build = pick(old.build, new.build),
                date = pick(old.date, new.date),
                uptime = pick(old.uptime, new.uptime),
                features = pick(old.features, new.features),
                modes = old.modes.mapValues { (channel, value) -> pick(value, new.modes.getValue(channel)) },
                references = new.references,
                dirty = new.dirty,
            )
        }
    }
}
