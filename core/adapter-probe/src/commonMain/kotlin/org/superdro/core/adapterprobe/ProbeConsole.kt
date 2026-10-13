package org.superdro.core.adapterprobe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ValueState

/**
 * One typed command of the console.
 *
 * @property text The text of the command on the wire.
 * @property caution The caution before the typed `yes`, or null when the command needs no
 *   confirmation.
 */
sealed interface ConsoleCommand {
    val text: String
    val caution: String?

    /** A SuperDRO command. It goes through the SuperDRO command queue. */
    data class Queue(val command: AdapterCommand, override val caution: String? = null) : ConsoleCommand {
        override val text: String get() = command.text
    }

    /** A raw text: the exact bytes. It does not go through the queue. */
    data class Raw(override val text: String, val bytes: ByteArray, override val caution: String?) : ConsoleCommand {
        override fun equals(other: Any?): Boolean =
            other is Raw && other.text == text && other.bytes.contentEquals(bytes) && other.caution == caution

        override fun hashCode(): Int = 31 * text.hashCode() + bytes.contentHashCode()
    }
}

/** The command builder of the console (`build_command` of the tool). */
object ConsoleCommands {

    /** The help text of the console. */
    val HELP: String = """Commands:
  get <field>              send "#get:<field>;" with the two '"' bytes
                           (fields: mac uuid features uptime model name version build date)
  get_mode <axis>          send #get_mode:<axis>;   (axis: x y z w u v)
  set_mode <axis> <id>     send #set_mode:<axis>,<id>;   (asks for a confirmation)
  ref_axis <axis>          send #ref_axis:<axis>,15;   (x y z w; asks for a confirmation)
  clear_counter <axis>     send #clear_counter:<axis>;   (asks for a confirmation)
  reboot                   send #reboot:1000;   (asks for a confirmation)
  get_sig                  send #get_sig:<seconds>,1;   (asks for a confirmation; the app does not verify the answer)
  raw <text>               send the text exactly; \xNN gives one byte
  keepalive on|off         start or stop the BEL byte (0x07)
  print all|changes|events|none
  values                   show the last value of each input
  stats                    show the statistics since the start
  mark <text>              write a note in the log
  close / open             close the link, or open it again
  quit"""

    /**
     * Makes a command from a typed line. [epochNowMs] gives the seconds of `#get_sig`.
     *
     * @throws IllegalArgumentException with the usage text for a bad command.
     */
    fun build(line: String, epochNowMs: Long): ConsoleCommand {
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        require(parts.first().isNotEmpty()) { "empty command" }
        val name = parts[0].lowercase()
        val rest = if (parts.size > 1) parts[1].trim() else ""
        val args = if (rest.isEmpty()) emptyList() else rest.split(Regex("\\s+"))
        return when (name) {
            "get" -> {
                val field = args.singleOrNull()?.lowercase()?.let { text -> AdapterField.entries.firstOrNull { it.getName == text } }
                requireNotNull(field) { "usage: get <field>  (fields: ${(ProbeTexts.ID_FIELDS + ProbeTexts.SECONDARY_FIELDS).joinToString(" ")})" }
                ConsoleCommand.Queue(AdapterCommand.GetField(field))
            }
            "get_mode", "clear_counter", "ref_axis" -> {
                val channel = args.singleOrNull()?.singleOrNull()?.lowercaseChar()?.let(InputChannel::forLetter)
                requireNotNull(channel) { "usage: $name <axis>  (axis: x y z w u v)" }
                when (name) {
                    "get_mode" -> ConsoleCommand.Queue(AdapterCommand.GetMode(channel))
                    "clear_counter" -> ConsoleCommand.Queue(AdapterCommand.ClearCounter(channel), ProbeTexts.cautionClear(channel.letter))
                    else -> {
                        require(channel.referenceAllowed) { "ref_axis accepts only x y z w (the protocol defines only these)" }
                        ConsoleCommand.Queue(AdapterCommand.RefAxis(channel), ProbeTexts.cautionRef(channel.letter))
                    }
                }
            }
            "set_mode" -> {
                val usage = "usage: set_mode <axis> <mode id>  (ids: ${InputMode.wireModes.joinToString(", ") { "${it.id} ${it.text}" }})"
                val channel = args.getOrNull(0)?.singleOrNull()?.lowercaseChar()?.let(InputChannel::forLetter)
                val id = args.getOrNull(1)?.takeIf { text -> text.all { it in '0'..'9' } }?.toIntOrNull()
                require(args.size == 2 && channel != null && id != null) { usage }
                val mode = InputMode.forId(id)
                require(mode != InputMode.Unknown) { usage }
                ConsoleCommand.Queue(AdapterCommand.SetMode(channel, mode), ProbeTexts.cautionSetMode(channel.letter))
            }
            "reboot" -> ConsoleCommand.Queue(AdapterCommand.Reboot, ProbeTexts.CAUTION_REBOOT)
            "get_sig" -> ConsoleCommand.Queue(AdapterCommand.GetSig.at(epochNowMs), ProbeTexts.CAUTION_GET_SIG)
            "raw" -> {
                require(rest.isNotEmpty()) { "usage: raw <text>  (\\xNN gives one byte)" }
                val bytes = ProbeTexts.decodeEscapes(rest)
                val text = ProbeTexts.asciiBackslash(bytes)
                val caution = when {
                    ProbeTexts.STATE_COMMANDS.any { it in text } -> ProbeTexts.CAUTION_RAW
                    "#get_sig" in text -> ProbeTexts.CAUTION_GET_SIG
                    else -> null
                }
                ConsoleCommand.Raw(text, bytes, caution)
            }
            else -> throw IllegalArgumentException("unknown command ${PyText.repr(name)}. Type help.")
        }
    }
}

