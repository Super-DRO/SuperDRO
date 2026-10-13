package org.superdro.app.adapterprobe.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.app.adapterprobe.DebugLinks
import org.superdro.app.adapterprobe.LinkChoice
import org.superdro.app.adapterprobe.LinkType
import org.superdro.app.adapterprobe.ProbeGraph
import org.superdro.core.adapterprobe.CameraAttach
import org.superdro.core.adapterprobe.ConsoleState
import org.superdro.core.adapterprobe.LinkInfo
import org.superdro.core.adapterprobe.PrintLevel
import org.superdro.core.adapterprobe.ProbeTestId
import org.superdro.core.adapterprobe.ProbeTexts
import org.superdro.core.adapterprobe.RunRecord
import org.superdro.core.adapterprobe.RunStatus
import org.superdro.core.adapterprobe.RunnerState
import org.superdro.core.adapterprobe.TabletTests
import org.superdro.core.adapterprobe.TestOptions
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.model.AdapterAddress

/** The screens of the probe app. */
enum class Screen { Link, Tests, Test, Console, Report }

/** The state of the app frame: the current screen and the selected test. */
data class AppState(val screen: Screen = Screen.Link, val test: ProbeTestId = ProbeTestId.T01, val debugMenu: Boolean = DebugLinks.available)

/** The presenter of the app frame. */
class AppPresenter {
    private val _state = MutableStateFlow(AppState())

    /** The state of the frame. */
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Shows [screen]. */
    fun show(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    /** Selects [test] and shows the Test screen. */
    fun openTest(test: ProbeTestId) {
        _state.update { it.copy(screen = Screen.Test, test = test) }
    }
}

/** The state of one permission of the Link screen. */
data class PermissionRow(val permission: String, val granted: Boolean)

/**
 * The state of the Link screen: the link type, the paired
 * Bluetooth devices, the USB serial devices, the host and the TCP port, the baud rate, and the
 * state of the link of the current test or console.
 */
data class LinkUiState(
    val choice: LinkChoice = LinkChoice(),
    val types: List<LinkType> = LinkType.entries,
    val paired: List<DiscoveredAdapter> = emptyList(),
    val usb: List<DiscoveredAdapter> = emptyList(),
    val baudRates: List<Int> = AdapterAddress.UsbSerial.BAUD_RATES,
    val scenarios: List<String> = emptyList(),
    val selected: String? = null,
    val tcpValid: Boolean = true,
    val info: LinkInfo = LinkInfo(),
    val permissions: List<PermissionRow> = emptyList(),
)

/** The presenter of the Link screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPresenter(private val graph: ProbeGraph, private val scope: CoroutineScope) {

    private val permissionTick = MutableStateFlow(0)

    private val linkInfo = combine(graph.runner.state, graph.console.state) { run, console -> run.running to console.open }
        .flatMapLatest { (running, consoleOpen) ->
            when {
                consoleOpen -> graph.console.linkInfo ?: flowOf(LinkInfo())
                running -> graph.runner.link
                else -> flowOf(LinkInfo())
            }
        }

    /** The state of the screen. */
    val state: StateFlow<LinkUiState> = combine(
        graph.link,
        graph.sppDiscovery.adapters,
        graph.usbDiscovery.adapters,
        linkInfo,
        permissionTick,
    ) { choice, paired, usb, info, _ ->
        LinkUiState(
            choice = choice,
            types = LinkType.entries.filter { it != LinkType.Fake || DebugLinks.available },
            paired = paired,
            usb = usb,
            scenarios = DebugLinks.scenarios,
            selected = graph.linkSpec(choice)?.describe(),
            tcpValid = choice.type != LinkType.Tcp || choice.tcpAddress() != null,
            info = info,
            permissions = graph.runtimePermissions().map { PermissionRow(it, graph.granted(it)) },
        )
    }.stateIn(scope, SharingStarted.Eagerly, LinkUiState())

    fun selectType(type: LinkType) = graph.selectLink { it.copy(type = type) }

    fun selectBluetooth(adapter: DiscoveredAdapter) {
        val address = adapter.address as? AdapterAddress.BluetoothSpp ?: return
        graph.selectLink { it.copy(type = LinkType.Bluetooth, bluetooth = address.mac, bluetoothName = adapter.displayName) }
    }

