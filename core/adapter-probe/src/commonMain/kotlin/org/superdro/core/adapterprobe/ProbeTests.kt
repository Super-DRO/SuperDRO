package org.superdro.core.adapterprobe

import org.superdro.core.adapterprobe.PyText.fixed
import org.superdro.core.adapterprobe.PyText.left
import org.superdro.core.adapterprobe.PyText.ms
import org.superdro.core.adapterprobe.PyText.right
import org.superdro.core.model.AdapterAddress
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.StatusCode

/**
 * The tests T01 to T16. Each function is the port of the test function of
 * `scripts/adapter_probe.py` with the same phases, times, prompts, and summary lines.
 *
 * The differences come from the SuperDRO command queue: a test that sends requests gets the
 * start queries of the queue 2000 ms after the first valid message. Thus T02, T10, T11,
 * and T16 read the "id" values and the modes from the start queries, and send no second query.
 */
object ProbeTests {

    /** Runs the body of [id] on [s] and gives the summary lines. TT1 and TT2 have their own runners. */
    suspend fun body(id: ProbeTestId, s: ProbeSession): List<String> = when (id) {
        ProbeTestId.T01 -> t01Link(s)
        ProbeTestId.T02 -> t02Identity(s)
        ProbeTestId.T03 -> t03Idle(s)
        ProbeTestId.T04 -> t04Motion(s)
        ProbeTestId.T05 -> t05KeepAlive(s)
        ProbeTestId.T06 -> t06Quotes(s)
        ProbeTestId.T07 -> t07Uptime(s)
        ProbeTestId.T08 -> t08Tach(s)
        ProbeTestId.T09 -> t09Probe(s)
        ProbeTestId.T10 -> t10NoSig(s)
        ProbeTestId.T11 -> t11RefAxis(s)
        ProbeTestId.T12 -> t12Reboot(s)
        ProbeTestId.T13 -> t13CounterKeep(s)
        ProbeTestId.T14 -> t14SecondLink(s)
        ProbeTestId.T15 -> t15HardDrop(s)
        ProbeTestId.T16 -> t16GetSig(s)
        ProbeTestId.TT1, ProbeTestId.TT2 -> throw IllegalArgumentException("$id is a tablet test. Use TabletTests.")
    }

    private fun phaseLines(st: PhaseStats, s: ProbeSession, events: Int = 12): List<String> = st.report(s.options.pair, events)

    private suspend fun needConnected(s: ProbeSession, out: MutableList<String>, timeoutS: Double = 10.0): Boolean {
        if (s.waitConnected(timeoutS)) return true
        out += "No valid message in ${fixed(timeoutS, 0)} s after the link opened. The test stops."
        return false
    }

    private suspend fun pollUntil(s: ProbeSession, timeoutS: Double, condition: () -> Boolean): Boolean {
        val end = s.now() + timeoutS
        while (s.now() < end) {
            if (condition()) return true
            kotlinx.coroutines.delay(POLL_MS)
        }
        return condition()
    }

    private fun notSent(text: String): ProbeRequest = ProbeRequest(text, null, auto = false).also { it.closed = true }

    private fun field(name: String): AdapterField = AdapterField.entries.first { it.getName == name }

    private fun channel(axis: Char): InputChannel = InputChannel.forLetter(axis) ?: InputChannel.X

    // T01

    internal suspend fun t01Link(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        s.waitFor(10.0) { s.firstRxT != null || !s.linkOpen }
        val first = s.firstRxT
        val opened = s.openedT
        val firstByte = if (first != null && opened != null) first - opened else null
        val st1 = s.phase("no writes")
        s.waitConnected(10.0)
        s.hold(5.0)
        val st2 = if (s.options.keepAlive) {
            s.setKeepAlive(true)
            s.phase("keep-alive on")
        } else {
            s.phase("no writes, second part")
        }
        s.hold(10.0)
        s.phase("end")
        val connected = s.connectedT
        out += "Link: ${s.spec.describe()}."
        out += "First byte ${ms(firstByte)} ms after the open. First valid message: " +
            "${if (connected != null && opened != null) "${ms(connected - opened)} ms after the open" else "none"}."
        out += "Checklist item 1: the adapter sends valid messages with no write from the app: ${if (connected != null) "yes" else "no"}."
        val address = s.spec.address
        if (address is AdapterAddress.Tcp) {
            out += "Q38: the TCP server at ${address.host}:${address.port} accepted the link and " +
                "${if (connected != null) "sent valid messages" else "sent no valid message"}."
        }
        out += Analysis.formsLine(s.statsAll)
        out += phaseLines(st1, s) + phaseLines(st2, s)
        return out
    }

    // T02

