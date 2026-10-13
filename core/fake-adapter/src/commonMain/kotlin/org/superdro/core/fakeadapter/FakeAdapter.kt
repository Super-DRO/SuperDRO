package org.superdro.core.fakeadapter

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.Transport
import org.superdro.core.model.AdapterAddress
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/**
 * A simulated TouchDRO-compatible adapter.
 * It replaces the link, not the session: the same parser, queue, session, and UI run on its bytes.
 * Each link is a new [Transport] from [transport]. The adapter keeps its state from one link to
 * the next: the uptime, the modes, the counter zero, and a restart after `#reboot:1000;`.
 *
 * The adapter sends readout frames in the wire format at [FakeScenario.framesPerSecond], and it
 * answers the adapter commands with the documented answers. All waits use
 * the coroutine time, so a test with virtual time runs at once. The `@` time stamps, the motion,
 * and the uptime use [clock]: give the clock of the test scheduler in a test.
 *
 * The class is for one link at a time.
 *
 * @param scenario The data and the faults.
 * @param clock The clock of the motion, the time stamps, and the uptime.
 * @param seed The seed of the noise.
 */
class FakeAdapter(
    val scenario: FakeScenario,
    private val clock: Clock = MonotonicClock(),
    seed: Int = 1,
) {
    private val random = Random(seed)
    private val createdAtMs = clock.nowMs()
    private var bootAtMs = createdAtMs - INITIAL_UPTIME_MS
    private var rebootUntilMs: Long? = null
    private var opens = 0
    private val modes = InputChannel.entries.associateWithTo(LinkedHashMap()) { channel -> DEFAULT_MODES.getValue(channel) }
    private val zeroOffsets = HashMap<Char, Long>()
    private val transmissions = HashMap<String, Int>()
    private val receivedCommands = ArrayList<String>()

    /** The number of links that opened. */
    var links: Int = 0
        private set

    /** The command texts that the adapter received, in order, with no `"`, no `;`, and no BEL byte. */
    val received: List<String> get() = receivedCommands.toList()

    /** The current mode of [channel]. */
    fun mode(channel: InputChannel): InputMode = InputMode.forId(modes.getValue(channel).second)

    /** Makes the transport of a new link to this adapter. */
    fun transport(address: AdapterAddress): Transport = FakeAdapterTransport(this, address)

    // The link.

    internal fun open() {
        opens += 1
        if (opens <= scenario.faults.refuseOpens) throw LinkFailureException(LinkFailure.Refused, "The fake adapter refuses the open $opens.")
        val now = clock.nowMs()
        rebootUntilMs?.let { until ->
            if (now < until) throw LinkFailureException(LinkFailure.Refused, "The fake adapter restarts.")
            rebootUntilMs = null
        }
        links += 1
    }

    internal fun run(scope: ProducerScope<ByteArray>, commands: Channel<String>, end: CompletableDeferred<Throwable?>) {
        val link = LinkState(clock.nowMs(), links)
        // One ordered output for each link: one coroutine writes all bytes of the link, in the
        // order that they go into the outbox. Thus the order of an ack and its answer does not
        // depend on the dispatcher.
        val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        scope.launch { for (bytes in outbox) scope.send(bytes) }
        scenario.faults.dropAfterMs?.let { after ->
            scope.launch {
                delay(after)
                end.complete(LinkFailureException(LinkFailure.Lost, "The fake adapter dropped the link."))
            }
        }
        scope.launch {
            while (isActive) {
                val now = clock.nowMs()
                if (!link.stopped(now)) {
                    if (scenario.faults.noise) noise()?.let { bytes -> outbox.trySend(bytes) }
                    frame(link, now).takeIf { text -> text.isNotEmpty() }?.let { text -> outbox.trySend(text.encodeToByteArray()) }
                }
                delay(scenario.framePeriodMs)
            }
        }
        scope.launch {
            for (command in commands) {
                val now = clock.nowMs()
                if (link.stopped(now)) continue
                receivedCommands += command
                val (immediate, later) = answer(command, now, link).partition { (wait, _) -> wait <= 0L }
                // The pieces with no wait go out now, in the order of the sequence, for example
                // `$0aa3;` before `$060;`.
                immediate.forEach { (_, text) -> outbox.trySend(text.encodeToByteArray()) }
                if (later.isNotEmpty()) {
                    scope.launch {
                        var waited = 0L
                        for ((wait, text) in later.sortedBy { (wait, _) -> wait }) {
                            if (wait > waited) delay(wait - waited)
                            waited = wait
                            if (!link.stopped(clock.nowMs())) outbox.trySend(text.encodeToByteArray())
                        }
                    }
                }
                if (command == REBOOT && scenario.answers != null && scenario.faults.restartOnReboot) {
                    rebootUntilMs = now + scenario.faults.rebootTimeMs
                    bootAtMs = now + REBOOT_DELAY_MS
                    scope.launch {
                        delay(REBOOT_DELAY_MS)
                        end.complete(LinkFailureException(LinkFailure.Lost, "The fake adapter restarts."))
                    }
                }
            }
        }
    }

    /** The state of one link. */
    private inner class LinkState(val openedAtMs: Long, val number: Int) {
        val lastSent = HashMap<Char, Long>()
        var nextClose = 0
        var closedUntil: Long? = null
        var lastDirtyMs: Long? = null
        var dirtyIndex = 0

        /** True when the adapter sends no more bytes: the half-open link of [FakeFaults.stopDataAfterMs]. */
        fun stopped(now: Long): Boolean {
            val after = scenario.faults.stopDataAfterMs ?: return false
            return now - openedAtMs >= after
        }
    }

    // The readout frames.

    /** Gives the count of [axis] (`x`, `y`, or `z`) at [now], with the counter zero of `#clear_counter`. */
    fun count(axis: Char, now: Long = clock.nowMs()): Long {
        val elapsed = now - createdAtMs
        val (x, y) = when (scenario.motion) {
            Motion.Still -> START_X to START_Y
            Motion.SlowMove -> START_X + elapsed * SLOW_UM_PER_MS to START_Y
            Motion.FastMove -> START_X + elapsed * FAST_UM_PER_MS to START_Y
            Motion.HolePattern -> holePattern(elapsed)
        }
        val raw = when (axis) {
            'x' -> x
            'y' -> y
            else -> START_Z
        }
        return raw - (zeroOffsets[axis] ?: 0L)
    }

    private fun holePattern(elapsedMs: Long): Pair<Long, Long> {
        val step = (elapsedMs / HOLE_CYCLE_MS).toInt()
        val phase = elapsedMs % HOLE_CYCLE_MS
        fun hole(index: Int): Pair<Double, Double> {
            val angle = 2.0 * PI * (index % HOLES) / HOLES
            return HOLE_CENTER + HOLE_RADIUS * cos(angle) to HOLE_CENTER + HOLE_RADIUS * sin(angle)
        }
        val (fromX, fromY) = hole(step)
        val (toX, toY) = hole(step + 1)
        val fraction = if (phase < HOLE_MOVE_MS) phase.toDouble() / HOLE_MOVE_MS else 1.0
        return (fromX + (toX - fromX) * fraction).roundToLong() to (fromY + (toY - fromY) * fraction).roundToLong()
    }

    private fun frame(link: LinkState, now: Long): String {
        val text = StringBuilder()
        if (scenario.timestamps && !scenario.legacy) {
            val drift = if (scenario.clockDriftMs > 0L) (now - link.openedAtMs) / DRIFT_STEP_MS * scenario.clockDriftMs else 0L
            val adapterTime = maxOf(0L, now - bootAtMs - drift)
            text.append('@').append(adapterTime.toString(16).uppercase()).append(';')
        }
        val x = count('x', now)
        val values = buildList {
            add('X' to x)
            add('Y' to count('y', now))
            add('Z' to count('z', now))
            // The reference input W and the target input X have one time stamp. The target
            // has an error of 2 µm for each mm of travel.
            if (scenario.twoInputs) add('W' to x - (x - START_X) / TARGET_ERROR_DIVISOR)
            scenario.tachometerRpm?.let { rpm -> add('T' to rpm) }
        }
        values.forEach { (letter, value) ->
            if (!scenario.changesOnly || link.lastSent[letter] != value) {
                text.append(letter).append(value).append(';')
                link.lastSent[letter] = value
            }
        }
        if (!scenario.legacy) {
            probe(link, x, now)?.let { value -> text.append("P$value;") }
            scenario.timedProbe?.let { timed -> text.append("P").append(timed.valueAt(now - link.openedAtMs)).append(';') }
            if (scenario.dirtyFlags) {
                val last = link.lastDirtyMs
                if (last == null || now - last >= DIRTY_PERIOD_MS) {
                    link.lastDirtyMs = now
                    val mask = DIRTY_MASKS[link.dirtyIndex++ % DIRTY_MASKS.size]
                    text.append("\$32").append(mask.toString(16).padStart(2, '0')).append(';')
                }
            }
        }
        return text.toString()
    }

    /** Gives the probe value of this frame, or null for no probe message. */
    private fun probe(link: LinkState, x: Long, now: Long): Int? {
        val probe = scenario.probe ?: return null
        val until = link.closedUntil
        if (until != null) {
            if (now >= until) {
                link.closedUntil = null
                return 0
            }
            return if (probe.repeatClosed) 1 else null
        }
        val next = probe.closeAtX.getOrNull(link.nextClose) ?: return null
        if (x < next) return null
        link.nextClose += 1
        link.closedUntil = now + probe.closedMs
        return 1
    }

    private fun noise(): ByteArray? {
        if (random.nextInt(4) != 0) return null
        return when (random.nextInt(4)) {
            0 -> byteArrayOf(0x0D, 0x0A)
            1 -> byteArrayOf(0x00, 0xFF.toByte(), 0x7F)
            2 -> "X12 3;".encodeToByteArray()
            else -> "~%&".encodeToByteArray()
        }
    }

    // The commands.

    /** Gives the answers to [command] at [now] as (delay, text) pairs. */
    private fun answer(command: String, now: Long, link: LinkState): List<Pair<Long, String>> {
        val answers = scenario.answers ?: return emptyList()
        if (answers.badData) return listOf(0L to BAD_DATA)
        val count = transmissions[command] ?: 0
        transmissions[command] = count + 1
        if (count < answers.busyCount) return listOf(0L to BUSY)
        val ack = if (answers.ack) listOf(0L to ACK) else emptyList()
        val name = command.substringBefore(':')
        val args = command.substringAfter(':', "").split(',')
        val channel = args.firstOrNull()?.singleOrNull()?.let(InputChannel::forLetter)
        return when (name) {
            "#get" -> {
                val field = AdapterField.entries.firstOrNull { field -> field.getName == args.first() } ?: return ack + (0L to BAD_DATA)
                val wait = if (field in AdapterField.idGroup) answers.idDelayMs else 0L
                ack + (wait to "?${field.hexId}${fieldValue(field, now, link)};")
            }
            "#get_mode" -> {
                if (channel == null) return ack + (0L to INVALID_ARGUMENT)
                val (mask, mode) = modes.getValue(channel)
                ack + (0L to "?01${channel.letter}${hex4(mask)}${hex4(mode)};")
            }
            "#set_mode" -> {
                val id = args.getOrNull(1)?.toIntOrNull()
                if (channel == null || id == null) return ack + (0L to INVALID_ARGUMENT)
                val (mask, _) = modes.getValue(channel)
                if (InputMode.forId(id) == InputMode.Unknown || id and mask == 0) return ack + (0L to INVALID_ARGUMENT)
                modes[channel] = mask to id
                ack + (0L to "\$060;")
            }
            "#ref_axis" -> {
                val reference = channel?.referenceSubsystem ?: return ack + (0L to BAD_DATA)
                val subsystem = reference.toString(16)
                val end = answers.referenceTimeoutMs?.let { wait -> wait to "\$${subsystem}16;" }
                    ?: (answers.referenceFindMs to "\$${subsystem}a1;")
                ack + (0L to "\$${subsystem}a0;") + end
            }
            "#clear_counter" -> {
                if (channel == null) return ack + (0L to BAD_DATA)
                zeroOffsets[channel.letter] = (zeroOffsets[channel.letter] ?: 0L) + count(channel.letter, now)
                ack
            }
            "#reboot", "#get_sig" -> ack
            else -> listOf(0L to BAD_DATA)
        }
    }

    private fun fieldValue(field: AdapterField, now: Long, link: LinkState): String = when (field) {
        AdapterField.Mac -> scenario.identity.mac
        AdapterField.Uuid -> scenario.identity.uuid
        AdapterField.Features -> "1"
        AdapterField.Uptime -> {
            val seconds = if (scenario.uptimeGoesBack) {
                maxOf(1L, GOING_BACK_UPTIME_S - UPTIME_STEP_S * link.number)
            } else {
                (now - bootAtMs) / 1000L
            }
            seconds.toString(16)
        }
        AdapterField.Model -> MODEL
        AdapterField.Name -> NAME
        AdapterField.Version -> VERSION
        AdapterField.Build -> BUILD
        AdapterField.Date -> FIRMWARE_DATE.toString(16)
        AdapterField.Mode, AdapterField.Signature -> ""
    }

    private fun hex4(value: Int): String = value.toString(16).padStart(4, '0')

    companion object {
        /**
         * The base answer to `"#get:mac;"` ([FakeIdentity.BASE]). The MAC + 2 is the Bluetooth address
         * `24:6f:28:a1:b2:c5`. Each named scenario except `commands` has its own MAC.
         */
        const val MAC: String = "246f28a1b2c3"

        /** The base answer to `"#get:uuid;"` ([FakeIdentity.BASE]). */
        const val UUID: String = "c0ffee00-1234-4abc-8def-0123456789ab"

        /** The model starts with `tda-`, so the Adapter Details screen shows "Reference". */
        const val MODEL: String = "tda-fake"
        const val NAME: String = "SuperDRO fake adapter"
        const val VERSION: String = "1.0.0"
        const val BUILD: String = "fake"

        /** The firmware date 2026-01-01 00:00 UTC in seconds. */
        const val FIRMWARE_DATE: Long = 1_767_225_600L

        const val ACK: String = "\$0aa3;"
        const val BUSY: String = "\$0a20;"
        const val BAD_DATA: String = "!0a21;"
        const val INVALID_ARGUMENT: String = "!0603;"
        const val REBOOT: String = "#reboot:1000"

        /** The adapter ran for one hour before the first link. */
        const val INITIAL_UPTIME_MS: Long = 3_600_000L

        /** The adapter restarts this time after the ack of `#reboot:1000;`. */
        const val REBOOT_DELAY_MS: Long = 1_000L

        const val START_X: Long = 10_000L
        const val START_Y: Long = 20_000L
        const val START_Z: Long = -5_000L
        const val SLOW_UM_PER_MS: Long = 1L
        const val FAST_UM_PER_MS: Long = 50L

        private const val HOLES = 6
        private const val HOLE_CENTER = 50_000.0
        private const val HOLE_RADIUS = 25_000.0
        private const val HOLE_MOVE_MS = 1_000L
        private const val HOLE_CYCLE_MS = 2_000L
        private const val DRIFT_STEP_MS = 5_000L
        private const val DIRTY_PERIOD_MS = 1_000L
        private const val TARGET_ERROR_DIVISOR = 500L
        private const val GOING_BACK_UPTIME_S = 100_000L
        private const val UPTIME_STEP_S = 1_000L
        private val DIRTY_MASKS = listOf(0x05, 0x02, 0x00)

        /** The supported mask and the current mode of each channel at the start. */
        private val DEFAULT_MODES: Map<InputChannel, Pair<Int, Int>> = mapOf(
            InputChannel.X to (0x0F0F to InputMode.QuadratureAB.id),
            InputChannel.Y to (0x0F0F to InputMode.QuadratureAB.id),
            InputChannel.Z to (0x0F0F to InputMode.QuadratureAB.id),
            InputChannel.W to (0x0F0F to InputMode.QuadratureAB.id),
            InputChannel.U to (0x004F to InputMode.DigitalProbe.id),
            InputChannel.V to (0x0005 to InputMode.Tachometer.id),
        )
    }
}