/**
 * The state of the console.
 *
 * @property open True while the console session runs.
 * @property lines The last lines of the console: the live messages and the answers.
 * @property level The filter of the live messages (`all`, `changes`, `events`, `none`).
 * @property prompt The prompt that waits for the user (the typed `yes`), or null.
 * @property logName The `.log` file of the console session.
 * @property busy True while a command runs.
 */
data class ConsoleState(
    val open: Boolean = false,
    val lines: List<String> = emptyList(),
    val level: PrintLevel = PrintLevel.Events,
    val prompt: PromptRequest? = null,
    val logName: String? = null,
    val busy: Boolean = false,
)

/**
 * The console of the app: the interactive mode of the tool. The console session has the
 * mode tag `interactive` and its own `.bin` and `.log` files. The adapter commands go
 * through the SuperDRO command queue; `raw` writes the exact bytes. Before `#set_mode`,
 * `#clear_counter`, `#ref_axis`, `#reboot`, and `#get_sig`, the user must type `yes`.
 * The console sends nothing before the first valid message.
 */
class ProbeConsole(
    private val scope: CoroutineScope,
    private val env: ProbeEnvironment,
    private val onRecord: (RunRecord) -> Unit = {},
) {
    private val _state = MutableStateFlow(ConsoleState())
    private var session: ProbeSession? = null
    private var job: Job? = null
    private var lines: Channel<String>? = null
    private var pending: CompletableDeferred<String>? = null

    /** The state of the console. */
    val state: StateFlow<ConsoleState> = _state.asStateFlow()

    /** The link state of the console session, or null. */
    val linkInfo: StateFlow<LinkInfo>? get() = session?.linkInfo

    private val shell = object : ProbeShell {
        override fun output(line: String) {
            _state.update { state -> state.copy(lines = (state.lines + line).takeLast(MAX_LINES)) }
        }

        override suspend fun ask(prompt: PromptRequest): String {
            val answer = CompletableDeferred<String>()
            pending = answer
            _state.update { state -> state.copy(prompt = prompt) }
            try {
                return answer.await()
            } finally {
                pending = null
                _state.update { state -> state.copy(prompt = null) }
            }
        }
    }

    /** Opens the console session on [link]. Gives false when it is open already. */
    fun start(link: LinkSpec, options: TestOptions): Boolean {
        if (job?.isActive == true) return false
        val level = options.printLevel ?: PrintLevel.Events
        val s = ProbeSession(link, options, "interactive", options.keepAlive, level, env, scope, shell, usesQueue = true)
        session = s
        val channel = Channel<String>(Channel.UNLIMITED)
        lines = channel
        _state.value = ConsoleState(open = true, level = level, logName = s.logName)
        job = scope.launch {
            var status = RunStatus.Done
            try {
                s.within {
                    printer.line("Interactive mode. Type help for the commands, quit to stop.")
                    try {
                        open()
                    } catch (failure: LinkFailureException) {
                        say("The link did not open: ${failure.message ?: failure.failure}")
                    }
                    for (line in channel) {
                        _state.update { state -> state.copy(busy = true) }
                        val go = handle(this, line)
                        _state.update { state -> state.copy(busy = false) }
                        if (!go) break
                    }
                    // Close the statistics of the run before the report. The close counts the
                    // last readout group, as the analysis of the log does at its end.
                    val end = now()
                    statsCur.close(end)
                    statsAll.close(end)
                    statsAll.report(options.pair).forEach(::say)
                }
            } catch (stopped: CancellationException) {
                status = RunStatus.Stopped
                throw stopped
            } finally {
                withContext(NonCancellable) {
                    s.finish()
                    onRecord(
                        RunRecord(
                            test = "console", linkKind = link.kind, link = link.describe(), linkKey = link.key,
                            startedAtEpochMs = s.startedAtEpochMs, status = status, summary = emptyList(),
                            logFile = s.logName, binFile = s.binName, adapter = AdapterSnapshot.of(s.adapterValues), label = options.label,
                        ),
                    )
                    _state.update { state -> state.copy(open = false, busy = false, prompt = null) }
                }
            }
        }
        return true
    }

    /** Gives one typed line to the console. */
    fun submit(line: String) {
        lines?.trySend(line)
    }

    /** Gives the answer of the user to the current prompt. */
    fun answer(text: String) {
        pending?.complete(text)
    }

    /** Sets the filter of the live messages. */
    fun setLevel(level: PrintLevel) {
        session?.printer?.level = level
        _state.update { state -> state.copy(level = level) }
    }

    /** Ends the console session: `quit`. */
    fun quit() {
        val channel = lines
        if (channel == null || job?.isActive != true) return
        if (pending != null) pending?.complete("")
        channel.trySend("quit")
    }

    /** Waits until the console session ends. */
    suspend fun join() {
        job?.join()
    }

    /** Runs one typed line (`run_interactive`). Gives false for `quit`. */
    private suspend fun handle(s: ProbeSession, typed: String): Boolean {
        val line = typed.trim()
        if (line.isEmpty()) return true
        s.note("typed: $line")
        s.printer.line("> $line")
        val name = line.substringBefore(' ').lowercase()
        val rest = line.substringAfter(' ', "").trim()
        when {
            name == "quit" || name == "exit" || name == "q" -> return false
            name == "help" -> ConsoleCommands.HELP.lines().forEach(s.printer::line)
            name == "keepalive" && (rest == "on" || rest == "off") -> s.setKeepAlive(rest == "on")
            name == "print" && PrintLevel.of(rest) != null -> setLevel(PrintLevel.of(rest) ?: PrintLevel.Events)
            name == "values" -> KeyOrder.sorted(s.snapshot().keys).forEach { key -> s.printer.line("  $key = ${s.snapshot()[key]}") }
            name == "stats" -> s.statsAll.report(s.options.pair).forEach(s.printer::line)
            name == "mark" -> s.note("mark: $rest")
            name == "close" -> s.close()
            name == "open" -> try {
                s.open()
            } catch (failure: LinkFailureException) {
                s.say("The link did not open: ${failure.message ?: failure.failure}")
            }
            else -> runCommand(s, line)
        }
        return true
    }

    private suspend fun runCommand(s: ProbeSession, line: String) {
        val command = try {
            ConsoleCommands.build(line, s.wallClockMs())
        } catch (bad: IllegalArgumentException) {
            s.printer.line(bad.message ?: "bad command")
            return
        }
        if (!s.linkOpen) {
            s.printer.line("The link is not open. Type open.")
            return
        }
        if (s.connectedT == null) {
            s.printer.line("No valid message came yet. The app sends nothing before the first valid message (AP7).")
            return
        }
        var currentMode: String? = null
        val setMode = (command as? ConsoleCommand.Queue)?.command as? AdapterCommand.SetMode
        if (setMode != null) {
            // Show the current mode of the channel before #set_mode.
            val channel = setMode.channel
            currentMode = currentModeText(s, channel)
            s.say("Channel ${channel.letter}: current mode $currentMode.")
        }
        val caution = command.caution
        if (caution != null && !s.confirm(command.text, caution, currentMode)) return
        when (command) {
            is ConsoleCommand.Queue -> s.request(command.command, tailS = 0.3)
            is ConsoleCommand.Raw -> s.rawRequest(command.text, command.bytes, tailS = 0.3)
        }
    }

    private suspend fun currentModeText(s: ProbeSession, channel: InputChannel): String {
        fun reported(): String? {
            val value = s.queue?.values?.modes?.get(channel) ?: return null
            val modes = value.value ?: return null
            if (value.state !is ValueState.Reported) return null
            val mode = modes.current
            return "${modes.currentId} (${if (mode == InputMode.Unknown) "Unknown" else mode.text})"
        }
        reported()?.let { return it }
        s.request(AdapterCommand.GetMode(channel))
        return reported() ?: "not known (no #get_mode answer)"
    }

    private companion object {
        const val MAX_LINES = 400
    }
}
