package org.superdro.core.functions.measurement

import kotlin.math.abs
import kotlin.math.max
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.Units

/** The severity of a point note. */
enum class NoteSeverity { Valid, Suspect, Invalid }

/**
 * A system note of a slot point. The
 * KDoc of each value gives the English text of the message line.
 *
 * @property wire The text in [CollectedPoint.notes] and in the log.
 * @property exportText The text in the CSV export and in the text sheet.
 */
enum class PointNote(val wire: String, val exportText: String, val severity: NoteSeverity) {
    /** "Tip radius not compensated for this measurement" */
    Uncompensated("UNCOMPENSATED", "uncompensated", NoteSeverity.Suspect),

    /**
     * "Rotation changed by %1$s after this set was started". `%1$s` is the angle change in degrees
     * with the format `"%.3f°"`.
     */
    FrameChanged("FRAME_CHANGED", "frame changed", NoteSeverity.Suspect),
    ;

    companion object {
        /** Gives the note of the wire text [wire], or null. */
        fun ofWire(wire: String): PointNote? = entries.firstOrNull { note -> note.wire == wire }

        /**
         * Gives the export text of [wire]: the text in lower case with spaces in place of `_`, for
         * example `frame changed`.
         */
        fun exportTextOf(wire: String): String = ofWire(wire)?.exportText ?: wire.lowercase().replace('_', ' ')
    }
}

/**
 * The frame angles of a measurement set, in arc seconds: the axis alignment angle and the part
 * rotation angle (`Workspace.axisAlignment.angleArcSec`, `Workspace.partRotation.angleArcSec`).
 */
data class FrameAngles(val alignmentArcSec: Double = 0.0, val rotationArcSec: Double = 0.0) {

    /**
     * Gives the change from [start] to this frame in arc seconds: `max(|Δalignment|, |Δrotation|)`.
     * Each difference goes into −180° to +180° first.
     */
    fun changeFrom(start: FrameAngles): Double =
        max(abs(halfTurn(alignmentArcSec - start.alignmentArcSec)), abs(halfTurn(rotationArcSec - start.rotationArcSec)))

    private fun halfTurn(arcSec: Double): Double {
        val turn = Units.ARCSEC_PER_REVOLUTION
        var value = arcSec % turn
        if (value > turn / 2.0) value -= turn
        if (value < -turn / 2.0) value += turn
        return value
    }

    companion object {
        /** A change of more than this value in arc seconds gives the note [PointNote.FrameChanged]. */
        const val FRAME_CHANGE_LIMIT_ARCSEC: Double = 2.0
    }
}

/** The state of one slot button. */
enum class SlotState {
    /** The first empty slot. "Capture the current position here" */
    Armed,

    /** A point is in the slot. "Point captured. Long-press to clear" */
    Filled,

    /** A point with a note of the severity [NoteSeverity.Suspect] is in the slot. */
    FilledSuspect,

    /** An empty slot after the armed slot. "Waiting for an earlier point" */
    Blank,
}

/** One note of the message line, with the angle change of [PointNote.FrameChanged] in degrees. */
data class NoteMessage(val note: PointNote, val angleChangeDegrees: Double? = null)

/** The message line of the panel. */
sealed interface MeasurementMessage {

    /** The step text of the armed slot. */
    data class Step(val step: MeasurementStep) : MeasurementMessage

    /** The last probe refusal. */
    data class ProbeError(val refusal: ProbeRefusal) : MeasurementMessage

    /** The failure of the complete set. */
    data class Failure(val failure: MeasurementFailure) : MeasurementMessage

    /** The notes of the points. The UI puts two spaces between two notes. */
    data class Notes(val notes: List<NoteMessage>) : MeasurementMessage
}

/** The status of a capture. The UI selects the sound from it. */
enum class CaptureStatus {
    /** The point went into a slot. The set is not complete. */
    Added,

    /** The point went into the last empty slot. The set is complete. */
    Completed,

    /** The set is complete, so the point did not go into a slot. */
    NotAdded,
}

/** The result of [MeasurementSession.capture]. */
data class CaptureResult(val session: MeasurementSession, val status: CaptureStatus)

/**
 * The state of one open measurement function. The type is immutable: each change
 * gives a new session.
 *
 * Rules:
 *
 * - The slots fill in order. A capture goes into the first empty slot. A cleared slot becomes
 *   the armed slot again.
 * - The frame snapshot occurs when a point goes into an empty set.
 * - A point gets [PointNote.Uncompensated] when it has a tip radius and the function does not
 *   compensate the tip (Point, Distance). A point gets [PointNote.FrameChanged] when the frame
 *   changed by more than 2″ since the snapshot.
 * - The user can log the set when all slots are full, the set is not logged, and at least one
 *   result has a value. A change of a point or of the note resets the logged state.
 *
 * @property toleranceUm The fit tolerance of [MeasurementTolerance].
 * @property slots The point of each slot, or null.
 * @property frameChangeDegrees The frame change of each slot point in degrees, when the point has
 *   the note [PointNote.FrameChanged].
 * @property frameAtStart The frame snapshot of the set, or null for an empty set.
 * @property note The note of the user.
 * @property loggedSequence The log sequence of the set, or null when the set is not logged.
 * @property lastProbeError The last probe refusal. The next capture clears it.
 */