    internal suspend fun t02Identity(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        var idPhase = false
        var inputsPhase = false
        s.beforeWrite = { command ->
            if (!idPhase && command is AdapterCommand.GetField) {
                idPhase = true
                s.phase("id group")
            } else if (!inputsPhase && command is AdapterCommand.GetMode) {
                inputsPhase = true
                s.phase("inputs group")
            }
        }
        s.waitQueueStart()
        val tC = s.connectedT ?: 0.0
        s.awaitQueueIdle()
        s.beforeWrite = null
        if (!idPhase) s.phase("id group")
        if (!inputsPhase) s.phase("inputs group")
        val res = LinkedHashMap<String, ProbeRequest>()
        for (name in ProbeTexts.ID_FIELDS) {
            val command = AdapterCommand.GetField(field(name))
            res[name] = s.startQuery(command) ?: notSent(command.text)
        }
        val known = res.values.filter { it.okInfo() }
        val modes = LinkedHashMap<Char, ProbeRequest>()
        for (axis in ProbeTexts.AXES) {
            val command = AdapterCommand.GetMode(channel(axis))
            modes[axis] = s.startQuery(command) ?: notSent(command.text)
        }
        s.phase("secondary group")
        for (name in ProbeTexts.SECONDARY_FIELDS) res[name] = s.get(field(name))
        s.phase("end")
        val macRequest = res.getValue("mac")
        out += "Connect sequence of 3.32.00 (five \"#get:\" queries, then six #get_mode queries), first query " +
            "${ms(macRequest.tSend?.let { it - tC })} ms after the first valid message."
        if (known.size == 5) {
            val tAll = known.maxOf { it.tAnswer ?: 0.0 } - tC
            out += "Checklist item 10: the five id values came ${ms(tAll)} ms after the first valid message (limit: " +
                "5000 ms): ${if (tAll <= 5.0) "in the limit" else "NOT in the limit"}."
        }
        out += "  request          ack ms  answer ms  answer"
        for ((name, r) in res) {
            val a = r.answer
            val text = a?.text ?: if (r.closed) "closed" else "timeout"
            var decoded = ""
            if (r.okInfo() && a != null) {
                decoded = ProbeTexts.decodeInfo(a.field ?: 0, a.body.orEmpty())
                val expected = field(name).id
                if (expected != a.field) decoded += " (the answer field is ${PyText.hex2(a.field ?: 0)}, not ${PyText.hex2(expected)})"
            }
            out += "  ${left(name, 16)} ${right(times(r.tAck, r.tSend), 6)} ${right(times(r.tAnswer, r.tSend), 10)}  ${text.take(48)}  $decoded"
        }
        val modeText = ArrayList<String>()
        for ((axis, r) in modes) {
            val a = r.answer
            val decoded = if (r.okInfo(1) && a != null) ProbeTexts.decodeMode(a.body) else null
            val text = a?.text ?: if (r.closed) "closed" else "timeout"
            out += "  get_mode ${left(axis, 7)} ${right(times(r.tAck, r.tSend), 6)} ${right(times(r.tAnswer, r.tSend), 10)}  " +
                "${text.take(48)}  ${if (r.okInfo(1) && a != null) ProbeTexts.decodeInfo(1, a.body.orEmpty()) else ""}"
            if (decoded != null) modeText += "$axis=${decoded.mode}"
        }
        if (modeText.isNotEmpty()) out += "Record these input modes before a set_mode test: ${modeText.joinToString(" ")}."
        val mac = if (macRequest.okInfo(AdapterField.Mac.id)) macRequest.answer?.body else null
        val forms = mac?.let(ProbeTexts::macPlusTwo)
        if (mac != null && forms != null) out += q51Line(s, mac, forms)
        val answered = (res.values + modes.values).count { it.answer != null }
        if (answered == 0) {
            out += "Q19: the adapter answered no query. This agrees with a one-way (legacy) adapter, or with an adapter that ignores " +
                "the commands on this link."
        }
        return out
    }

    /** The Q51 line with the MAC + 2 check: the Bluetooth link address, the option, or the paired devices. */
    private suspend fun q51Line(s: ProbeSession, mac: String, forms: Pair<String, String>): String {
        val (carry, last) = forms
        val address = s.spec.address
        var reference: String? = when {
            address is AdapterAddress.BluetoothSpp -> address.mac
            !s.options.btAddress.isNullOrBlank() -> s.options.btAddress
            else -> null
        }
        var paired: PairedAdapter? = null
        if (reference == null) {
            val devices = s.pairedDevices()
            paired = devices.firstOrNull { ProbeTexts.normMac(it.address) == ProbeTexts.normMac(carry) }
                ?: devices.firstOrNull { ProbeTexts.normMac(it.address) == ProbeTexts.normMac(last) }
            reference = paired?.address
        }
        if (reference == null) {
            return "Q51: reported MAC $mac + 2 = $carry. No paired Bluetooth device has this address. Compare it with the Bluetooth " +
                "address of the adapter, or select the address in the test options."
        }
        val same = ProbeTexts.normMac(reference) == ProbeTexts.normMac(carry)
        val source = paired?.let { " (paired device \"${it.name}\")" }.orEmpty()
        return "Q51: reported MAC $mac + 2 = $carry. Bluetooth address ${reference.uppercase()}$source: ${if (same) "the same" else "NOT the same"}."
    }

    private fun times(t: Double?, send: Double?): String = if (t != null && send != null) ms(t - send) else "-"

    // T03

    internal suspend fun t03Idle(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        val duration = s.options.durationS ?: 30.0
        s.say("Do not move an axis and do not touch the probe for ${fixed(duration, 0)} s.")
        val st = s.phase("idle")
        s.hold(duration)
        s.phase("end")
        out += phaseLines(st, s)
        out += Analysis.readoutLines(st, "idle")
        out += Analysis.specialLines(st)
        out += Analysis.formsLine(st)
        return out
    }

    // T04

    internal suspend fun t04Motion(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        val duration = s.options.durationS ?: 30.0
        val pair = s.options.pair
        val what = if (pair != null) "the inputs ${pair.first} and ${pair.second} together (target and reference)" else "an axis"
        s.say("Move $what back and forth during the full test (${fixed(duration, 0)} s).")
        s.ask("Push Next to start the motion phase: ")
        val st = s.phase("motion")
        s.hold(duration)
        s.phase("end")
        out += phaseLines(st, s)
        out += Analysis.readoutLines(st, "motion")
        out += Analysis.specialLines(st)
        if (pair != null) {
            out += "Q40: see the line 'Pair ${pair.first} + ${pair.second}' above. 100 % means that the two inputs get equal time stamps."
        }
        return out
    }

    // T05