    /** Selects a USB device. A device with no permission gets the Android permission dialog first. */
    fun selectUsb(adapter: DiscoveredAdapter) {
        val address = adapter.address as? AdapterAddress.UsbSerial ?: return
        graph.selectLink { it.copy(type = LinkType.Usb, usb = address) }
        if (adapter.needsPermission) {
            scope.launch {
                graph.usbRequester.request(address)
                graph.usbDiscovery.refresh()
            }
        }
    }

    fun selectBaud(baud: Int) = graph.selectLink { it.copy(baudRate = baud) }

    fun setHost(host: String) = graph.selectLink { it.copy(host = host) }

    fun setPort(port: String) = graph.selectLink { it.copy(port = port.filter { c -> c.isDigit() }.take(5)) }

    fun selectScenario(name: String) = graph.selectLink { it.copy(type = LinkType.Fake, fakeScenario = name) }

    /** The debug menu: connects the link to the fake adapter. */
    fun connectFake() = graph.selectLink { it.copy(type = LinkType.Fake) }

    /** The debug menu: the fake adapter behind a local TCP server, and the TCP link to it. */
    fun connectFakeTcp() {
        scope.launch {
            val port = DebugLinks.startTcpServer(graph.link.value.fakeScenario) ?: return@launch
            graph.selectLink { it.copy(type = LinkType.Tcp, host = "127.0.0.1", port = port.toString()) }
        }
    }

    /** Asks for the run-time permissions of the links and the notification. */
    fun requestPermissions() {
        scope.launch {
            graph.permissions.request(graph.runtimePermissions())
            graph.sppDiscovery.refresh()
            permissionTick.update { it + 1 }
        }
    }

    /** Reads the permissions and the device lists again. */
    fun refresh() {
        graph.sppDiscovery.refresh()
        graph.usbDiscovery.refresh()
        permissionTick.update { it + 1 }
    }
}

/** One row of the Tests screen: the test, its questions, its state, and its last result. */
data class TestRow(val id: ProbeTestId, val status: RunStatus?, val result: String?)

/** The presenter of the Tests screen. */
class TestsPresenter(graph: ProbeGraph, scope: CoroutineScope) {

    /** The rows of T01 to T16, TT1, and TT2. */
    val state: StateFlow<List<TestRow>> = combine(graph.records.records, graph.runner.state) { records, run ->
        ProbeTestId.entries.map { id ->
            val last = records.lastOrNull { it.test == id.name }
            val status = if (run.running && run.test == id) RunStatus.Running else last?.status
            TestRow(id, status, last?.shortResult)
        }
    }.stateIn(scope, SharingStarted.Eagerly, ProbeTestId.entries.map { TestRow(it, null, null) })
}

/** The options of a test as the user types them. */
data class OptionsUi(
    val axis: Char = 'x',
    val pair: String = "",
    val probePrefix: String = "P",
    val refRuns: Int = 1,
    val duration: String = "",
    val label: String = "",
    val keepAlive: Boolean = true,
    val btAddress: String = "",
) {
    /** The options of the core module for [test]: only the options that the Test screen shows for [test]. */
    fun toOptions(test: ProbeTestId): TestOptions = TestOptions(
        keepAlive = keepAlive,
        durationS = duration.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && test in DURATION_OPTION },
        axis = axis.takeIf { test.needsAxis },
        pair = TestOptions.parsePair(pair).takeIf { test == ProbeTestId.T04 },
        probePrefix = if (test == ProbeTestId.T09) probePrefix else "P",
        refRuns = if (test == ProbeTestId.T11) refRuns else 1,
        btAddress = btAddress.trim().ifEmpty { null }?.takeIf { test == ProbeTestId.T02 },
        label = label.trim().ifEmpty { null },
    )

    companion object {
        /** The tests with the option "Duration" (the option `--duration` of the tool). */
        val DURATION_OPTION: Set<ProbeTestId> = setOf(ProbeTestId.T03, ProbeTestId.T04, ProbeTestId.T05, ProbeTestId.T08, ProbeTestId.T11)
    }
}

/**
 * The state of the Test screen: the test, the options, the runner state (the step, the countdown,
 * the prompt, the live messages, the summary), and for TT2 the camera attaches.
 */
