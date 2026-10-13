package org.superdro.core.protocol

import org.superdro.core.util.Bytes

/**
 * Drives a [CommandQueue] with virtual time. The harness follows the timer requests of the queue,
 * gives the answers of a test adapter to a parser, and gives the messages to the queue. The
 * harness uses no real clock.
 *
 * @param respond Gives the answers of the adapter to a written command: pairs of a delay after
 *   the write (ms) and the answer text.
 */
class QueueHarness(
    val queue: CommandQueue = CommandQueue(),
    var respond: (AdapterCommand) -> List<Pair<Long, String>> = { emptyList() },
) {
    var now: Long = 0L
        private set

    /** All outputs with their times. */
    val outputs: MutableList<Pair<Long, QueueOutput>> = mutableListOf()

    private val parser = AdapterParser()
    private var timerAt: Long? = null
    private val answers = mutableListOf<Pair<Long, String>>()

    private fun record(list: List<QueueOutput>) {
        timerAt = null
        list.forEach { output ->
            outputs += now to output
            if (output is QueueOutput.Timer) timerAt = output.atMs
            if (output is QueueOutput.Write) {
                respond(output.command).forEach { (delay, text) -> answers += (now + delay) to text }
            }
        }
    }

    /** Runs all timers and answers up to [timeMs], in the order of their times, then sets the time to [timeMs]. */
    fun runUntil(timeMs: Long) {
        while (true) {
            val timer = timerAt
            val answer = answers.minByOrNull { it.first }
            val nextAnswer = answer?.first
            val next = listOfNotNull(timer, nextAnswer).minOrNull() ?: break
            if (next > timeMs) break
            now = next
            if (answer != null && nextAnswer == next) {
                answers.remove(answer)
                receiveNow(answer.second)
            } else {
                record(queue.onTimer(next))
            }
        }
        now = timeMs
    }

    /** Gives a link phase at the current time. */
    fun phase(phase: LinkPhase) {
        record(queue.onLinkPhase(phase, now))
    }

    /** Parses [text] at the current time and gives each message to the queue. */
    fun receive(text: String) {
        receiveNow(text)
    }

    private fun receiveNow(text: String) {
        parser.feed(text, now).forEach { message -> record(queue.onMessage(message, now)) }
    }

    /** Submits [command] at the current time and gives the request ID. */
    fun submit(command: AdapterCommand): Long {
        val submission = queue.submit(command, now)
        record(submission.outputs)
        return submission.requestId
    }

    /** Opens the Adapter Details screen at the current time. */
    fun openDetails() {
        record(queue.openAdapterDetails(now))
    }

    /** The writes as (time, text) pairs. */
    fun writes(): List<Pair<Long, String>> = outputs.mapNotNull { (time, output) ->
        (output as? QueueOutput.Write)?.let { time to Bytes.asciiText(it.bytes) }
    }

    /** The write texts only. */
    fun writeTexts(): List<String> = writes().map { it.second }

    /** The outputs of the type [T] with their times. */
    inline fun <reified T : QueueOutput> all(): List<Pair<Long, T>> =
        outputs.mapNotNull { (time, output) -> (output as? T)?.let { time to it } }

    /** The result of the request [id], or null. */
    fun resultOf(id: Long): CommandResult? = all<QueueOutput.Result>().lastOrNull { it.second.requestId == id }?.second?.result

    /** The time of the result of the request [id], or null. */
    fun resultTimeOf(id: Long): Long? = all<QueueOutput.Result>().lastOrNull { it.second.requestId == id }?.first

    /** Forgets the outputs so far. */
    fun clearOutputs() {
        outputs.clear()
    }

    companion object {
        const val MAC_ANSWER: String = "?05246f28a1b2c3;"
        const val UUID_ANSWER: String = "?02c0ffee00-1234-4abc-8def-0123456789ab;"

        /** The answers of a genuine two-way adapter. Each answer comes 10 ms after the write. */
        fun genuine(modes: MutableMap<InputChannel, Int> = InputChannel.entries.associateWith { 2 }.toMutableMap()): (AdapterCommand) -> List<Pair<Long, String>> =
            { command ->
                when (command) {
                    is AdapterCommand.GetField -> listOf(
                        10L to when (command.field) {
                            AdapterField.Mac -> MAC_ANSWER
                            AdapterField.Uuid -> UUID_ANSWER
                            AdapterField.Features -> "?0a1;"
                            AdapterField.Uptime -> "?09e10;"
                            AdapterField.Model -> "?04tda-example;"
                            AdapterField.Name -> "?03Shop adapter;"
                            AdapterField.Version -> "?061.4.2;"
                            AdapterField.Build -> "?07b42;"
                            AdapterField.Date -> "?0866000000;"
                            else -> "?ff;"
                        },
                    )
                    is AdapterCommand.GetMode -> {
                        val mode = modes.getValue(command.channel).toString(16).padStart(4, '0')
                        listOf(10L to "?01${command.channel.letter}000f$mode;")
                    }
                    is AdapterCommand.SetMode -> {
                        modes[command.channel] = command.mode.id
                        listOf(10L to "\$0aa3;", 20L to "\$060;")
                    }
                    is AdapterCommand.RefAxis -> listOf(10L to "\$0aa3;", 20L to "\$${command.channel.referenceSubsystem!!.toString(16)}a0;")
                    is AdapterCommand.ClearCounter -> listOf(10L to "\$0aa3;")
                    AdapterCommand.Reboot -> listOf(10L to "\$0aa3;")
                    is AdapterCommand.GetSig, AdapterCommand.Bel -> emptyList()
                }
            }

        /**
         * Gives a harness with a genuine adapter after the start sequence: `Connected` at 0, the
         * queue start at 2000, the 11 start queries at 2000 to 2500. The time is 3000 at the end.
         */
        fun afterStart(queue: CommandQueue = CommandQueue()): QueueHarness {
            val harness = QueueHarness(queue, genuine())
            harness.phase(LinkPhase.Connected)
            harness.runUntil(3_000L)
            return harness
        }
    }
}
