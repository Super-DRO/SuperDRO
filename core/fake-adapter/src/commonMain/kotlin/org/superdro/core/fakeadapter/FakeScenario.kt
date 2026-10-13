package org.superdro.core.fakeadapter

/** The motion of the fake axes. All counts are µm at 25400 counts per inch. */
enum class Motion {
    /** X, Y, and Z do not change. */
    Still,

    /** X moves 1000 µm each second (60 mm/min). */
    SlowMove,

    /** X moves 50000 µm each second (3 m/min). */
    FastMove,

    /** X and Y visit the 6 holes of a hole circle: 1000 ms to move, 1000 ms at each hole. */
    HolePattern,
}

/**
 * The probe of a scenario.
 *
 * @property closeAtX The X counts where the probe closes. X must move over them.
 * @property closedMs The time that the probe stays closed.
 * @property repeatClosed True when the adapter sends `P1;` in each frame while the probe is
 *   closed. False when it sends `P1;` only at the change.
 */
data class ProbeScenario(
    val closeAtX: List<Long> = listOf(10_500L, 11_500L),
    val closedMs: Long = 100L,
    val repeatClosed: Boolean = false,
)

/**
 * A probe that follows the time from the open of the link (test T09 of the adapter probe app).
 * The adapter sends `P<value>;` in each frame. The steps repeat: each step is a time in ms and the
 * probe value for that time.
 *
 * @property steps The times in ms and the values 0 (open) or 1 (closed). Each time is more than 0.
 */
data class TimedProbe(val steps: List<Pair<Long, Int>>) {
    init {
        require(steps.isNotEmpty() && steps.all { (ms, value) -> ms > 0L && (value == 0 || value == 1) }) {
            "Each step of a timed probe needs a time of more than 0 ms and the value 0 or 1."
        }
    }

    /** The time of one cycle of the steps. */
    val cycleMs: Long get() = steps.sumOf { (ms, _) -> ms }

    /** Gives the probe value [elapsedMs] after the open of the link. */
    fun valueAt(elapsedMs: Long): Int {
        var rest = elapsedMs.mod(cycleMs)
        for ((ms, value) in steps) {
            if (rest < ms) return value
            rest -= ms
        }
        return steps.last().second
    }

    companion object {
        /**
         * The four steps of T09: open 5.5 s, closed 9 s, open
         * 5.5 s, then 10 taps of 0.5 s closed and 0.5 s open. The cycle is 30 s. The closed step
         * starts 0.5 s after the end of the step "open" of T09 and ends 0.5 s before the end of
         * the step "closed". Thus each step of T09 keeps its main value when the user pushes Next
         * up to 0.5 s after the link opens.
         */
        val T09: TimedProbe = TimedProbe(
            listOf(5_500L to 0, 9_000L to 1, 5_500L to 0) + List(10) { listOf(500L to 1, 500L to 0) }.flatten(),
        )
    }
}

/**
 * The answers of the fake adapter to the commands.
 *
 * @property ack True when the adapter sends `$0aa3;` before each answer. With false, the commands
 *   whose answer is the ack (`#clear_counter`, `#reboot`) never complete.
 * @property busyCount The first transmissions of each command that get the RX Busy `$0a20;`
 *   (1 for "Busy once", 3 for "Busy three times").
 * @property badData True when each command gets `!0a21;` (Bad data).
 * @property idDelayMs The delay of each answer of the "id" group. 1500 ms gives a slow "id" group
 *   of more than 5000 ms.
 * @property referenceFindMs The time from `$<ref>a0;` to `$<ref>a1;` of a reference search.
 * @property referenceTimeoutMs When set, a reference search does not find the mark: the adapter
 *   sends `$<ref>16;` (User timeout) this time after `$<ref>a0;`, and no `$<ref>a1;`.
 */
data class CommandAnswers(
    val ack: Boolean = true,
    val busyCount: Int = 0,
    val badData: Boolean = false,
    val idDelayMs: Long = 0L,
    val referenceFindMs: Long = 2_000L,
    val referenceTimeoutMs: Long? = null,
)

/**
 * The faults of a scenario.
 *
 * @property dropAfterMs The link fails with `Lost` this time after the open, or null.
 * @property stopDataAfterMs The adapter sends no more bytes this time after the open, but the link
 *   stays open (a half-open link: `NoData`), or null.
 * @property noise True when random bytes and line noise go between the frames.
 * @property refuseOpens The number of first opens that fail with `Refused`.
 * @property restartOnReboot True when the adapter restarts after `#reboot:1000;`: the link drops
 *   1000 ms after the ack, and each open fails until [rebootTimeMs] after the ack.
 * @property rebootTimeMs The time of a restart. A long time makes the connect of the "Restart Now"
 *   sequence fail.
 */
