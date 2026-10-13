package org.superdro.core.ui.graphics.thread

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.functions.thread.ThreadCut
import org.superdro.core.functions.thread.ThreadCutState
import org.superdro.core.functions.thread.ThreadCutStatus
import org.superdro.core.functions.thread.ThreadPassRecord
import org.superdro.core.functions.thread.ThreadPoints
import org.superdro.core.model.AxisId
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ThreadPass
import org.superdro.core.model.ThreadSpec
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.SubDatumOperations
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.readout.TabReadout

/** One thread of the chooser of the thread cut panel: "<tags> · <designation> <internal|external>". */
@Immutable
data class ThreadChoice(val id: String, val tag: String, val designation: String, val internal: Boolean)

/** The content of the thread cut panel. */
@Immutable
sealed interface ThreadPanelUiState {
    /** "No threads in this workspace yet. …" */
    data object NoThreads : ThreadPanelUiState

    /** "Select a Thread": one row for each thread that the user can arm. */
    data class Chooser(val threads: List<ThreadChoice>) : ThreadPanelUiState

    /**
     * "Cutting <designation> <internal|external>" with the live values.
     *
     * @property lastPass The text of "Last pass" in the X readout format, or null for "—".
     * @property infeed The text of "Infeed", or null for "—".
     * @property cutLoad The cut load ratio with one decimal, or null for "—".
     */
    data class Armed(
        val thread: ThreadChoice,
        val status: ThreadCutStatus,
        val lastPass: String?,
        val infeed: String?,
        val cutLoad: String?,
        val metric: Boolean,
    ) : ThreadPanelUiState
}

/**
 * The thread arm and the thread cut panel of the graphical view. The panel has no pass schedule.
 *
 * - The arm button: when the panel is open, it disarms and closes. Else it lists the threads with
 *   StartZ ≠ StopZ. With no thread it asks "There are no threads in this workspace. Do you want to
 *   create one?". With one thread it arms that thread at once.
 * - The arm step makes the 4 finish corner sub-datums again when they are not there, starts a new
 *   pass record for a new thread, stores the armed thread in the workspace, and activates the
 *   corner with X > 0 and the smallest Z.
 * - Each readout frame updates the pass record. A new record goes into the workspace
 *   ([org.superdro.core.model.Workspace.threadPass]).
 */