data class MeasurementSession(
    val verb: MeasurementVerb,
    val toleranceUm: Double = MeasurementTolerance.FACTOR * MeasurementTolerance.DEFAULT_STEP_UM,
    val slots: List<CollectedPoint?> = List(verb.slotCount) { null },
    val frameChangeDegrees: List<Double?> = List(verb.slotCount) { null },
    val frameAtStart: FrameAngles? = null,
    val note: String = "",
    val loggedSequence: Int? = null,
    val lastProbeError: ProbeRefusal? = null,
) {
    init {
        require(slots.size == verb.slotCount) { "the function ${verb.wire} has ${verb.slotCount} slots" }
        require(frameChangeDegrees.size == verb.slotCount) { "one frame change for each slot" }
    }

    /** The index of the armed slot (the first empty slot), or null when the set is complete. */
    val armedSlot: Int? get() = slots.indexOfFirst { point -> point == null }.takeIf { index -> index >= 0 }

    /** True when all slots have a point. */
    val isComplete: Boolean get() = slots.all { point -> point != null }

    /** True when all slots are empty. The reset button is then disabled. */
    val isEmpty: Boolean get() = slots.all { point -> point == null }

    /** Gives the results and the failure of the points. */
    fun evaluate(): MeasurementEvaluation = MeasurementFunctions.evaluate(verb, slots, toleranceUm)

    /** True when the user can log the set. */
    val canLog: Boolean get() = isComplete && loggedSequence == null && evaluate().hasValue

    /** Gives the state of the slot [index]. */
    fun slotState(index: Int): SlotState {
        val point = slots[index]
        return when {
            point != null -> {
                val suspect = point.notes.any { wire -> PointNote.ofWire(wire)?.severity == NoteSeverity.Suspect }
                if (suspect) SlotState.FilledSuspect else SlotState.Filled
            }
            index == armedSlot -> SlotState.Armed
            else -> SlotState.Blank
        }
    }

    /**
     * Puts [point] into the armed slot, with its notes. [frame] is the current frame. A complete
     * set does not change and gives [CaptureStatus.NotAdded].
     */
    fun capture(point: CollectedPoint, frame: FrameAngles = FrameAngles()): CaptureResult {
        val index = armedSlot ?: return CaptureResult(this, CaptureStatus.NotAdded)
        val start = if (isEmpty) frame else frameAtStart ?: frame
        val notes = point.notes.toMutableList()
        if (verb.uncompensated && MeasurementMath.tipRadius(point) > 0.0) notes.addNote(PointNote.Uncompensated)
        val changeArcSec = frame.changeFrom(start)
        val frameChanged = changeArcSec > FrameAngles.FRAME_CHANGE_LIMIT_ARCSEC
        if (frameChanged) notes.addNote(PointNote.FrameChanged)
        val next = copy(
            slots = slots.replaced(index, point.copy(notes = notes)),
            frameChangeDegrees = frameChangeDegrees.replaced(
                index,
                if (frameChanged) changeArcSec / Units.ARCSEC_PER_DEGREE else null,
            ),
            frameAtStart = start,
            loggedSequence = null,
            lastProbeError = null,
        )
        return CaptureResult(next, if (next.isComplete) CaptureStatus.Completed else CaptureStatus.Added)
    }

    /** Keeps the probe refusal [refusal] for the message line. */
    fun withProbeError(refusal: ProbeRefusal): MeasurementSession = copy(lastProbeError = refusal)

    /** Clears the slot [index] (a long press). An empty set also clears the frame snapshot. */
    fun clearSlot(index: Int): MeasurementSession {
        if (slots[index] == null) return this
        val next = copy(
            slots = slots.replaced(index, null),
            frameChangeDegrees = frameChangeDegrees.replaced(index, null),
            loggedSequence = null,
        )
        return if (next.isEmpty) next.copy(frameAtStart = null) else next
    }

    /** Clears all points and the note ("Start this measurement over"). */
    fun reset(): MeasurementSession = MeasurementSession(verb = verb, toleranceUm = toleranceUm)

    /** Sets the note of the user. A changed note resets the logged state. */
    fun withNote(text: String): MeasurementSession =
        if (text == note) this else copy(note = text, loggedSequence = null)

    /** Marks the set as logged with the log sequence [sequence]. */
    fun markLogged(sequence: Int): MeasurementSession = copy(loggedSequence = sequence)

    /** Gives the system notes of the set: the union of the point notes, in slot order. */
    fun systemNotes(): List<String> = slots.filterNotNull().flatMap { point -> point.notes }.distinct()

    /**
     * Gives the message line, or null when the line is hidden:
     *
     * 1. A set that is not complete: the last probe error, else the step text of the armed slot.
     * 2. A complete set: the failure of the function, if there is one.
     * 3. Else the notes of the points. [PointNote.FrameChanged] has the largest angle change.
     * 4. Else no message.
     */
    fun message(): MeasurementMessage? {
        val armed = armedSlot
        if (armed != null) {
            val error = lastProbeError
            return if (error != null) MeasurementMessage.ProbeError(error) else MeasurementMessage.Step(verb.steps[armed])
        }
        val failure = evaluate().failure
        if (failure != null) return MeasurementMessage.Failure(failure)
        val notes = systemNotes().mapNotNull { wire -> PointNote.ofWire(wire) }
        if (notes.isEmpty()) return null
        val angle = frameChangeDegrees.filterNotNull().maxOrNull()
        return MeasurementMessage.Notes(
            notes.map { note -> NoteMessage(note, if (note == PointNote.FrameChanged) angle else null) },
        )
    }

    private fun MutableList<String>.addNote(note: PointNote) {
        if (note.wire !in this) add(note.wire)
    }

    private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { list -> list[index] = value }

    companion object {
        /** Opens [verb] with empty slots and the fit tolerance [toleranceUm]. */
        fun open(verb: MeasurementVerb, toleranceUm: Double): MeasurementSession =
            MeasurementSession(verb = verb, toleranceUm = toleranceUm)
    }
}