    internal suspend fun t05KeepAlive(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        val st0 = s.phase("open, no writes")
        if (!s.waitConnected(10.0)) {
            out += "Q14: no valid message in 10 s with no write. The protocol has no write before the first valid message, so an app " +
                "cannot get to the state Connected on this link."
            out += phaseLines(st0, s)
            return out
        }
        s.hold(10.0)
        s.setKeepAlive(true)
        val st1 = s.phase("BEL on")
        s.hold(20.0)
        val off = s.options.durationS ?: 60.0
        s.setKeepAlive(false)
        val lastBel = s.lastBelT
        val st2 = s.phase("BEL off")
        s.hold(off)
        var st3: PhaseStats? = null
        if (s.linkOpen) {
            s.setKeepAlive(true)
            st3 = s.phase("BEL on again")
            s.hold(20.0)
        }
        s.phase("end")
        val rows = listOfNotNull(st0, st1, st2, st3)
        out += "  phase              time s  valid/s  max gap ms  link"
        for (st in rows) {
            val closed = st.linkEvents.filter { event -> "closed" in event }
            out += "  ${left(st.name, 18)} ${right(fixed(st.duration(), 1), 6)} ${right(fixed(st.valid / st.duration(), 2), 8)} " +
                "${right(ms(st.maxGap), 11)}  ${closed.firstOrNull() ?: "open"}"
        }
        val rOn = st1.valid / st1.duration()
        val rOff = st2.valid / st2.duration()
        val closedT = s.closedT
        if (closedT != null && st2.t0 <= closedT && closedT <= (st2.t1 ?: s.now())) {
            out += "Q14: the link closed ${ms(if (lastBel != null) closedT - lastBel else null)} ms after the last BEL byte. The adapter " +
                "needs the BEL byte on this link."
        } else if (rOff < 0.5 * rOn || st2.longGaps.isNotEmpty()) {
            out += "Q14: the data rate fell from ${fixed(rOn, 2)}/s to ${fixed(rOff, 2)}/s without the BEL byte. The adapter needs the BEL " +
                "byte on this link."
        } else {
            out += "Q14: the data rate stayed ${fixed(rOn, 2)}/s -> ${fixed(rOff, 2)}/s without the BEL byte for ${fixed(off, 0)} s. " +
                "The adapter does not need the BEL byte on this link."
        }
        val keysOn = st1.keys.keys.toSet()
        val keysOff = st2.keys.keys.toSet()
        if (keysOn != keysOff) {
            out += "Q14: the message prefixes changed: with BEL ${KeyOrder.sorted(keysOn).joinToString(" ")}, without BEL " +
                "${KeyOrder.sorted(keysOff).joinToString(" ")}."
        }
        for (st in rows) out += phaseLines(st, s, events = 6)
        return out
    }

    // T06

    internal suspend fun t06Quotes(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        s.waitQueueStart()
        s.awaitQueueIdle()
        val variants = listOf(
            "\"#get:mac;\"" to "protocol form, with the two quote bytes",
            "#get:mac;" to "no quote bytes",
            "\"#get:mac;" to "only the first quote byte",
            "#get:mac;\"" to "only the last quote byte",
        )
        val st = s.phase("quotes")
        val results = ArrayList<Triple<String, String, ProbeRequest>>()
        for ((index, variant) in variants.withIndex()) {
            val (text, label) = variant
            val r = if (index == 0) {
                s.request(AdapterCommand.GetField(AdapterField.Mac), tailS = 0.3)
            } else {
                s.rawRequest(text, ProbeTexts.decodeEscapes(text), tailS = 0.3)
            }
            results += Triple(text, label, r)
            s.hold(0.3)
        }
        s.phase("end")
        val reference = results[0].third.answer?.text
        for ((text, label, r) in results) {
            val a = r.answer
            val answer = if (a != null) a.text + if (a.text == reference) " (same as the protocol form)" else "" else "none"
            out += "  ${left(text, 14)} ${left(label, 42)} ack ${left(times(r.tAck, r.tSend), 7)} answer $answer"
        }
        val same = results.all { (_, _, r) -> r.answer != null && r.answer?.text == reference }
        out += when {
            reference == null -> "Q20: the protocol form got no answer. The test gives no result."
            same -> "Q20: the adapter gives the same answer with and without the quote bytes. It ignores them."
            else -> "Q20: the answers are different. See the table: the quote bytes have an effect."
        }
        out += phaseLines(st, s)
        return out
    }

    // T07

    internal suspend fun t07Uptime(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        s.waitQueueStart()
        s.awaitQueueIdle()
        val st = s.phase("uptime")
        val samples = ArrayList<Pair<Double, Long>>()
        val count = 7
        for (index in 0 until count) {
            val r = s.get(AdapterField.Uptime)
            if (r.okInfo(AdapterField.Uptime.id)) {
                r.answer?.body?.toLongOrNull(16)?.let { value -> samples += (r.tAnswer ?: 0.0) to value }
            }
            if (index < count - 1 && !s.hold(10.0)) break
        }
        s.phase("end")
        out += "Uptime answers: ${samples.size} of $count. Values: ${samples.joinToString(", ") { it.second.toString() }}."
        if (samples.size >= 3) {
            val origin = samples.first()
            val fit = Analysis.fitLine(samples.map { (t, v) -> (t - origin.first) to (v - origin.second).toDouble() })
            if (fit != null) out += "Q15: uptime rate ${fixed(fit.first, 3)} units/s, probable unit: ${ProbeTexts.unitName(fit.first)}."
            if (samples.zipWithNext().any { (a, b) -> b.second < a.second }) {
                out += "The uptime value decreased in the test. The adapter restarted, or the counter wraps."
            }
        }
        if (st.at.isNotEmpty() && samples.isNotEmpty()) {
            val atLast = st.at.last().second
            val last = samples.last().second
            out += "Last '@' value $atLast, last uptime value $last (ratio ${fixed(if (last != 0L) atLast.toDouble() / last else 0.0, 4)})."
        }
        out += Analysis.specialLines(st)
        return out
    }

    // T08