class ThreadCutController(
    private val scope: CoroutineScope,
    private val deps: GraphicsDependencies,
    private val readout: TabReadout,
    threads: StateFlow<List<ThreadSpec>>,
) {
    private val panelOpen = MutableStateFlow(false)
    private val askCreate = MutableStateFlow(false)
    private var cut = ThreadCutState()

    /** True while the question "There are no threads in this workspace. …" shows. */
    val createQuestion: StateFlow<Boolean> = askCreate

    /** The threads that the user can arm: StartZ ≠ StopZ. */
    val armable: StateFlow<List<ThreadSpec>> = threads

    /** The panel, or null when it is closed and no thread is armed. */
    val panel: StateFlow<ThreadPanelUiState?> = combine(readout.frame, threads, panelOpen) { frame, list, open ->
        panelOf(frame, list, open)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** True when the panel is open or a thread is armed. */
    val active: Boolean get() = panel.value != null

    init {
        scope.launch {
            readout.frame.filterNotNull().collect { frame -> onFrame(frame, threads.value) }
        }
    }

    /** A tap on the arm button "Arm thread cutting". */
    fun onArmButton() {
        val frame = readout.frame.value ?: return
        if (panelOpen.value || frame.workspace.armedThreadId != null) {
            disarm()
            return
        }
        val list = ThreadPoints.armable(armable.value)
        when (list.size) {
            0 -> askCreate.value = true
            1 -> {
                panelOpen.value = true
                arm(list.single().id)
            }
            else -> panelOpen.value = true
        }
    }

    /** The answer to the create question. */
    fun onCreateQuestionClosed() {
        askCreate.value = false
    }

    /** Arms the thread [threadId]. */
    fun arm(threadId: String) {
        val frame = readout.frame.value ?: return
        val spec = armable.value.firstOrNull { thread -> thread.id == threadId } ?: return
        val workspaceId = frame.workspace.id
        panelOpen.value = true
        scope.launch {
            val subDatums = frame.workspace.subDatums
            if (ThreadPoints.needsRebuild(spec, subDatums)) {
                val linked = subDatums.filter { point ->
                    point.groupKind == SubDatumGroupKind.Thread && ThreadPoints.parseGroupRef(point.groupRef)?.first == spec.id
                }
                if (linked.isNotEmpty()) deps.machines.deleteSubDatums(workspaceId, linked.map { point -> point.id })
                deps.machines.insertSubDatums(workspaceId, ThreadPoints.toSubDatums(spec))
            }
            cut = ThreadCut.arm(cut.copy(record = recordOf(frame.workspace.threadPass)), spec.id)
            readout.applyWorkspace(null) { workspace ->
                workspace.copy(armedThreadId = spec.id, threadPass = passOf(cut.record))
            }
            activateCorner(spec)
        }
    }

    /** Disarms the thread and closes the panel ("Close thread cutting"). */
    fun disarm() {
        panelOpen.value = false
        if (readout.frame.value?.workspace?.armedThreadId != null) {
            readout.applyWorkspace(null) { workspace -> workspace.copy(armedThreadId = null) }
        }
    }

    private suspend fun activateCorner(spec: ThreadSpec) {
        val corner = ThreadPoints.activeCorner(spec) ?: return
        val ref = ThreadPoints.groupRef(spec.id, corner.role)
        val frame = withTimeoutOrNull(CORNER_WAIT_MS) {
            readout.frame.first { current ->
                current?.workspace?.subDatums?.any { point -> point.groupRef == ref && (point.x ?: 0.0) > 0.0 } == true
            }
        } ?: return
        val point = frame.workspace.subDatums.first { item -> item.groupRef == ref && (item.x ?: 0.0) > 0.0 }
        if (frame.workspace.activeSubDatumId != point.id) readout.apply(SubDatumOperations.activate(frame, point.id), null)
    }

    private fun onFrame(frame: ReadoutFrame, threads: List<ThreadSpec>) {
        val spec = frame.workspace.armedThreadId?.let { id -> threads.firstOrNull { thread -> thread.id == id } } ?: return
        val x = frame.reading(AxisId.X)
        val z = frame.reading(AxisId.Z)
        val base = cut.copy(record = recordOf(frame.workspace.threadPass).takeIf { record -> record.threadId == spec.id } ?: cut.record)
        val next = ThreadCut.update(spec, base, x.absolute, if (z.hasValue) z.absolute else null)
        cut = next
        if (next.record != base.record) {
            readout.applyWorkspace(null) { workspace -> workspace.copy(threadPass = passOf(next.record)) }
        }
    }

    private fun panelOf(frame: ReadoutFrame?, threads: List<ThreadSpec>, open: Boolean): ThreadPanelUiState? {
        if (frame == null || !frame.profile.machineType.isLathe) return null
        val armedId = frame.workspace.armedThreadId
        val armed = armedId?.let { id -> threads.firstOrNull { thread -> thread.id == id } }
        if (armed == null && !open) return null
        if (armed == null) {
            val list = ThreadPoints.armable(threads)
            return if (list.isEmpty()) ThreadPanelUiState.NoThreads else ThreadPanelUiState.Chooser(list.map(::choiceOf))
        }
        val x = frame.reading(AxisId.X)
        val z = frame.reading(AxisId.Z)
        val values = ThreadCut.values(
            spec = armed,
            record = recordOf(frame.workspace.threadPass).takeIf { record -> record.threadId == armed.id } ?: ThreadPassRecord(),
            xUm = x.absolute,
            readoutXUm = x.value,
            zUm = if (z.hasValue) z.absolute else null,
            hasActiveCorner = frame.workspace.activeSubDatumId != null,
        )
        fun text(radialUm: Double?): String? = radialUm?.let { value ->
            frame.pattern(AxisId.X).format(frame.converter(AxisId.X).toDisplay(frame.postProcessor(AxisId.X).toShown(value)))
        }
        return ThreadPanelUiState.Armed(
            thread = choiceOf(armed),
            status = values.status,
            lastPass = text(values.lastPassUm),
            infeed = text(values.infeedUm),
            cutLoad = values.cutLoadRatio?.let { ratio -> oneDecimal(ratio) },
            metric = frame.metric(AxisId.X),
        )
    }

    private fun choiceOf(spec: ThreadSpec): ThreadChoice = ThreadChoice(spec.id, spec.tag, spec.size, spec.internal)

    companion object {
        /** The longest wait for the corner sub-datums in the readout after the arm step. */
        const val CORNER_WAIT_MS: Long = 2_000L

        /** Gives [value] with one decimal, for example "0.7". */
        fun oneDecimal(value: Double): String {
            val scaled = kotlin.math.floor(value * 10.0 + 0.5).toLong()
            val negative = scaled < 0L
            val magnitude = kotlin.math.abs(scaled)
            return (if (negative) "-" else "") + "${magnitude / 10}.${magnitude % 10}"
        }

        private fun recordOf(pass: ThreadPass): ThreadPassRecord = ThreadPassRecord(pass.deepestUm, pass.previousDeepestUm, pass.threadId)

        private fun passOf(record: ThreadPassRecord): ThreadPass = ThreadPass(record.deepestUm, record.previousDeepestUm, record.threadId)
    }
}
