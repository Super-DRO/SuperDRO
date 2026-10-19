package org.superdro.core.ui.tabs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.superdro.core.appversion.AppVersion
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.PermissionKind
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.TabState
import org.superdro.core.resources.Res
import org.superdro.core.resources.app_could_not_restore_link
import org.superdro.core.resources.connection_adapter_restarted
import org.superdro.core.resources.connection_identity_differs
import org.superdro.core.resources.connection_restart_failed
import org.superdro.core.resources.frame_could_not_connect
import org.superdro.core.resources.frame_machine_message
import org.superdro.core.resources.frame_reconnecting
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.settings.about.VersionCheck
import org.superdro.core.ui.text.UiText

/**
 * The presenter of the device tabs. The app has one object in
 * the app scope: the frame of a new activity gets the same
 * tabs.
 *
 * Rules:
 * - A tab shows while the session of its machine is online (`AwaitingData`, `Connected`, or
 *   `NoData`).
 * - A tab also shows when a session starts: the session goes to `Connecting(1)` from no session or
 *   from `Disconnected`. Thus the user sees that a new machine connects. When the first attempt
 *   fails before the session is online, the grace time starts. After the grace time the tab hides,
 *   and the snackbar shows "Could not connect". The session continues to try, and the remembered
 *   machines pane shows its status.
 * - A tab whose session goes offline stays for the grace time of 10 s ([GRACE_MS]). The tab then
 *   shows the status of the session ("Reconnecting (n)", or the cause) and the readout keeps the
 *   last values. After the grace time the tab hides, and the snackbar shows "Could not restore the
 *   link".
 * - A user disconnect (the session goes to `Disconnected`) hides the tab at once: the user asked
 *   for it, so a grace time has no use.
 * - The tabs have the order of the remembered machines of [TabStore], then the other sessions in
 *   the order of their start.
 * - One tap selects a tab and writes the selection to [TabStore]. Each new stored selection
 *   selects its tab when the tab shows: the last selected machine at the start (the app opens on
 *   that machine), and a tap on the line of a machine in the notification (`TabStore.select`).
 *   When the selected tab hides, its neighbor becomes the selected tab.
 * - "Disconnect all" runs the [DisconnectAllCommand] of the app: the same command as the action
 *   of the notification.
 * - While the platform keep-alive cannot keep the links in the background
 *   ([KeepAliveStatus.linksCanStopInBackground]), the frame shows a warning.
 * - A link loss of a shown tab and the session events "restart failed", "identity differs", and
 *   "adapter restarted" go to the snackbar with the name of the machine. A link error
 *   shows no modal dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTabsPresenter(
    private val connections: Connections,
    private val tabStore: TabStore,
    machines: MachineRepository,
    context: PresenterContext,
    private val disconnectAll: DisconnectAllCommand = PauseAndDisconnectAll(tabStore, connections),
    keepAliveStatus: StateFlow<KeepAliveStatus> = KeepAliveStatus.idleFlow,
    private val requests: RequestSender = RequestSender {},
    private val appSettings: AppSettingsStore? = null,
    private val currentVersionCode: Int = AppVersion.CODE,
) : ScopedPresenter(context), DeviceTabsEvents {

    private class SessionSnapshot(val id: DeviceId, val device: DeviceRef, val state: SessionState)

    private val mutex = Mutex()
    private val state = MutableStateFlow(DeviceTabsUiState())

    /** The state of the frame. */
    val uiState: StateFlow<DeviceTabsUiState> = state.asStateFlow()

    private var sessions: List<SessionSnapshot> = emptyList()
    private var tabState: TabState = TabState()
    private var names: Map<String, String> = emptyMap()
    private val visible = LinkedHashSet<DeviceId>()
    private val lastStates = HashMap<DeviceId, SessionState>()

    /** The tabs of the sessions that started and were not online yet. */
    private val starting = HashSet<DeviceId>()
    private val graceJobs = HashMap<DeviceId, Job>()
    private var selected: DeviceId? = null
    private var seenStoredSelection: DeviceId? = null
    private var wantedSelection: DeviceId? = null
    private var backgroundWarning = false
    private var currentKeepAliveStatus: KeepAliveStatus = KeepAliveStatus.Idle
    private var availableUpdateVersion: String? = null
    private var order: List<DeviceId> = emptyList()
    private var menuFor: DeviceId? = null
    private var overflowOpen = false
    private var messages: List<TabMessage> = emptyList()
    private var nextMessageId = 1L

    init {
        val sessionFlow: Flow<List<SessionSnapshot>> = connections.sessions.flatMapLatest { map ->
            if (map.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(map.values.map { session -> combine(session.device, session.state) { device, state -> SessionSnapshot(session.id, device, state) } }) { array ->
                    array.toList()
                }
            }
        }
        val nameFlow = machines.profiles()
            .map { profiles -> profiles.associate { profile -> profile.id to profile.name } }
            .onStart { emit(emptyMap()) }
            .distinctUntilChanged()
        scope.launch {
            combine(sessionFlow, tabStore.state, nameFlow) { snapshots, tabs, profileNames -> Triple(snapshots, tabs, profileNames) }
                .collect { (snapshots, tabs, profileNames) ->
                    mutex.withLock {
                        sessions = snapshots
                        tabState = tabs
                        names = profileNames
                        applySessions()
                        publish()
                    }
                }
        }
        scope.launch {
            keepAliveStatus.collect { status ->
                mutex.withLock {
                    currentKeepAliveStatus = status
                    backgroundWarning = status.linksCanStopInBackground
                    publish()
                }
            }
        }
        if (appSettings != null) {
            scope.launch {
                appSettings.settings.collect { settings ->
                    val available = VersionCheck.unseenReleaseVersion(settings, currentVersionCode)
                    mutex.withLock {
                        availableUpdateVersion = available
                        publish()
                    }
                }
            }
        }
        scope.launch {
            connections.sessions.flatMapLatest { map ->
                if (map.isEmpty()) emptyFlow() else map.values.map { session -> session.events.map { event -> session.id to event } }.merge()
            }.collect { (id, event) -> onSessionEvent(id, event) }
        }
    }

    override fun onSelect(id: DeviceId) {
        scope.launch {
            mutex.withLock {
                if (id !in visible) return@withLock
                selected = id
                wantedSelection = null
                menuFor = null
                tabStore.select(id.value)
                publish()
            }
        }
    }

    override fun onOpenMenu(id: DeviceId) = change { menuFor = id }

    override fun onCloseMenu() = change { menuFor = null }

    override fun onPause(id: DeviceId) {
        change { menuFor = null }
        tabStore.setPaused(id.value, true)
        connections.disconnect(id)
    }

    override fun onOpenOverflow() = change { overflowOpen = true }

    override fun onCloseOverflow() = change { overflowOpen = false }

    /**
     * "Disconnect all": the same command as the action of the
     * notification. Each remembered machine pauses, so that no restore connects it again, and each
     * session goes to `Disconnected`.
     */
    override fun onDisconnectAll() {
        change { overflowOpen = false }
        disconnectAll.disconnectAll()
    }

    override fun onMessageShown(id: Long) = change { messages = messages.filterNot { message -> message.id == id } }

    override fun onRequestBackgroundPermission() {
        requests.send(ConnectionRequest.RequestPermissions(setOf(PermissionKind.BluetoothConnect)))
    }

    override fun onOpenBackgroundAppSettings() {
        requests.send(ConnectionRequest.OpenAppSettings)
    }

    override fun onAcknowledgeUpdate() {
        appSettings?.update(VersionCheck::acknowledgeRelease)
    }

    private fun change(block: () -> Unit) {
        scope.launch {
            mutex.withLock {
                block()
                publish()
            }
        }
    }

    private suspend fun onSessionEvent(id: DeviceId, event: SessionEvent) {
        val text = when (event) {
            is SessionEvent.RestartFailed -> UiText.of(Res.string.connection_restart_failed)
            is SessionEvent.IdentityDiffers -> UiText.of(Res.string.connection_identity_differs)
            is SessionEvent.AdapterRestarted -> UiText.of(Res.string.connection_adapter_restarted)
            else -> return
        }
        mutex.withLock {
            addMessage(id, text)
            publish()
        }
    }

    /** Applies the session states to the visible tabs. Call it under [mutex]. */
    private fun applySessions() {
        val ids = sessions.mapTo(HashSet()) { snapshot -> snapshot.id }
        (visible.toList() + lastStates.keys.toList()).filter { id -> id !in ids }.forEach { id ->
            hide(id)
            lastStates.remove(id)
        }
        sessions.forEach { snapshot ->
            val id = snapshot.id
            val state = snapshot.state
            val before = lastStates.put(id, state)
            val wasOnline = before?.isOnline == true
            when {
                state.isOnline -> {
                    visible += id
                    starting -= id
                    graceJobs.remove(id)?.cancel()
                }
                state is SessionState.Connecting && (before == null || before == SessionState.Disconnected) -> {
                    visible += id
                    starting += id
                    graceJobs.remove(id)?.cancel()
                }
                id !in visible -> Unit
                state == SessionState.Disconnected -> hide(id)
                // The first attempt of a new session runs: no grace time yet.
                id in starting && state is SessionState.Connecting && graceJobs[id] == null -> Unit
                else -> {
                    if (wasOnline) addMessage(id, linkLostText(state))
                    if (graceJobs[id] == null) graceJobs[id] = startGrace(id)
                }
            }
        }
    }

    private fun linkLostText(state: SessionState): UiText = when (state) {
        is SessionState.Reconnecting, is SessionState.Connecting -> UiText.of(Res.string.frame_reconnecting)
        else -> TabStatusRules.of(state).text
    }

    private fun startGrace(id: DeviceId): Job = scope.launch {
        delay(GRACE_MS)
        mutex.withLock {
            graceJobs.remove(id)
            val snapshot = sessions.firstOrNull { it.id == id }
            if (snapshot == null || !snapshot.state.isOnline) {
                if (id in visible) {
                    val neverOnline = id in starting
                    hide(id)
                    addMessage(id, UiText.of(if (neverOnline) Res.string.frame_could_not_connect else Res.string.app_could_not_restore_link))
                }
                publish()
            }
        }
    }

    private fun hide(id: DeviceId) {
        visible -= id
        starting -= id
        graceJobs.remove(id)?.cancel()
        if (menuFor == id) menuFor = null
    }

    private fun addMessage(id: DeviceId, text: UiText) {
        messages = messages + TabMessage(nextMessageId++, UiText.of(Res.string.frame_machine_message, nameOf(id), text))
    }

    private fun nameOf(id: DeviceId): String =
        names[id.value] ?: sessions.firstOrNull { it.id == id }?.device?.name ?: id.value

    /** Gives the order of the tabs: the remembered order, then the other sessions in start order. */
    private fun tabOrder(): List<DeviceId> {
        val remembered = tabState.remembered.map { machine -> DeviceId(machine.profileId) }
        val first = remembered.filter { id -> id in visible }
        val rest = sessions.map { it.id }.filter { id -> id in visible && id !in first }
        return first + rest
    }

    /** Publishes the state. Call it under [mutex]. */
    private fun publish() {
        val newOrder = tabOrder()
        // The stored selection is the last selected machine at the start, and a tap on the line of
        // a machine in the notification (TabStore.select). Each new value selects its tab when the
        // tab shows.
        val stored = tabState.selectedProfileId?.let(::DeviceId)
        if (stored != seenStoredSelection) {
            seenStoredSelection = stored
            if (stored != null) wantedSelection = stored
        }
        wantedSelection?.let { wanted ->
            if (wanted in newOrder) {
                selected = wanted
                wantedSelection = null
            }
        }
        val current = selected
        if (current == null || current !in newOrder) selected = neighbor(current, newOrder)
        order = newOrder
        val tabs = newOrder.map { id ->
            val snapshot = sessions.first { it.id == id }
            DeviceTabUiState(
                id = id,
                name = nameOf(id),
                transport = snapshot.device.transport,
                state = snapshot.state,
                status = TabStatusRules.of(snapshot.state),
                inGrace = id in graceJobs,
            )
        }
        state.value = DeviceTabsUiState(
            tabs = tabs,
            selected = selected,
            rememberedCount = tabState.remembered.size,
            menuFor = menuFor?.takeIf { it in visible },
            overflowOpen = overflowOpen,
            messages = messages,
            backgroundWarning = backgroundWarning,
            keepAliveStatus = currentKeepAliveStatus,
            availableUpdateVersion = availableUpdateVersion,
        )
    }

    /** Gives the tab that follows [old] when [old] hides: the next tab, else the previous tab, else the first tab. */
    private fun neighbor(old: DeviceId?, newOrder: List<DeviceId>): DeviceId? {
        if (newOrder.isEmpty()) return null
        val index = if (old == null) -1 else order.indexOf(old)
        if (index < 0) return newOrder.first()
        val after = order.drop(index + 1).firstOrNull { it in newOrder }
        val before = order.take(index).lastOrNull { it in newOrder }
        return after ?: before ?: newOrder.first()
    }

    companion object {
        /** The grace time before the tab of an offline machine hides. */
        const val GRACE_MS: Long = 10_000L
    }
}
