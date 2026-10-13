package org.superdro.core.ui.settings.logs

import androidx.compose.runtime.Immutable
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.SessionState
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_logs_exported
import org.superdro.core.resources.settings_logs_filter_all
import org.superdro.core.resources.settings_logs_filter_app
import org.superdro.core.resources.settings_logs_no_share
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.FilePicker

/** The two parts of the log screen. */
enum class LogsTab { Lines, Record }

/** One filter chip: the filter and its text. */
@Immutable
data class LogFilterItem(val filter: LogFilter, val label: UiText)

/** One shown log line. [machine] is the machine name, or null for a line of the app. */
@Immutable
data class LogLineUiState(
    val sequence: Long,
    val time: String,
    val level: LogLevel,
    val tag: String,
    val machine: String?,
    val text: String,
    val error: String?,
)

/**
 * The record switch of one session.
 *
 * @property available False while the session is `Disconnected` (a paused machine): the switch
 *   would connect the machine.
 */
@Immutable
data class RecordSessionUiState(val deviceId: DeviceId, val name: String, val recording: Boolean, val available: Boolean)

/** One recorded link of the list. */
@Immutable
data class RecordedLinkUiState(
    val id: Long,
    val name: String,
    val link: Int,
    val started: String,
    val chunkCount: Int,
    val byteCount: Long,
    val open: Boolean,
    val truncated: Boolean,
)

/** A message for the snackbar of the frame. [id] keeps two equal texts apart. */
@Immutable
data class LogsMessage(val id: Long, val text: UiText)

/** The state of the log screen. The lines are newest first. */
@Immutable
data class LogsUiState(
    val tab: LogsTab = LogsTab.Lines,
    val filters: List<LogFilterItem> = emptyList(),
    val filter: LogFilter = LogFilter.All,
    val lines: List<LogLineUiState> = emptyList(),
    val sessions: List<RecordSessionUiState> = emptyList(),
    val records: List<RecordedLinkUiState> = emptyList(),
    val busy: Boolean = false,
    val message: LogsMessage? = null,
    /** The most bytes of the copy of one link, for the text of a truncated record. */
    val maxBytesPerLink: Long = RecordedStreams.DEFAULT_MAX_BYTES_PER_LINK,
)

/** The events of the log screen. */
interface LogsEvents {
    fun onSelectTab(tab: LogsTab)
    fun onSelectFilter(filter: LogFilter)
    fun onExport()
    fun onClear()
    fun onRecordChange(deviceId: DeviceId, on: Boolean)
    fun onExportRecord(id: Long)
    fun onMessageShown(id: Long)
}