    internal suspend fun t08Tach(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        s.say("Turn the spindle at a constant speed. Measure the speed with a hand tachometer.")
        s.ask("Push Next when the speed is constant: ")
        val duration = s.options.durationS ?: 20.0
        val st = s.phase("tach")
        s.hold(duration)
        s.phase("end")
        val ks = st.keys["T"]
        if (ks == null || ks.count == 0) {
            out += "Q15: no T message. Check the input mode of the tachometer channel (T02)."
            out += phaseLines(st, s)
            return out
        }
        val values = if (ks.distinct.size <= StatLimits.DISTINCT_CAP) ks.distinct.filterIsInstance<Long>() else emptyList()
        val mean = ks.total.toDouble() / ks.count
        out += "T: ${ks.count} messages (${fixed(ks.count / st.duration(), 2)}/s), mean ${fixed(mean, 1)}, last value ${ks.value}, " +
            "${ks.distinct.size} values (min ${values.minOrNull() ?: "-"}, max ${values.maxOrNull() ?: "-"})."
        val rpm = askFloat(s, "Type the measured speed in RPM (leave the field empty to skip): ")
        val ppr = askFloat(s, "Type the pulses for each revolution of the sensor (leave the field empty to skip): ")
        if (rpm != null && rpm != 0.0) {
            out += "Q15: T / RPM = ${fixed(mean / rpm, 4)}."
            val checks = ArrayList<Pair<String, Double>>()
            checks += "T is RPM" to mean / rpm
            if (ppr != null && ppr != 0.0) {
                checks += "T is pulses for each minute" to mean / (rpm * ppr)
                checks += "T is pulses for each second" to mean / (rpm * ppr / 60.0)
                checks += "T is the pulse period in us" to mean * rpm * ppr / 60e6
            }
            checks += "T is revolutions for each second" to mean / (rpm / 60.0)
            for ((label, k) in checks) {
                out += "  ${left(label, 36)} ratio ${fixed(k, 3)}${if (k in 0.95..1.05) "  <- agrees (5 %)" else ""}"
            }
            out += "The present formula (Q15): RPM = T / (pulses for each revolution)."
        }
        out += phaseLines(st, s)
        return out
    }

    private suspend fun askFloat(s: ProbeSession, prompt: String): Double? =
        s.ask(prompt, PromptKind.Number).replace(",", ".").trim().toDoubleOrNull()

    // T09

    internal suspend fun t09Probe(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        val pk = s.options.probePrefix
        val steps = listOf(
            Triple("open", 5, "Keep the probe contact open. Do not touch the probe."),
            Triple("closed", 10, "Close the probe contact now (touch the part) and hold it closed."),
            Triple("open again", 5, "Open the probe contact now and keep it open."),
            Triple(
                "taps", 10,
                "Touch the part and release the probe quickly, 5 times or more. If the probe is on the machine, move an axis slowly while " +
                    "you touch.",
            ),
        )
        val phases = ArrayList<PhaseStats>()
        for ((name, seconds, text) in steps) {
            s.say("Step '$name' ($seconds s): $text")
            if (name == "open") s.ask("Push Next to start: ")
            val st = s.phase(name)
            phases += st
            if (!s.hold(seconds.toDouble())) break
        }
        s.phase("end")
        val summary = LinkedHashMap<String, Pair<Int, Int>>()
        for (st in phases) {
            val events = st.probe[pk].orEmpty()
            val n1 = events.count { it.second == 1L }
            val n0 = events.size - n1
            summary[st.name] = n0 to n1
            out += "  phase ${left(st.name, 12)} ${pk}0: ${right(n0, 5)}  ${pk}1: ${right(n1, 5)}"
        }
        if (summary.values.all { it.first + it.second == 0 }) {
            out += "Q17: no $pk message. Check the probe channel mode (Digital Probe, 8) in T02, or use --probe-prefix Q."
        }
        val rest = summary["open"] ?: (0 to 0)
        val touch = summary["closed"] ?: (0 to 0)
        val vRest = if (rest.second > rest.first) 1L else 0L
        val vTouch = if (touch.second > touch.first) 1L else 0L
        if (rest.first + rest.second > 0 && touch.first + touch.second > 0) {
            out += "Q39: value at rest $pk$vRest, value with a closed contact $pk$vTouch." +
                if (vRest == vTouch) " The values are the same: check the wires." else ""
        }
        val closed = phases.firstOrNull { it.name == "closed" }
        if (closed != null) {
            val runs = Analysis.probeRuns(closed.probe[pk].orEmpty()).filter { it.value == vTouch }
            if (runs.isNotEmpty()) {
                val longest = runs.maxByOrNull { it.last - it.first } ?: runs.first()
                val gaps = longest.gaps
                out += "Q17: longest closed run: ${fixed(longest.last - longest.first, 1)} s, ${longest.count} messages $pk$vTouch, median " +
                    "repeat ${ms(Analysis.median(gaps))} ms, longest repeat ${if (gaps.isNotEmpty()) ms(longest.maxGap) else "-"} ms."
                out += when {
                    longest.count <= 2 -> "Q17: the adapter sends $pk$vTouch only at the edge. A probe watchdog of 1000 ms clears the state " +
                        "while the contact stays closed."
                    longest.maxGap < 1.0 -> "Q17: the adapter sends $pk$vTouch again while the contact stays closed. The longest repeat is " +
                        "less than the probe watchdog period (1000 ms)."
                    else -> "Q17: the adapter sends $pk$vTouch again, but the longest repeat is more than 1000 ms. A probe watchdog of " +
                        "1000 ms can clear the state."
                }
            }
        }
        val taps = phases.firstOrNull { it.name == "taps" }
        if (taps != null) {
            val runs = Analysis.probeRuns(taps.probe[pk].orEmpty())
            if (runs.size > 1) {
                val dts = runs.zipWithNext { a, b -> b.first - a.first }.sorted()
                val short = dts.count { it < 0.005 }
                out += "Q39: taps: ${runs.size - 1} changes, shortest time between two changes ${ms(dts.first())} ms, changes after less " +
                    "than 5 ms: $short."
                out += "Q39: " + if (short > 0) {
                    "changes after less than 5 ms show contact bounce: the adapter does not debounce (or its debounce is shorter)."
                } else {
                    "no change after less than 5 ms: the adapter debounces, or the contact did not bounce."
                }
            }
        }
        val other = if (pk == "P") "Q" else "P"
        val nOther = phases.sumOf { st -> st.probe[other].orEmpty().size }
        out += "Q18: $nOther $other messages in the test."
        val merged = PhaseStats("probe", 0.0, keep = true)
        for (st in phases) st.msgs.orEmpty().forEach(merged::add)
        out += Analysis.edgeLines(merged, pk)
        out += Analysis.specialLines(merged)
        for (st in phases) out += phaseLines(st, s, events = 4)
        return out
    }

