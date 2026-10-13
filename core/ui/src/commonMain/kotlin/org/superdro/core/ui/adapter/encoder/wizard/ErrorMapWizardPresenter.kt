package org.superdro.core.ui.adapter.encoder.wizard

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.errormap.ErrorMapWizard
import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.functions.errormap.PassRowInfo
import org.superdro.core.functions.errormap.WizardSlot
import org.superdro.core.model.Channels
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.presenter.ScopedPresenter
import kotlinx.coroutines.flow.emptyFlow
import org.superdro.core.readout.input.ChannelValue

/**
 * One pass row of the wizard: "N samples · 0 – farthest – end", or an empty place.
 */
@Immutable
data class PassRowUi(val info: PassRowInfo?, val truncated: Boolean = false)

/**
 * The state of the error map wizard of one target input.
 *
 * @property references The inputs, except the target, that sent data.
 * @property liveTarget The target count relative to its first value, or null with no value.
 * @property liveReference The reference count relative to its first value, or null.
 * @property recording The slot that records now, or null.
 * @property recordingSamples The number of pairs of the pass that records now.
 * @property passes The five pass places: a recorded pass, or a row with no info.
 * @property recovery True while the question "Recovery File Found" shows.
 * @property confirmDiscard True while the question of "Discard" shows.
 * @property openReport True when the session file is in the store after "Review Map": the
 *   screen opens the report, then calls [ErrorMapWizardPresenter.onReportOpened].
 * @property exited True when the session file is in the store after "Exit": the screen closes.
 */
@Immutable
data class ErrorMapWizardUiState(
    val input: Char,
    val loaded: Boolean = false,
    val available: Boolean = false,
    val connected: Boolean = false,
    val references: List<Char> = emptyList(),
    val reference: Char? = null,
    val liveTarget: Long? = null,
    val liveReference: Long? = null,
    val recording: WizardSlot? = null,
    val recordingSamples: Int = 0,
    val passes: List<PassRowUi> = List(ErrorMapWizard.MAX_PASSES) { PassRowUi(null) },
    val verification: PassRowUi = PassRowUi(null),
    val canStartPass: Boolean = false,
    val canStartVerification: Boolean = false,
    val canReview: Boolean = false,
    val recovery: Boolean = false,
    val confirmDiscard: Boolean = false,
    val openReport: Boolean = false,
    val exited: Boolean = false,
)