data class FakeFaults(
    val dropAfterMs: Long? = null,
    val stopDataAfterMs: Long? = null,
    val noise: Boolean = false,
    val refuseOpens: Int = 0,
    val restartOnReboot: Boolean = true,
    val rebootTimeMs: Long = 1_500L,
)

/**
 * The identity of one fake adapter: the answers to `"#get:mac;"` and `"#get:uuid;"`. Two scenarios
 * with different identities give two adapter profiles, so the identity check sees two adapters.
 *
 * @property mac 12 lower case hex digits. The Bluetooth address is the MAC + 2.
 * @property uuid The UUID text in lower case.
 */
data class FakeIdentity(val mac: String, val uuid: String) {
    companion object {
        /** The identity of the scenario `commands` and of a scenario with no identity of its own. */
        val BASE: FakeIdentity = FakeIdentity(FakeAdapter.MAC, FakeAdapter.UUID)

        /**
         * Gives the identity number [n] (1 to 255): the MAC `246f28a1c0<nn>` and the UUID
         * `c0ffee00-1234-4abc-8def-0000000000<nn>`, with `<nn>` = [n] in two hex digits.
         */
        fun numbered(n: Int): FakeIdentity {
            require(n in 1..255) { "The identity number must be 1 to 255, but it is $n." }
            val hex = n.toString(16).padStart(2, '0')
            return FakeIdentity(mac = "246f28a1c0$hex", uuid = "c0ffee00-1234-4abc-8def-0000000000$hex")
        }
    }
}

/**
 * One scenario of the fake adapter. [AdapterAddress.Fake.scenario] names a scenario of
 * [FakeScenarios].
 *
 * @property framesPerSecond The readout frames each second. 20 gives "20 messages each second".
 * @property timestamps True when each frame starts with an `@<hex>;` time stamp.
 * @property changesOnly True when the adapter sends a count only when it changes.
 * @property answers The answers to the commands, or null for a legacy one-way adapter that
 *   answers nothing.
 * @property legacy True for numeric readouts only, for example `X123;Y-456;Z789;T1500;`.
 * @property dirtyFlags True when the adapter sends `$32<mask>;` each second.
 * @property clockDriftMs Each 5 s, the `@` time goes back by this value. More than 250 ms makes
 *   the session set a new offset.
 * @property uptimeGoesBack True when each new link reports a smaller `uptime` (an adapter
 *   restart).
 * @property twoInputs True when each frame has a target input X and a reference input W with one
 *   time stamp (the error map wizard).
 * @property tachometerRpm The value of `T` in each frame, or null for no `T`.
 * @property timedProbe A probe that follows the time, or null. Do not use it with [probe].
 * @property identity The MAC and the UUID that the adapter reports. Each named scenario of
 *   [FakeScenarios] has its own identity.
 */
data class FakeScenario(
    val name: String,
    val motion: Motion = Motion.Still,
    val framesPerSecond: Int = 20,
    val timestamps: Boolean = false,
    val changesOnly: Boolean = false,
    val probe: ProbeScenario? = null,
    val answers: CommandAnswers? = CommandAnswers(),
    val legacy: Boolean = false,
    val dirtyFlags: Boolean = false,
    val clockDriftMs: Long = 0L,
    val uptimeGoesBack: Boolean = false,
    val twoInputs: Boolean = false,
    val tachometerRpm: Long? = null,
    val timedProbe: TimedProbe? = null,
    val faults: FakeFaults = FakeFaults(),
    val identity: FakeIdentity = FakeIdentity.BASE,
) {
    init {
        require(framesPerSecond in 1..1_000) { "The frames for each second must be 1 to 1000, but they are $framesPerSecond." }
    }

    /** The time between two frames. */
    val framePeriodMs: Long get() = 1_000L / framesPerSecond
}

/**
 * The named scenarios. The names are the values of `AdapterAddress.Fake.scenario`.
 * Each scenario reports its own MAC and UUID ([FakeIdentity.numbered]). Only `commands` keeps the
 * base identity [FakeIdentity.BASE] of the protocol examples (`246f28a1b2c3`).
 */
object FakeScenarios {

    private fun id(n: Int): FakeIdentity = FakeIdentity.numbered(n)