    // T10

    /** Reads the modes of [axis] with a `#get_mode` request through the queue (`read_mode`). */
    private suspend fun readMode(s: ProbeSession, axis: Char): Pair<ProbeRequest, ProbeTexts.ModeAnswer?> {
        val r = s.request(AdapterCommand.GetMode(channel(axis)))
        return r to decodedMode(r, axis)
    }

    private fun decodedMode(r: ProbeRequest?, axis: Char): ProbeTexts.ModeAnswer? {
        if (r == null || !r.okInfo(1)) return null
        val decoded = ProbeTexts.decodeMode(r.answer?.body) ?: return null
        return if (decoded.channel == axis) decoded else null
    }

    internal suspend fun t10NoSig(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        val axis = s.options.axis ?: return listOf("T10 needs --axis.")
        val powerCycle = s.ask(
            "Did you turn the adapter power off and on, and keep the other DRO app disconnected since then?",
            PromptKind.YesNo,
        )
        out += "Power cycle before the test, with no link of the other DRO app: ${powerCycle.ifEmpty { "no answer" }}."
        if (!needConnected(s, out)) return out
        s.waitQueueStart()
        val st = s.phase("no-sig")
        // The "id" queries and the modes are the start queries of the queue.
        s.awaitQueueIdle()
        val modeAnswer = decodedMode(s.startQuery(AdapterCommand.GetMode(channel(axis))), axis) ?: readMode(s, axis).second
        var rSet: ProbeRequest? = null
        var rBack: ProbeTexts.ModeAnswer? = null
        var rClear: ProbeRequest? = null
        if (modeAnswer == null) {
            out += "No valid #get_mode:$axis; answer. The tool does not send #set_mode."
        } else {
            s.say(
                "Channel $axis: current mode ${modeAnswer.mode} (${modeAnswer.name}). Supported: ${modeAnswer.supported.joinToString(", ")}. " +
                    "Record this value.",
            )
            val mode = InputMode.forId(modeAnswer.mode)
            val text = "#set_mode:$axis,${modeAnswer.mode};"
            if (mode == InputMode.Unknown) {
                out += "The current mode ${modeAnswer.mode} of channel $axis is not one mode ID. The tool does not send $text."
            } else if (s.confirm(
                    text,
                    "This command sets channel $axis to its current mode ${modeAnswer.mode}. The mode does not change, but the adapter can " +
                        "ask for a restart.",
                    currentMode = "${modeAnswer.mode} (${modeAnswer.name})",
                )
            ) {
                val set = s.request(AdapterCommand.SetMode(channel(axis), mode), tailS = 0.5)
                rSet = set
                // After `$060;` the queue reads the mode back by itself.
                val readBack = if (set.answer?.let(::acceptModeOk) == true) {
                    s.autoRequests().lastOrNull { it.command == AdapterCommand.GetMode(channel(axis)) }
                } else {
                    null
                }
                rBack = decodedMode(readBack, axis) ?: if (readBack == null) readMode(s, axis).second else null
            }
        }
        val key = axis.uppercaseChar().toString()
        val before = s.snapshot()[key]
        val clearText = "#clear_counter:$axis;"
        var after: Long? = null
        if (s.confirm(clearText, ProbeTexts.cautionClear(axis))) {
            rClear = s.request(AdapterCommand.ClearCounter(channel(axis)), tailS = 1.0)
            s.hold(1.0)
            after = s.snapshot()[key]
        }
        s.phase("end")
        if (rSet != null) {
            val a = rSet.answer
            val ok = a != null && acceptModeOk(a)
            out += "Q4: #set_mode without #get_sig: ${if (ok) "ACCEPTED" else "NOT accepted"}. Answer: ${a?.text ?: "none"}. Read-back mode: " +
                "${rBack?.mode ?: "none"}."
        }
        if (rClear != null) {
            val a = rClear.answer
            val ok = a != null && a.isAck()
            val later = rClear.tail.filter { it.kind != MessageKind.Unknown }.joinToString(", ") { it.text }.ifEmpty { "none" }
            out += "Q4: #clear_counter without #get_sig: ${if (ok) "ACKNOWLEDGED" else "NOT acknowledged"}. Answer: ${a?.text ?: "none"}. " +
                "Later messages: $later. $key before ${PyText.str(before)}, after ${PyText.str(after)}."
        }
        out += phaseLines(st, s)
        return out
    }

    private fun acceptModeOk(m: ProbeMessage): Boolean = m.kind == MessageKind.Status && m.sub == ProbeTexts.INPUTS && m.code == 0

    // T11