/**
 * One link to a [FakeAdapter]. Collect [incoming] one time, after [open]. [close] ends the flow
 * with no failure. A drop and a restart end the flow with [LinkFailure.Lost].
 */
internal class FakeAdapterTransport(
    private val adapter: FakeAdapter,
    override val address: AdapterAddress,
) : Transport {

    private val commands = Channel<String>(Channel.UNLIMITED)
    private val end = CompletableDeferred<Throwable?>()

    @Volatile
    private var opened = false

    @Volatile
    private var closed = false

    override val incoming: Flow<ByteArray> = channelFlow {
        check(opened) { "Open the fake link before the read." }
        adapter.run(this, commands, end)
        val failure = end.await()
        if (failure != null) throw failure
        coroutineContext[Job]?.children?.forEach { child -> child.cancel() }
    }

    override suspend fun open() {
        if (closed) throw LinkFailureException(LinkFailure.Refused, "The fake link is closed.")
        adapter.open()
        opened = true
    }

    override suspend fun write(bytes: ByteArray) {
        if (!opened || closed || end.isCompleted) throw LinkFailureException(LinkFailure.Lost, "The fake link is not open.")
        val text = bytes.filter { byte -> byte != BEL && byte != QUOTE }.toByteArray().decodeToString()
        text.split(';').filter { command -> command.isNotEmpty() }.forEach { command -> commands.trySend(command) }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        commands.close()
        end.complete(null)
    }

    private companion object {
        const val BEL: Byte = 0x07
        const val QUOTE: Byte = 0x22
    }
}
