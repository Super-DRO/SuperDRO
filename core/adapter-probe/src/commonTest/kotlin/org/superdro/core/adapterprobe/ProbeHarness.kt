package org.superdro.core.adapterprobe

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import org.superdro.core.fakeadapter.FakeScenario
import org.superdro.core.fakeadapter.FakeScenarios
import org.superdro.core.fakeadapter.FakeTransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.util.Clock

/**
 * The probe with the SuperDRO fake adapter and virtual time. The prompts get automatic
 * answers: the typed `yes`, `yes` for a question, `skip` for a step that the user can skip, and
 * the numbers of [numbers] in their order.
 *
 * @param fraction The µs part of the probe clock for the read `read` (0 for the first read) in the
 *   virtual ms `ms`. The default 0 gives whole ms. A value must be 0 to 999, and it must not
 *   decrease in one ms. A device clock has µs parts, and two reads in one ms give two times.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeHarness(
    val test: TestScope,
    extraScenarios: List<FakeScenario> = emptyList(),
    val files: ProbeFiles = MemoryProbeFiles(),
    paired: List<PairedAdapter> = emptyList(),
    private val fraction: (ms: Long, read: Int) -> Long = { _, _ -> 0L },
) {
    private var clockMs = -1L
    private var clockReads = 0
    val clock: ProbeClock = ProbeClock {
        val ms = test.testScheduler.currentTime
        if (ms != clockMs) {
            clockMs = ms
            clockReads = 0
        }
        ms * 1_000_000L + fraction(ms, clockReads++).coerceIn(0L, 999L) * 1_000L
    }
    val fakeClock: Clock = Clock { test.testScheduler.currentTime }
    val factory = FakeTransportFactory(fakeClock) { name -> extraScenarios.firstOrNull { it.name == name } ?: FakeScenarios.byName(name) }
    val env = ProbeEnvironment(
        clock = clock,
        wallClockMs = { WALL_START_MS + test.testScheduler.currentTime },
        files = files,
        linkIo = StandardTestDispatcher(test.testScheduler),
        timeZone = TimeZone.UTC,
        writer = "SuperDRO adapter probe (test)",
        pairedDevices = { paired },
    )
    val records = ArrayList<RunRecord>()
    val prompts = ArrayList<PromptRequest>()
    val numbers = ArrayDeque<String>()
    var typedYes: String = "yes"
    val runner = ProbeRunner(test.backgroundScope, env) { record -> records += record }

    /** The link to the fake adapter of [scenario]. */
    fun link(scenario: String): LinkSpec = LinkSpec(AdapterAddress.Fake(scenario), factory)

    /** Gives the automatic answer to [prompt]. */
    fun answerOf(prompt: PromptRequest): String = when (prompt.kind) {
        PromptKind.Next -> ""
        PromptKind.NextOrSkip -> "skip"
        PromptKind.YesNo -> "yes"
        PromptKind.Number -> numbers.removeFirstOrNull() ?: ""
        PromptKind.TypedYes -> typedYes
    }

    /** Runs [id] on the fake adapter of [scenario] and gives the record. */
    suspend fun run(id: ProbeTestId, scenario: String, options: TestOptions = TestOptions()): RunRecord {
        val answers = test.backgroundScope.launch {
            runner.state.collect { state ->
                val prompt = state.prompt ?: return@collect
                prompts += prompt
                runner.answer(answerOf(prompt))
            }
        }
        check(runner.start(id, link(scenario), options)) { "The runner did not start $id." }
        runner.join()
        answers.cancel()
        return records.last()
    }

    /** Waits until the runner shows a prompt. */
    suspend fun awaitPrompt(): PromptRequest = runner.state.first { it.prompt != null }.prompt!!

    companion object {
        /** 2026-10-12 08:00:00 UTC. */
        const val WALL_START_MS: Long = 1_791_792_000_000L
    }
}

/** The lines of [lines] from the line that starts with [start] to the line before the next line that does not start with a space. */
fun block(lines: List<String>, start: String): List<String> {
    val index = lines.indexOfFirst { it.startsWith(start) }
    if (index < 0) return emptyList()
    val rest = lines.drop(index + 1).takeWhile { it.startsWith(" ") }
    return listOf(lines[index]) + rest
}