    internal suspend fun t11RefAxis(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        val axis = s.options.axis
        val sub = axis?.let(ProbeTexts::refSub)
        if (axis == null || sub == null) return listOf("T11 needs --axis x, y, z, or w (the protocol defines #ref_axis only for these).")
        if (!needConnected(s, out)) return out
        s.waitQueueStart()
        s.awaitQueueIdle()
        val modelRequest = s.startQuery(AdapterCommand.GetField(AdapterField.Model)) ?: s.get(AdapterField.Model)
        val model = if (modelRequest.okInfo(AdapterField.Model.id)) modelRequest.answer?.body.orEmpty() else ""
        val mode = decodedMode(s.startQuery(AdapterCommand.GetMode(channel(axis))), axis) ?: readMode(s, axis).second
        if (!model.lowercase().startsWith("tda-")) {
            s.say(
                "Note: the model ${PyText.repr(model)} does not start with tda-. The reference function needs a model that starts with " +
                    "tda-.",
            )
        }
        if (mode == null || mode.mode != 2) {
            s.say("Note: channel $axis is not in Quadrature A/B mode (2). The reference function needs this mode.")
        }
        val runs = ArrayList<Pair<String, String>>()
        runs += "no motion" to "Do not move the axis. The tool waits for the end of the search."
        if (s.options.refRuns == 2) runs += "motion" to "Move the axis slowly over the reference mark of the scale."
        val limit = s.options.durationS ?: 40.0
        val key = axis.uppercaseChar().toString()
        val text = "#ref_axis:$axis,15;"
        for ((name, hint) in runs) {
            s.say("Run '$name': $hint")
            if (!s.confirm(text, ProbeTexts.cautionRef(axis))) continue
            val st = s.phase("ref $name")
            val r = s.request(AdapterCommand.RefAxis(channel(axis)))
            val tStart = if (r.answer != null && !r.error) r.tAnswer else null
            var endMessage: ProbeMessage? = null
            val others = ArrayList<String>()
            var snapBefore = s.snapshot()[key]
            val stop = s.now() + limit
            while (s.now() < stop && s.linkOpen && r.answer != null && !r.error) {
                val m = s.nextEvent(0.1)
                if (m == null) {
                    snapBefore = s.snapshot()[key]
                    continue
                }
                if (m.isStatusOrError && m.sub == sub) {
                    if (m.code == StatusCode.FINISHED || m.code == StatusCode.CANCELLED || m.code == StatusCode.USER_TIMEOUT) {
                        endMessage = m
                        break
                    }
                    others += m.text
                }
            }
            s.hold(0.5)
            val snapAfter = s.snapshot()[key]
            s.phase("end")
            out += "Run '$name': answer ${r.answer?.text ?: "none"} after ${times(r.tAnswer, r.tSend)} ms."
            val end = endMessage
            if (end != null && tStart != null) {
                val dt = end.t - tStart
                out += "  End: ${end.text} (${ProbeTexts.statusName(end.code ?: 0)}) ${fixed(dt, 2)} s after the start answer. Other " +
                    "messages: ${others.joinToString(", ").ifEmpty { "none" }}."
                out += "  $key before the end: ${PyText.str(snapBefore)}, 0.5 s after the end: ${PyText.str(snapAfter)}."
                if (end.code == StatusCode.USER_TIMEOUT) {
                    out += "Q13: the search ended with User timeout after ${fixed(dt, 1)} s. " +
                        if (kotlin.math.abs(dt - 15.0) <= 1.5) "This agrees with '15' = the time limit in seconds." else "This does not agree with a limit of 15 s."
                } else if (end.code == StatusCode.FINISHED) {
                    out += "Q13: the search found the mark (Finished). Do a run with no motion to measure the time limit."
                }
            } else if (tStart != null) {
                out += "  No end message in ${fixed(limit, 0)} s. Other messages: ${others.joinToString(", ").ifEmpty { "none" }}."
            }
            out += phaseLines(st, s, events = 8)
        }
        return out
    }

    // T12

    private fun rxGapAfter(s: ProbeSession, from: Double, minGap: Double = 0.3): Pair<Double?, Double?> {
        val times = s.rxTimes.filter { it >= from }
        for ((a, b) in times.zipWithNext()) {
            if (b - a >= minGap) return a to b
        }
        return times.lastOrNull() to null
    }

    private suspend fun readUptime(s: ProbeSession): Long? {
        val r = s.get(AdapterField.Uptime)
        val body = r.answer?.body
        return if (r.okInfo(AdapterField.Uptime.id) && body != null && HEX.matches(body)) body.toLongOrNull(16) else null
    }