    val Still = FakeScenario("still", identity = id(1))
    val SlowMove = FakeScenario("slow-move", motion = Motion.SlowMove, identity = id(2))
    val FastMove = FakeScenario("fast-move", motion = Motion.FastMove, identity = id(3))
    val SlowMoveTimed = FakeScenario("slow-move-timed", motion = Motion.SlowMove, timestamps = true, identity = id(4))
    val FastMoveTimed = FakeScenario("fast-move-timed", motion = Motion.FastMove, timestamps = true, identity = id(5))
    val HolePattern = FakeScenario("hole-pattern", motion = Motion.HolePattern, identity = id(6))
    val Probe = FakeScenario("probe", motion = Motion.SlowMove, timestamps = true, probe = ProbeScenario(), identity = id(7))
    val ProbeRepeated = FakeScenario(
        "probe-repeated", motion = Motion.SlowMove, timestamps = true, probe = ProbeScenario(repeatClosed = true), identity = id(8),
    )
    /** The probe of T09 of the adapter probe app: it follows the time, and each frame has `P0;` or `P1;`. */
    val ProbeTimed = FakeScenario("probe-timed", motion = Motion.SlowMove, timestamps = true, timedProbe = TimedProbe.T09, identity = id(25))
    val ChangesOnly = FakeScenario("changes-only", motion = Motion.HolePattern, changesOnly = true, identity = id(9))
    val Commands = FakeScenario("commands")
    val CommandsNoAck = FakeScenario("commands-no-ack", answers = CommandAnswers(ack = false), identity = id(10))
    val BusyOnce = FakeScenario("busy-once", answers = CommandAnswers(busyCount = 1), identity = id(11))
    val BusyThreeTimes = FakeScenario("busy-three-times", answers = CommandAnswers(busyCount = 3), identity = id(12))
    val BadData = FakeScenario("bad-data", answers = CommandAnswers(badData = true), identity = id(13))
    val SlowIdGroup = FakeScenario("slow-id-group", answers = CommandAnswers(idDelayMs = 1_500L), identity = id(14))

    /** A reference search that does not find the mark: `$<ref>16;` (User timeout) 15 s after the start (T11). */
    val ReferenceTimeout = FakeScenario("ref-timeout", answers = CommandAnswers(referenceTimeoutMs = 15_000L), identity = id(26))
    val Status332 = FakeScenario(
        "status-332", timestamps = true, dirtyFlags = true, clockDriftMs = 400L, uptimeGoesBack = true, identity = id(15),
    )
    val Legacy = FakeScenario("legacy", legacy = true, answers = null, tachometerRpm = 1_500L, identity = id(16))
    val TwoInputs = FakeScenario("two-inputs", motion = Motion.SlowMove, timestamps = true, twoInputs = true, identity = id(17))
    val DropLink = FakeScenario("drop-link", faults = FakeFaults(dropAfterMs = 10_000L), identity = id(18))
    val StopData = FakeScenario("stop-data", faults = FakeFaults(stopDataAfterMs = 5_000L), identity = id(19))
    val Noise = FakeScenario("noise", motion = Motion.SlowMove, faults = FakeFaults(noise = true), identity = id(20))
    val RefuseOpen = FakeScenario("refuse-open", faults = FakeFaults(refuseOpens = 2), identity = id(21))
    val RestartFails = FakeScenario("restart-fails", faults = FakeFaults(rebootTimeMs = 60_000L), identity = id(22))

    /** The demo mill of `DemoActivity`: a hole pattern and all answers. */
    val DemoMill = FakeScenario("mill", motion = Motion.HolePattern, timestamps = true, identity = id(23))

    /** The demo lathe of `DemoActivity`: a slow move and a tachometer. */
    val DemoLathe = FakeScenario("lathe", motion = Motion.SlowMove, timestamps = true, tachometerRpm = 1_200L, identity = id(24))

    /** All named scenarios. */
    val all: List<FakeScenario> = listOf(
        Still, SlowMove, FastMove, SlowMoveTimed, FastMoveTimed, HolePattern, Probe, ProbeRepeated, ProbeTimed, ChangesOnly,
        Commands, CommandsNoAck, BusyOnce, BusyThreeTimes, BadData, SlowIdGroup, ReferenceTimeout, Status332, Legacy, TwoInputs,
        DropLink, StopData, Noise, RefuseOpen, RestartFails, DemoMill, DemoLathe,
    )

    /** Gives the scenario with [name], or null. */
    fun byName(name: String): FakeScenario? = all.firstOrNull { scenario -> scenario.name == name }
}