/**
 * The presenter of the error map wizard of one target input. The math is in
 * `core:functions` ([ErrorMapWizard]).
 *
 * - The wizard records the pairs of the raw counts of the target and the reference that have the
 *   same time stamp: each snapshot of the session with a new common time gives one pair.
 * - The session file goes to the store after each stop and each clear. An empty session deletes
 *   the file. "Exit" keeps the file. At the open, a stored session asks "Recovery File Found".
 * - The wizard needs the online source: the adapter profile of the tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorMapWizardPresenter(
    val deviceId: DeviceId,
    input: Char,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    /** The lowercase target input. */
    val input: Char = Channels.normalize(input)

    private val mutable = MutableStateFlow(ErrorMapWizardUiState(this.input))
    private val fileLock = Mutex()
    private var adapterId: String? = null
    private var wizard: ErrorMapWizard? = null
    private var stored: ErrorMapWizardSession? = null
    private var firstTarget: Long? = null
    private var firstReference: Long? = null
    private var lastSnapshot: InputSnapshot = InputSnapshot.EMPTY

    /** The state of the screen. */
    val uiState: StateFlow<ErrorMapWizardUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            val adapter = deps.tabAdapter(deviceId).first()
            if (adapter == null) {
                mutable.update { it.copy(loaded = true, available = false) }
                return@launch
            }
            adapterId = adapter.id
            val session = deps.adapters.loadWizardSession(adapter.id, input, ErrorMapWizardSession.serializer())
            wizard = ErrorMapWizard(adapter.id, input)
            if (session != null && !session.isEmpty) {
                stored = session
                mutable.update { it.copy(recovery = true) }
            }
            mutable.update { it.copy(loaded = true, available = true) }
            publish()
            launch { deps.sessionState(deviceId).collect { state -> mutable.update { it.copy(connected = state is SessionState.Connected) } } }
            // The pairs come from each sample of the session, not from the conflated inputs, so that
            // no pair is lost. The inputs give the live values only.
            launch {
                deps.session(deviceId)
                    .flatMapLatest { session -> session?.samples ?: emptyFlow() }
                    .collect(::onSample)
            }
            deps.session(deviceId)
                .flatMapLatest { session -> session?.inputs ?: flowOf(null) }
                .filterNotNull()
                .collect(::onSnapshot)
        }
    }

    private fun onSnapshot(snapshot: InputSnapshot) {
        lastSnapshot = snapshot
        val current = wizard ?: return
        val target = snapshot.channels[input]
        val reference = current.referenceInput?.let { snapshot.channels[it] }
        if (target != null && firstTarget == null) firstTarget = target.rawCount
        if (reference != null && firstReference == null) firstReference = reference.rawCount
        publish()
    }

    /**
     * One sample of the session: the last sample of each input. A target sample and a reference
     * sample with the same time stamp give one pair. A time gives at most one pair.
     */
    private fun onSample(sample: ChannelValue) {
        val letter = Channels.normalize(sample.input)
        latest[letter] = sample
        val current = wizard ?: return
        val referenceInput = current.referenceInput ?: return
        if (letter != input && letter != referenceInput) return
        val target = latest[input] ?: return
        val reference = latest[referenceInput] ?: return
        if (target.timeMs != reference.timeMs || target.timeMs == lastPairTime || current.recording == null) return
        lastPairTime = target.timeMs
        current.offer(target.rawCount, reference.rawCount, target.timeMs)
        publish()
    }

    /** The last sample of each input, and the time of the last pair. */
    private val latest = HashMap<Char, ChannelValue>()
    private var lastPairTime: Long? = null

    private fun publish() {
        val current = wizard ?: return
        val snapshot = lastSnapshot
        val references = Channels.axisChannels.filter { letter -> letter != input && snapshot.channels.containsKey(letter) }
        val target = snapshot.channels[input]?.rawCount
        val reference = current.referenceInput?.let { snapshot.channels[it]?.rawCount }
        val passes = current.passes
        mutable.update { state ->
            state.copy(
                references = references,
                reference = current.referenceInput,
                liveTarget = target?.let { value -> firstTarget?.let { value - it } },
                liveReference = reference?.let { value -> firstReference?.let { value - it } },
                recording = current.recording,
                recordingSamples = current.currentPass()?.pairs?.size ?: 0,
                passes = List(ErrorMapWizard.MAX_PASSES) { index ->
                    passes.getOrNull(index)?.let { pass -> PassRowUi(pass.rowInfo(), pass.truncated) } ?: PassRowUi(null)
                },
                verification = current.verification?.let { PassRowUi(it.rowInfo(), it.truncated) } ?: PassRowUi(null),
                canStartPass = current.canStartPass,
                canStartVerification = current.canStartVerification,
                canReview = current.canReview,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Events

    /** "Yes" of "Recovery File Found": the wizard loads the stored passes. */
    fun onRecover() {
        val session = stored ?: return
        val id = adapterId ?: return
        stored = null
        wizard = ErrorMapWizard(
            adapterId = id,
            input = input,
            referenceInput = session.reference?.firstOrNull()?.let(Channels::normalize),
            passes = session.passes.map { it.toPass() },
            verification = session.verification?.toPass(),
        )
        firstReference = null
        mutable.update { it.copy(recovery = false) }
        publish()
    }

    /** "No" of "Recovery File Found": a new session starts, and the old file goes away. */
    fun onDropRecovery() {
        stored = null
        mutable.update { it.copy(recovery = false) }
        saveSession()
    }

    /** Selects the reference input. A change of the reference deletes the recorded passes. */
    fun onReference(reference: Char) {
        val current = wizard ?: return
        val had = current.passes.isNotEmpty() || current.verification != null
        if (!current.selectReference(Channels.normalize(reference))) return
        firstReference = null
        if (had && current.passes.isEmpty()) saveSession()
        publish()
    }

    /** "Start" of the next pass. */
    fun onStartPass() {
        wizard?.startPass() ?: return
        publish()
    }

    /** "Start" of the verification pass. */
    fun onStartVerification() {
        wizard?.startVerification() ?: return
        publish()
    }

    /** "Stop": the pass ends, and the session file goes to the store. */
    fun onStop() {
        val current = wizard ?: return
        if (current.recording == null) return
        current.stop()
        saveSession()
        publish()
    }

    /** Clears the pass [index]. */
    fun onClearPass(index: Int) {
        wizard?.clearPass(index) ?: return
        saveSession()
        publish()
    }

    /** Clears the verification pass. */
    fun onClearVerification() {
        wizard?.clearVerification() ?: return
        saveSession()
        publish()
    }

    /**
     * "Review Map": stops the pass that records and saves the session. When at least 1 pass has
     * samples, [ErrorMapWizardUiState.openReport] becomes true after the save.
     */
    fun onReview() {
        val current = wizard ?: return
        if (!current.canReview) return
        current.stop()
        val save = saveSession()
        publish()
        scope.launch {
            save?.join()
            mutable.update { it.copy(openReport = true) }
        }
    }

    /** The screen opened the report. */
    fun onReportOpened() = mutable.update { it.copy(openReport = false) }

    fun onDiscard() = mutable.update { it.copy(confirmDiscard = true) }

    /** "Discard" of the question: deletes all passes and the session file. */
    fun onConfirmDiscard() {
        wizard?.discard()
        mutable.update { it.copy(confirmDiscard = false) }
        saveSession()
        publish()
    }

    fun onCancelDiscard() = mutable.update { it.copy(confirmDiscard = false) }

    /**
     * "Exit": stops the pass that records and keeps the session file.
     * [ErrorMapWizardUiState.exited] becomes true after the save.
     */
    fun onExit() {
        val current = wizard
        val save = if (current?.recording != null) {
            current.stop()
            saveSession()
        } else {
            null
        }
        scope.launch {
            save?.join()
            mutable.update { it.copy(exited = true) }
        }
    }

    /**
     * The entry closes, for example with the back gesture. A pass that records stops, and its
     * session goes to the store in a scope that the close does not cancel.
     */
    override fun close() {
        val current = wizard
        if (current?.recording != null) {
            current.stop()
            val id = adapterId
            val session = current.toSession()
            if (id != null) {
                CoroutineScope(deps.context.dispatcher).launch { writeSession(id, session) }
            }
        }
        super.close()
    }

    // ---------------------------------------------------------------------------------------
    // The session file

    private fun saveSession(): Job? {
        val id = adapterId ?: return null
        val session = wizard?.toSession() ?: return null
        return scope.launch { writeSession(id, session) }
    }

    private suspend fun writeSession(id: String, session: ErrorMapWizardSession) {
        fileLock.withLock {
            if (session.isEmpty) {
                deps.adapters.deleteWizardSession(id, input)
            } else {
                deps.adapters.saveWizardSession(id, input, ErrorMapWizardSession.serializer(), session)
            }
        }
    }
}