    internal suspend fun t12Reboot(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        s.waitQueueStart()
        s.awaitQueueIdle()
        val u1 = readUptime(s)
        s.hold(1.0)
        val snap1 = s.snapshot()
        s.say("Do not move an axis during this test.")
        if (!s.confirm(AdapterCommand.Reboot.text, ProbeTexts.CAUTION_REBOOT)) return listOf("Cancelled. The tool did not send #reboot:1000;.")
        val st = s.phase("reboot")
        val r = s.request(AdapterCommand.Reboot)
        val tRef = r.tAck ?: r.tSend ?: s.now()
        // The app closes the link 250 ms after the ack. The probe does not
        // close it, so that the test shows what the adapter does.
        pollUntil(s, 30.0) { !s.linkOpen || rxGapAfter(s, tRef).second != null }
        val closed = !s.linkOpen
        val tClosed = if (closed) s.closedT else null
        var reopen: Pair<Boolean, List<Pair<Double, String>>>? = null
        if (closed) {
            s.say("The link closed. The tool opens it again each 1 s for 30 s.")
            reopen = s.reopenLoop(30.0, 1.0)
            s.waitConnected(15.0)
        }
        val (lastBefore, firstAfter) = rxGapAfter(s, tRef)
        var tValid: Double? = null
        if (firstAfter != null) {
            tValid = s.validTimes.firstOrNull { it >= firstAfter }
            if (tValid == null) {
                pollUntil(s, 15.0) { !s.linkOpen || s.validTimes.any { it >= firstAfter } }
                tValid = s.validTimes.firstOrNull { it >= firstAfter }
            }
        }
        var u2: Long? = null
        var snap2: Map<String, Long> = emptyMap()
        if (s.linkOpen && tValid != null) {
            s.hold(ProbeSession.QUEUE_START_S)
            u2 = readUptime(s)
            s.hold(2.0)
            snap2 = s.snapshot()
        }
        s.phase("end")
        out += "Ack of #reboot:1000; after ${r.tAck?.let { ack -> ms(ack - (r.tSend ?: ack)) } ?: "- (none)"} ms."
        if (firstAfter == null && !closed) {
            out += "The stream did not stop in 30 s after the ack. The adapter did not restart."
        } else if (lastBefore != null) {
            out += "Q13: the stream stopped ${ms(lastBefore - tRef)} ms after the ack. The first block after the stop came " +
                "${if (firstAfter != null) ms(firstAfter - tRef) else "-"} ms after the ack."
        }
        if (closed) {
            out += "The link closed ${ms(tClosed?.let { it - tRef })} ms after the ack."
            reopen?.let { (ok, attempts) ->
                out += "Open again: ${if (ok) "success" else "FAILED"} after ${attempts.size} attempts, ${ms(attempts.last().first - tRef)} ms " +
                    "after the ack."
            }
        }
        out += "First valid message after the restart: ${if (tValid != null) "${ms(tValid - tRef)} ms after the ack" else "none"}."
        out += "Uptime before ${PyText.str(u1)}, after ${PyText.str(u2)}: " +
            "${if (u1 != null && u2 != null && u2 < u1) "the adapter restarted" else "no restart is visible in the uptime"}."
        out += "Q13: if the stream stops about 1000 ms after the ack, '1000' is probably the delay before the restart in ms."
        if (snap2.isNotEmpty()) {
            out += "Q41: counts before and after the restart:"
            for (key in KeyOrder.sorted(snap1.keys + snap2.keys)) {
                out += "  ${left(key, 3)} ${right(snap1[key] ?: "-", 12)} ${right(snap2[key] ?: "-", 12)}  " +
                    if (snap1[key] == snap2[key]) "same" else "CHANGED"
            }
        }
        out += phaseLines(st, s, events = 10)
        return out
    }

    // T13

    private fun countsLine(label: String, a: Map<String, Long>, b: Map<String, Long>): String {
        val keys = a.keys.filter { it in POSITION_KEYS }
        if (b.isEmpty() || keys.isEmpty()) return "Q41: $label: no data to compare."
        if (keys.all { a[it] == b[it] }) return "Q41: $label: the counts are the same."
        if (keys.all { b[it] == 0L }) return "Q41: $label: all counts are zero."
        return "Q41: $label: the counts are DIFFERENT (see the table)."
    }

    internal suspend fun t13CounterKeep(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        s.say("Do not move an axis during this test.")
        if (!needConnected(s, out)) return out
        s.waitQueueStart()
        s.awaitQueueIdle()
        val u1 = readUptime(s)
        s.hold(3.0)
        val snap1 = s.snapshot()
        s.say("Step 1: the tool closes the link and opens it again after 3 s.")
        s.close()
        kotlinx.coroutines.delay(3_000L)
        val (ok, _) = s.reopenLoop(15.0, 1.0)
        var snap2: Map<String, Long> = emptyMap()
        if (ok && s.waitConnected(10.0)) {
            s.hold(3.0)
            snap2 = s.snapshot()
        }
        val answer = s.ask(
            "Step 2: turn the adapter power off. Wait 10 s. Turn the power on. Then push Next (or Skip to skip this step): ",
            PromptKind.NextOrSkip,
            caution = ProbeTexts.CAUTION_POWER_CYCLE,
        )
        var snap3: Map<String, Long> = emptyMap()
        var u2: Long? = null
        val skip = answer.lowercase() == "skip"
        if (!skip) {
            val tEnter = s.now()
            if (!s.linkOpen) {
                s.say("The link is closed. The tool opens it again each 1 s for 60 s.")
                s.reopenLoop(60.0, 1.0)
            }
            if (s.linkOpen && s.waitConnected(15.0)) {
                s.hold(ProbeSession.QUEUE_START_S)
                u2 = readUptime(s)
                s.hold(3.0)
                val lastRx = s.lastRxT
                if (lastRx != null && lastRx > tEnter) snap3 = s.snapshot() else out += "No data came after the power cycle."
            }
        }
        s.phase("end")
        out += "Uptime before ${PyText.str(u1)}, after the power cycle ${u2?.toString() ?: "-"}."
        out += "Q41:  key   start        after a reconnect   after a power cycle"
        for (key in KeyOrder.sorted(snap1.keys + snap2.keys + snap3.keys)) {
            out += "      ${left(key, 4)} ${left(snap1[key] ?: "-", 12)} ${left(snap2[key] ?: "-", 19)} ${snap3[key] ?: "-"}"
        }
        out += countsLine("after a reconnect", snap1, snap2)
        if (!skip) out += countsLine("after a power cycle", snap1, snap3)
        return out
    }

    // T14