/**
 * The presenter of the log screen.
 *
 * - The lines come from [sink]. The presenter reads them again at most each [REFRESH_MS] ms while
 *   new lines come, so a fast log does not redraw the screen at each line.
 * - "Export" gives the shown lines to the share sheet ([FilePicker.share]) as plain text.
 * - The record switch of a session calls [Connections.setRecordStream]: the session starts a new
 *   link with `DeviceRef.recordStream` and opens a record sink at the start of the link. The
 *   session stays online, so the tab stays.
 * - The export of a recorded link gives the copy of [streams] in the text form of the file.
 *
 * @param wallClock Gives the time of an export. [zone] gives the local time of the texts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsPresenter(
    private val sink: SessionLogSink,
    private val streams: RecordedStreams,
    private val connections: Connections,
    machines: MachineRepository,
    private val filePicker: FilePicker,
    context: PresenterContext,
    private val wallClock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ScopedPresenter(context), LogsEvents {

    private data class Local(
        val tab: LogsTab = LogsTab.Lines,
        val filter: LogFilter = LogFilter.All,
        val busy: Boolean = false,
        val message: LogsMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** The revision of the sink, at most one new value each [REFRESH_MS] ms. */
    private val refreshed = MutableStateFlow(-1L)

    private val names: StateFlow<Map<DeviceId, String>> = machines.profiles()
        .map { profiles -> profiles.associate { profile -> DeviceId(profile.id) to profile.name } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val sessionRefs: Flow<List<Pair<DeviceRef, SessionState>>> = connections.sessions.flatMapLatest { sessions ->
        if (sessions.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(sessions.values.map { session -> combine(session.device, session.state) { device, state -> device to state } }) { pairs ->
                pairs.toList()
            }
        }
    }

    private val sessionList: StateFlow<List<Pair<DeviceRef, SessionState>>> = sessionRefs.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The state of the screen. */
    val uiState: StateFlow<LogsUiState> = combine(local, refreshed, sessionList, streams.links, names) { current, _, sessions, links, names ->
        build(current, sessions, links, names)
    }.stateIn(scope, SharingStarted.Eagerly, LogsUiState())

    init {
        scope.launch {
            // A StateFlow conflates: the delay gives one read for each REFRESH_MS ms at most.
            sink.revision.collect { revision ->
                refreshed.value = revision
                delay(REFRESH_MS)
            }
        }
    }

    override fun onSelectTab(tab: LogsTab) = local.update { old -> old.copy(tab = tab) }

    override fun onSelectFilter(filter: LogFilter) = local.update { old -> old.copy(filter = filter) }

    override fun onClear() = sink.clear()

    override fun onExport() {
        val current = local.value
        if (current.busy) return
        local.update { old -> old.copy(busy = true) }
        scope.launch {
            val now = wallClock.now()
            val names = names.value
            val title = when (val filter = current.filter) {
                LogFilter.All -> ALL_NAME
                LogFilter.App -> APP_NAME
                is LogFilter.Machine -> nameOf(filter.device, names)
            }
            val text = LogFormat.export(sink.lines(current.filter), title, { device -> nameOf(device, names) }, now, zone)
            val shared = filePicker.share(LogFormat.logFileName(title, now, zone), MIME_TEXT, text.encodeToByteArray())
            local.update { old -> old.copy(busy = false) }
            showResult(shared)
        }
    }

    override fun onRecordChange(deviceId: DeviceId, on: Boolean) {
        val session = connections.sessions.value[deviceId] ?: return
        val device = session.device.value
        if (device.recordStream == on || session.state.value == SessionState.Disconnected) return
        // One call: the session starts a new link with the flag and stays online, so the tab of
        // the machine does not hide and the selected tab does not move.
        connections.setRecordStream(deviceId, on)
    }

    override fun onExportRecord(id: Long) {
        val link = streams.links.value.firstOrNull { record -> record.id == id } ?: return
        val text = streams.export(id) ?: return
        scope.launch {
            val name = LogFormat.streamFileName(nameOf(link.device, names.value), link.link)
            showResult(filePicker.share(name, MIME_TEXT, text.encodeToByteArray()))
        }
    }

    override fun onMessageShown(id: Long) = local.update { old -> if (old.message?.id == id) old.copy(message = null) else old }

    private fun showResult(shared: Boolean) {
        val text = UiText.of(if (shared) Res.string.settings_logs_exported else Res.string.settings_logs_no_share)
        local.update { old -> old.copy(message = LogsMessage(++messageCounter, text)) }
    }

    private fun build(
        current: Local,
        sessions: List<Pair<DeviceRef, SessionState>>,
        links: List<RecordedLink>,
        names: Map<DeviceId, String>,
    ): LogsUiState {
        val sessionNames = sessions.associate { (device, _) -> device.id to device.name }
        val name = { device: DeviceId -> names[device] ?: sessionNames[device]?.takeIf { it.isNotBlank() } ?: device.value }
        val devices = (sink.devices() + sessions.map { (device, _) -> device.id }).distinct().sortedBy { device -> name(device).lowercase() }
        val filters = listOf(
            LogFilterItem(LogFilter.All, UiText.of(Res.string.settings_logs_filter_all)),
            LogFilterItem(LogFilter.App, UiText.of(Res.string.settings_logs_filter_app)),
        ) + devices.map { device -> LogFilterItem(LogFilter.Machine(device), UiText.Plain(name(device))) }
        val lines = sink.lines(current.filter).asReversed().map { line ->
            LogLineUiState(
                sequence = line.sequence,
                time = LogFormat.time(line.time, zone),
                level = line.level,
                tag = line.tag,
                machine = line.device?.let(name),
                text = line.text,
                error = line.error,
            )
        }
        return LogsUiState(
            tab = current.tab,
            filters = filters,
            filter = current.filter,
            lines = lines,
            sessions = sessions.map { (device, state) ->
                RecordSessionUiState(device.id, name(device.id), device.recordStream, available = state != SessionState.Disconnected)
            },
            records = links.map { link ->
                RecordedLinkUiState(
                    id = link.id,
                    name = name(link.device),
                    link = link.link,
                    started = LogFormat.time(link.startedOn, zone),
                    chunkCount = link.chunkCount,
                    byteCount = link.byteCount,
                    open = link.open,
                    truncated = link.truncated,
                )
            },
            busy = current.busy,
            message = current.message,
            maxBytesPerLink = streams.maxBytesPerLink,
        )
    }

    private fun nameOf(device: DeviceId, names: Map<DeviceId, String>): String =
        names[device] ?: connections.sessions.value[device]?.device?.value?.name?.takeIf { it.isNotBlank() } ?: device.value

    companion object {
        /** The shortest time between two reads of the lines. */
        const val REFRESH_MS: Long = 250L

        /** The type of the exported files. */
        const val MIME_TEXT: String = "text/plain"

        /** The machine part of the file name of an export of all lines. */
        const val ALL_NAME: String = "all"

        /** The machine part of the file name of an export of the app lines. */
        const val APP_NAME: String = "app"
    }
}
