package org.superdro.core.adapterprobe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.superdro.core.connection.LinkFailureException

/**
 * The state of the runner for the Test screen.
 *
 * @property test The test of the run, or null.
 * @property status The state of the run.
 * @property step The last step text of the test (`say`).
 * @property countdownEndNanos The end of the timed step on the probe clock, or null.
 * @property prompt The prompt that waits for the user, or null.
 * @property live The last live message lines.
 * @property summary The summary lines at the end.
 * @property logName The `.log` file of the run.
 * @property binName The `.bin` file of the run.
 * @property label The label of the run, or null.
 */
data class RunnerState(
    val test: ProbeTestId? = null,
    val status: RunStatus = RunStatus.Idle,
    val step: String? = null,
    val countdownEndNanos: Long? = null,
    val prompt: PromptRequest? = null,
    val live: List<String> = emptyList(),
    val summary: List<String> = emptyList(),
    val logName: String? = null,
    val binName: String? = null,
    val label: String? = null,
) {
    /** True while a test runs. */
    val running: Boolean get() = status == RunStatus.Running
}

/**
 * The test runner that a shell drives. The shell starts a test, shows [state], and
 * answers each prompt with [answer] ("Next", "Skip", yes or no, a number, or the typed `yes`). A
 * timed step shows the countdown of [RunnerState.countdownEndNanos]. [stop] stops the test; the
 * files close, and the log has the note "Stopped by the user.".
 *
 * The runner runs one test at a time. Each test has its own [ProbeSession], its own link, and its
 * own `.bin` and `.log` files. At the end, [onRecord] gets the [RunRecord] of the run.
 *
 * @param scope The scope of the runs.
 * @param env The platform parts.
 * @param onRecord Gets each run record (the report and the Tests screen use them).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeRunner(
    private val scope: CoroutineScope,
    private val env: ProbeEnvironment,
    private val onRecord: (RunRecord) -> Unit = {},
) {
    private val _state = MutableStateFlow(RunnerState())
    private val session = MutableStateFlow<ProbeSession?>(null)
    private var job: Job? = null
    private var pending: CompletableDeferred<String>? = null

    /** The state of the run. */
    val state: StateFlow<RunnerState> = _state.asStateFlow()

    /** The link state of the current run for the Link screen. */
    val link: Flow<LinkInfo> = session.flatMapLatest { current -> current?.linkInfo ?: flowOf(LinkInfo()) }

    private val shell = object : ProbeShell {
        override fun output(line: String) {
            _state.update { state -> state.copy(live = (state.live + line).takeLast(LIVE_LINES)) }
        }

        override fun step(text: String) {
            _state.update { state -> state.copy(step = text) }
        }

        override fun countdown(endNanos: Long?) {
            _state.update { state -> state.copy(countdownEndNanos = endNanos) }
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

    /**
     * Starts [test] on [link] with [options]. Gives false when a test runs already, or when [test]
     * is a tablet test (use [startTt1]).
     */
    fun start(test: ProbeTestId, link: LinkSpec, options: TestOptions): Boolean {
        if (job?.isActive == true || test.onTablet) return false
        _state.value = RunnerState(test, RunStatus.Running, label = options.label)
        job = scope.launch {
            val record = runTest(test, link, options)
            _state.update { state -> state.copy(status = record.status, step = null, prompt = null, countdownEndNanos = null) }
        }
        return true
    }

    /**
     * Starts TT1: T01 on the TCP link [tcp] before and after the request of the local network
     * permission [access]. Gives false when a test runs already.
     */
    fun startTt1(tcp: LinkSpec, options: TestOptions, access: LocalNetworkAccess, tablet: TabletInfo): Boolean {
        if (job?.isActive == true) return false
        _state.value = RunnerState(ProbeTestId.TT1, RunStatus.Running, label = options.label)
        job = scope.launch {
            val status = runTt1(tcp, options, access, tablet)
            _state.update { state -> state.copy(test = ProbeTestId.TT1, status = status, step = null, prompt = null, countdownEndNanos = null) }
        }
        return true
    }

    /** Gives the answer of the user to the current prompt. */
    fun answer(text: String) {
        pending?.complete(text)
    }

    /** Pushes "Next" on the current prompt. */
    fun next() {
        answer("")
    }

    /** Stops the current test. */
    fun stop() {
        job?.cancel()
    }

    /** Waits until the current run ends. */
    suspend fun join() {
        job?.join()
    }

    private suspend fun runTest(test: ProbeTestId, link: LinkSpec, options: TestOptions, tag: String = test.tag): RunRecord {
        val keepAlive = test.startKeepAlive ?: options.keepAlive
        val level = options.printLevel ?: PrintLevel.Events
        val s = ProbeSession(link, options, tag, keepAlive, level, env, scope, shell, test.keepMessages, test.usesQueue)
        session.value = s
        _state.update { state -> state.copy(test = test, logName = s.logName, binName = s.binName, live = emptyList(), summary = emptyList()) }
        var status = RunStatus.Done
        var summary: List<String> = emptyList()
        var record: RunRecord? = null
        try {
            s.within {
                try {
                    open()
                } catch (failure: LinkFailureException) {
                    say("The link did not open: ${failure.message ?: failure.failure}")
                    status = RunStatus.Failed
                    summary = listOf("The link did not open: ${failure.message ?: failure.failure}")
                    return@within
                }
                say("Test ${test.name} (${test.toolName}): ${test.purpose}. Questions: ${test.toolQuestions}.")
                val lines = ProbeTests.body(test, this)
                printer.line("")
                say("Summary of test ${test.name} (${test.toolName}):")
                lines.forEach(::say)
                summary = lines
            }
        } catch (stopped: CancellationException) {
            status = RunStatus.Stopped
            withContext(NonCancellable) { s.within { say("Stopped by the user.") } }
            throw stopped
        } catch (failure: Exception) {
            status = RunStatus.Failed
            summary = listOf("The test stopped with an error: ${failure.message ?: failure.toString()}")
            withContext(NonCancellable) { s.within { say(summary.first()) } }
        } finally {
            withContext(NonCancellable) {
                s.finish()
                val done = RunRecord(
                    test = test.name,
                    linkKind = link.kind,
                    link = link.describe(),
                    linkKey = link.key,
                    startedAtEpochMs = s.startedAtEpochMs,
                    status = status,
                    summary = summary,
                    logFile = s.logName,
                    binFile = s.binName,
                    adapter = AdapterSnapshot.of(s.adapterValues),
                    label = options.label,
                    btAddress = options.btAddress?.trim()?.ifEmpty { null },
                )
                _state.update { state -> state.copy(summary = summary, status = status) }
                onRecord(done)
                record = done
            }
        }
        return checkNotNull(record)
    }

    private suspend fun runTt1(tcp: LinkSpec, options: TestOptions, access: LocalNetworkAccess, tablet: TabletInfo): RunStatus {
        val started = env.wallClockMs()
        val lines = ArrayList<String>()
        lines += TabletTests.tabletLine("TT1", tablet)
        val before = TabletTests.permissionState(access)
        lines += "Q42: ACCESS_LOCAL_NETWORK before the request: $before."
        val first = runTest(ProbeTestId.T01, tcp, options.copy(label = "TT1, before the request"), tag = "TT1-T01-before")
        var granted = access.exists && access.granted()
        if (access.exists && !granted) {
            shell.ask(PromptRequest(PromptKind.Next, "The app asks for ACCESS_LOCAL_NETWORK now. Push Next, then answer the Android dialog."))
            granted = access.request()
            lines += "Q42: the request of ACCESS_LOCAL_NETWORK gave: ${if (granted) "granted" else "not granted"}."
        } else {
            lines += "Q42: no request: ${if (!access.exists) "this Android version has no ACCESS_LOCAL_NETWORK" else "the permission is granted already"}."
        }
        val second = runTest(ProbeTestId.T01, tcp, options.copy(label = "TT1, after the request"), tag = "TT1-T01-after")
        val after = TabletTests.permissionState(access)
        lines += "Q42: ACCESS_LOCAL_NETWORK after the request: $after."
        lines += TabletTests.tt1Lines(first, second, access.exists, before, granted)
        val record = RunRecord(
            test = ProbeTestId.TT1.name,
            linkKind = tcp.kind,
            link = tcp.describe(),
            linkKey = tcp.key,
            startedAtEpochMs = started,
            status = RunStatus.Done,
            summary = lines,
            label = options.label,
            files = listOfNotNull(first.logFile, first.binFile, second.logFile, second.binFile),
        )
        _state.update { state -> state.copy(test = ProbeTestId.TT1, summary = lines, step = null) }
        onRecord(record)
        return RunStatus.Done
    }

    private companion object {
        const val LIVE_LINES = 300
    }
}