    internal suspend fun t14SecondLink(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        s.hold(5.0)
        val st1 = s.phase("close, open again after 750 ms")
        s.close()
        kotlinx.coroutines.delay(750L)
        val (ok, attempts) = s.reopenLoop(10.0, 0.75)
        val got = ok && s.waitConnected(15.0)
        val connected = s.connectedT
        val opened = s.openedT
        out += "Phase 1 (NO_DATA recovery, DC20: close, then open after 750 ms): open ${if (ok) "success" else "FAILED"} after " +
            "${attempts.size} attempts; first valid message ${if (got && connected != null && opened != null) ms(connected - opened) else "- (none)"} " +
            "ms after the open."
        s.hold(5.0)
        if (s.spec.address is AdapterAddress.UsbSerial) {
            out += "Phase 2 does not apply to a USB link."
            s.phase("end")
            out += phaseLines(st1, s, events = 4)
            return out
        }
        val st2 = s.phase("second link, first link silent")
        val keep = s.keepAliveOn
        s.setKeepAlive(false)
        s.holdReader(true)
        s.note("first link: no read and no write from now")
        val res = s.secondLinkProbe(15.0)
        s.holdReader(false)
        s.hold(1.0)
        val firstState = if (s.linkOpen) "open" else "closed (${s.closedReason})"
        s.setKeepAlive(keep)
        s.hold(10.0)
        val lastRx = s.lastRxT
        val dataAfter = lastRx != null && s.now() - lastRx < 2.0
        s.phase("end")
        if (res.error != null && res.openS == null) {
            out += "Q50: the second link did not open while the first link stayed open: ${res.error}."
        } else {
            out += "Q50: the second link opened in ${ms(res.openS)} ms. It got ${res.valid} valid messages; the first valid message came " +
                "${ms(res.firstValidS)} ms after the open.${if (res.error != null) " End: ${res.error}." else ""}"
        }
        out += "Q50: after the second link closed, the first link is $firstState, and it ${if (dataAfter) "gets" else "does NOT get"} data."
        if (s.spec.address is AdapterAddress.BluetoothSpp) {
            out += "Note: for RFCOMM, a second link from the same PC can fail for a reason in the PC. Use the tablet for the second link " +
                "(plan, T14, manual part)."
        }
        out += phaseLines(st1, s, events = 4) + phaseLines(st2, s, events = 4)
        return out
    }

    // T15

    internal suspend fun t15HardDrop(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (s.spec.address is AdapterAddress.UsbSerial) return listOf("T15 applies only to a Bluetooth link or a TCP link.")
        if (!needConnected(s, out)) return out
        s.hold(5.0)
        val bluetooth = s.spec.address is AdapterAddress.BluetoothSpp
        val breakText = if (bluetooth) {
            "Break the link now without a clean close: turn off Bluetooth in the quick settings of the tablet. Then push Next: "
        } else {
            "Break the link now without a clean close: turn off Wi-Fi in the quick settings of the tablet. Then push Next: "
        }
        val fixText = if (bluetooth) {
            "Restore the link now: turn on Bluetooth in the quick settings. Push Next when the Bluetooth is on: "
        } else {
            "Restore the link now: turn on Wi-Fi in the quick settings. Push Next when the tablet is on the network again: "
        }
        s.ask(breakText, caution = if (bluetooth) ProbeTexts.CAUTION_BREAK_BLUETOOTH else ProbeTexts.CAUTION_BREAK_WIFI)
        s.setKeepAlive(false)
        val tBreak = s.now()
        s.close(abort = true)
        // The link stays broken for 20 s, less than the 30 s limits of the session.
        s.say("Wait ${PyText.fixed(BREAK_S, 0)} s with the link broken. This time is shorter than the 30 s limits of the session.")
        s.hold(BREAK_S, stopOnClose = false)
        s.ask(fixText)
        val tFix = s.now()
        s.setKeepAlive(s.options.keepAlive)
        val st = s.phase("open again")
        val (ok, attempts) = s.reopenLoop(120.0, 1.0)
        val got = ok && s.waitConnected(60.0)
        s.hold(10.0)
        s.phase("end")
        val errors = LinkedHashMap<String, Int>()
        attempts.filter { it.second != "open" }.forEach { (_, error) -> errors[error] = (errors[error] ?: 0) + 1 }
        val errorText = errors.entries.sortedByDescending { it.value }.take(3).joinToString("; ") { (error, n) -> "$error (x$n)" }
        out += "Link broken at ${ms(tBreak)} ms, PC link restored at ${ms(tFix)} ms (${fixed(tFix - tBreak, 1)} s later)."
        out += "Q50: open again: ${if (ok) "success" else "FAILED"} after ${attempts.size} attempts, ${ms(attempts.last().first - tFix)} ms " +
            "after the restore. Errors: ${errorText.ifEmpty { "none" }}."
        val connected = s.connectedT
        val opened = s.openedT
        out += "Q50: first valid message on the new link: " +
            "${if (got && connected != null && opened != null) "${ms(connected - opened)} ms after the open" else "none in 60 s"}."
        out += phaseLines(st, s, events = 4)
        return out
    }

    // T16

    internal suspend fun t16GetSig(s: ProbeSession): List<String> {
        val out = ArrayList<String>()
        if (!needConnected(s, out)) return out
        s.waitQueueStart()
        // The "id" queries are the start queries of the queue.
        s.awaitQueueIdle()
        s.say("The tool sends #get_sig once. It does not verify the answer.")
        val st = s.phase("get_sig")
        val r = s.request(AdapterCommand.GetSig.at(s.wallClockMs()))
        s.phase("end")
        val a = r.answer
        out += "#get_sig: ack ${times(r.tAck, r.tSend)} ms, answer ${times(r.tAnswer, r.tSend)} ms, answer kind ${a?.kind?.pyName ?: "none"}, " +
            "Base64 length ${if (a != null && a.kind == MessageKind.Signature) a.body.orEmpty().length.toString() else "-"}."
        out += "Q13: an RSA-3072 signature has 512 Base64 characters. The log has the answer."
        out += phaseLines(st, s, events = 4)
        return out
    }

    private const val POLL_MS = 50L

    /** T15: the time with the link broken. */
    private const val BREAK_S = 20.0
    private const val POSITION_KEYS = "XYZWUVABCDEF"
    private val HEX = Regex("[0-9A-Fa-f]+")
}