data class TestUiState(
    val test: ProbeTestId = ProbeTestId.T01,
    val options: OptionsUi = OptionsUi(),
    val run: RunnerState = RunnerState(),
    val link: String? = null,
    val linkKind: LinkType = LinkType.Bluetooth,
    val attaches: List<CameraAttach> = emptyList(),
    val tablet: String = "",
) {
    /** The live message lines of a run of [test] only. The run of a different test shows no line on this screen. */
    val liveLines: List<String> get() = if (run.test == test) run.live else emptyList()

    /**
     * The steps of [test]. While the test of this screen runs, the screen shows no steps. Thus the
     * step text and the countdown of the run stay near the top.
     */
    val planSteps: List<String> get() = if (run.running && run.test == test) emptyList() else ProbeTexts.testSteps(test)

    /** True when the caution about the one link of the adapter applies: each test, but not TT2 (a camera test, no adapter). */
    val oneLinkCaution: Boolean get() = test != ProbeTestId.TT2
}

/** The presenter of the Test screen. */
class TestPresenter(private val graph: ProbeGraph, private val app: AppPresenter, private val scope: CoroutineScope) {

    private val options = MutableStateFlow(OptionsUi())

    /** The state of the screen. */
    val state: StateFlow<TestUiState> = combine(app.state, options, graph.runner.state, graph.link, graph.attaches.attaches) { frame, options, run, link, attaches ->
        TestUiState(
            test = if (run.running) run.test ?: frame.test else frame.test,
            options = options,
            run = run,
            link = graph.linkSpec(link)?.describe(),
            linkKind = link.type,
            attaches = attaches,
            tablet = with(graph.tablet) { "$manufacturer $model, Android $androidVersion (API $sdkInt), $securityPatch" },
        )
    }.stateIn(scope, SharingStarted.Eagerly, TestUiState())

    fun setOptions(transform: (OptionsUi) -> OptionsUi) = options.update(transform)

    /** Starts the selected test on the selected link. Gives false when no link is selected. */
    fun start(): Boolean {
        val test = state.value.test
        val options = options.value.toOptions(test)
        return when (test) {
            ProbeTestId.TT1 -> {
                val tcp = graph.link.value.takeIf { it.type == LinkType.Tcp }?.let { graph.linkSpec(it) } ?: return false
                graph.runner.startTt1(tcp, options, graph.localNetwork, graph.tablet)
            }
            ProbeTestId.TT2 -> {
                writeTt2()
                true
            }
            else -> {
                val spec = graph.linkSpec() ?: return false
                graph.runner.start(test, spec, options)
            }
        }
    }

    fun stop() = graph.runner.stop()

    fun answer(text: String) = graph.runner.answer(text)

    /** TT2: the answer of the user for one attach. */
    fun answerAttach(index: Int, dialogShown: Boolean) = graph.attaches.answer(index, dialogShown)

    /** TT2: reads the attaches again. */
    fun reloadAttaches() = graph.attaches.reload()

    /** TT2: writes the result of the attaches as a run record (Q49). */
    private fun writeTt2() {
        val lines = TabletTests.tt2Lines(graph.attaches.attaches.value, graph.tablet)
        graph.records.add(
            RunRecord(
                test = ProbeTestId.TT2.name, linkKind = "usb", link = "UVC camera, USB video class 14", linkKey = "tablet:camera",
                startedAtEpochMs = System.currentTimeMillis(), status = RunStatus.Done, summary = lines,
                label = options.value.label.ifEmpty { null }, files = listOf(ProbeGraph.ATTACH_FILE),
            ),
        )
    }
}

/** The presenter of the Console screen. */
class ConsolePresenter(private val graph: ProbeGraph) {

    /** The state of the console. */
    val state: StateFlow<ConsoleState> = graph.console.state

    /** Opens the console on the selected link. Gives false when no link is selected. */
    fun open(): Boolean {
        val spec = graph.linkSpec() ?: return false
        return graph.console.start(spec, TestOptions())
    }

    fun submit(line: String) = graph.console.submit(line)

    fun setLevel(level: PrintLevel) = graph.console.setLevel(level)

    fun answer(text: String) = graph.console.answer(text)

    fun quit() = graph.console.quit()
}

/** The state of the Report screen: the report text. */
data class ReportUiState(val text: String = "", val runs: Int = 0, val sharing: Boolean = false)

/** The presenter of the Report screen. */
class ReportPresenter(private val graph: ProbeGraph, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(ReportUiState())

    /** The state of the screen. */
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        scope.launch { graph.records.records.map { it.size }.collect { refresh() } }
    }

    /** Makes the report again. */
    fun refresh() {
        scope.launch {
            val text = org.superdro.app.adapterprobe.ProbeShare.report(graph)
            _state.update { it.copy(text = text, runs = graph.records.records.value.size) }
        }
    }
}
