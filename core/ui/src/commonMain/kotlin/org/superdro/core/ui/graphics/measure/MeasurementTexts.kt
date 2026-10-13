package org.superdro.core.ui.graphics.measure

import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.functions.measurement.AddAction
import org.superdro.core.functions.measurement.MeasuredSubDatumName
import org.superdro.core.functions.measurement.MeasurementFailure
import org.superdro.core.functions.measurement.MeasurementFormat
import org.superdro.core.functions.measurement.MeasurementMessage
import org.superdro.core.functions.measurement.MeasurementStep
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.NoteMessage
import org.superdro.core.functions.measurement.PointNote
import org.superdro.core.functions.measurement.ProbeRefusal
import org.superdro.core.functions.measurement.ResultKey
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_measure_angle_lines
import org.superdro.core.resources.ic_measure_circle
import org.superdro.core.resources.ic_measure_distance
import org.superdro.core.resources.ic_measure_line_angle
import org.superdro.core.resources.ic_measure_point
import org.superdro.core.resources.measure_add_center
import org.superdro.core.resources.measure_add_circle
import org.superdro.core.resources.measure_add_corner
import org.superdro.core.resources.measure_add_midpoint
import org.superdro.core.resources.measure_add_position
import org.superdro.core.resources.measure_failure_collinear
import org.superdro.core.resources.measure_failure_line_no_normal
import org.superdro.core.resources.measure_failure_line_two_normals
import org.superdro.core.resources.measure_failure_tangent
import org.superdro.core.resources.measure_failure_tip_no_candidate
import org.superdro.core.resources.measure_failure_tip_two_candidates
import org.superdro.core.resources.measure_failure_too_close
import org.superdro.core.resources.measure_help_angle_lines_body
import org.superdro.core.resources.measure_help_circle3_body
import org.superdro.core.resources.measure_help_distance_body
import org.superdro.core.resources.measure_help_line_angle_body
import org.superdro.core.resources.measure_help_point_body
import org.superdro.core.resources.measure_name_corner
import org.superdro.core.resources.measure_name_fitted_center
import org.superdro.core.resources.measure_name_measured_point
import org.superdro.core.resources.measure_name_midpoint
import org.superdro.core.resources.measure_note_frame_changed
import org.superdro.core.resources.measure_note_uncompensated
import org.superdro.core.resources.measure_notes_join
import org.superdro.core.resources.measure_probe_no_approach
import org.superdro.core.resources.measure_probe_position_unknown
import org.superdro.core.resources.measure_result_angle
import org.superdro.core.resources.measure_result_angle_to_x
import org.superdro.core.resources.measure_result_angle_to_y
import org.superdro.core.resources.measure_result_center
import org.superdro.core.resources.measure_result_circle
import org.superdro.core.resources.measure_result_corner
import org.superdro.core.resources.measure_result_distance
import org.superdro.core.resources.measure_result_dx
import org.superdro.core.resources.measure_result_dy
import org.superdro.core.resources.measure_result_midpoint
import org.superdro.core.resources.measure_result_position
import org.superdro.core.resources.measure_result_supplement
import org.superdro.core.resources.measure_step_first
import org.superdro.core.resources.measure_step_first_line_point1
import org.superdro.core.resources.measure_step_first_line_point2
import org.superdro.core.resources.measure_step_line_point1
import org.superdro.core.resources.measure_step_line_point2
import org.superdro.core.resources.measure_step_on_circle
import org.superdro.core.resources.measure_step_point
import org.superdro.core.resources.measure_step_second
import org.superdro.core.resources.measure_step_second_apart
import org.superdro.core.resources.measure_step_second_line_point1
import org.superdro.core.resources.measure_step_second_line_point2
import org.superdro.core.resources.measure_step_third
import org.superdro.core.resources.measure_verb_angle_lines
import org.superdro.core.resources.measure_verb_circle
import org.superdro.core.resources.measure_verb_distance
import org.superdro.core.resources.measure_verb_line_angle
import org.superdro.core.resources.measure_verb_point
import org.superdro.core.ui.text.UiText

/**
 * The texts and the icons of the measurement mode. The
 * strings are in `strings_measure.xml` of `core:resources`.
 */
object MeasurementTexts {

    /** The title of [verb]. */
    fun verbTitle(verb: MeasurementVerb): StringResource = when (verb) {
        MeasurementVerb.Point -> Res.string.measure_verb_point
        MeasurementVerb.Distance -> Res.string.measure_verb_distance
        MeasurementVerb.Circle -> Res.string.measure_verb_circle
        MeasurementVerb.LineAngle -> Res.string.measure_verb_line_angle
        MeasurementVerb.AngleBetweenLines -> Res.string.measure_verb_angle_lines
    }

    /** The icon of [verb]. */
    fun verbIcon(verb: MeasurementVerb): DrawableResource = when (verb) {
        MeasurementVerb.Point -> Res.drawable.ic_measure_point
        MeasurementVerb.Distance -> Res.drawable.ic_measure_distance
        MeasurementVerb.Circle -> Res.drawable.ic_measure_circle
        MeasurementVerb.LineAngle -> Res.drawable.ic_measure_line_angle
        MeasurementVerb.AngleBetweenLines -> Res.drawable.ic_measure_angle_lines
    }

    /** The help text of [verb]. */
    fun help(verb: MeasurementVerb): StringResource = when (verb) {
        MeasurementVerb.Point -> Res.string.measure_help_point_body
        MeasurementVerb.Distance -> Res.string.measure_help_distance_body
        MeasurementVerb.Circle -> Res.string.measure_help_circle3_body
        MeasurementVerb.LineAngle -> Res.string.measure_help_line_angle_body
        MeasurementVerb.AngleBetweenLines -> Res.string.measure_help_angle_lines_body
    }

    /** The step text of [step]. */
    fun step(step: MeasurementStep): StringResource = when (step) {
        MeasurementStep.LocateThePoint -> Res.string.measure_step_point
        MeasurementStep.LocateTheFirstPoint -> Res.string.measure_step_first
        MeasurementStep.LocateTheSecondPoint -> Res.string.measure_step_second
        MeasurementStep.LocateAPointOnTheCircle -> Res.string.measure_step_on_circle
        MeasurementStep.LocateASecondPointWellApart -> Res.string.measure_step_second_apart
        MeasurementStep.LocateTheThirdPoint -> Res.string.measure_step_third
        MeasurementStep.LocatePoint1OnTheLine -> Res.string.measure_step_line_point1
        MeasurementStep.LocatePoint2OnTheLine -> Res.string.measure_step_line_point2
        MeasurementStep.LocatePoint1OnTheFirstLine -> Res.string.measure_step_first_line_point1
        MeasurementStep.LocatePoint2OnTheFirstLine -> Res.string.measure_step_first_line_point2
        MeasurementStep.LocatePoint1OnTheSecondLine -> Res.string.measure_step_second_line_point1
        MeasurementStep.LocatePoint2OnTheSecondLine -> Res.string.measure_step_second_line_point2
    }

    /** The text of a probe refusal. */
    fun refusal(refusal: ProbeRefusal): StringResource = when (refusal) {
        ProbeRefusal.PositionUnknown -> Res.string.measure_probe_position_unknown
        ProbeRefusal.NoApproach -> Res.string.measure_probe_no_approach
    }

    /** The text of a failure. Each failure has its own text. */
    fun failure(failure: MeasurementFailure): StringResource = when (failure) {
        MeasurementFailure.Collinear -> Res.string.measure_failure_collinear
        MeasurementFailure.TipFitNoCandidate -> Res.string.measure_failure_tip_no_candidate
        MeasurementFailure.TipFitTwoCandidates -> Res.string.measure_failure_tip_two_candidates
        MeasurementFailure.PointsTooClose -> Res.string.measure_failure_too_close
        MeasurementFailure.TangentImpossible -> Res.string.measure_failure_tangent
        MeasurementFailure.LineTipNoNormal -> Res.string.measure_failure_line_no_normal
        MeasurementFailure.LineTipTwoNormals -> Res.string.measure_failure_line_two_normals
    }

    /** The label of the result [key]. */
    fun resultLabel(key: ResultKey): StringResource = when (key) {
        ResultKey.Diameter -> Res.string.measure_result_circle
        ResultKey.Center -> Res.string.measure_result_center
        ResultKey.Distance -> Res.string.measure_result_distance
        ResultKey.Dx -> Res.string.measure_result_dx
        ResultKey.Dy -> Res.string.measure_result_dy
        ResultKey.Midpoint -> Res.string.measure_result_midpoint
        ResultKey.IncludedAngle -> Res.string.measure_result_angle
        ResultKey.Supplement -> Res.string.measure_result_supplement
        ResultKey.Corner -> Res.string.measure_result_corner
        ResultKey.AngleToX -> Res.string.measure_result_angle_to_x
        ResultKey.AngleToY -> Res.string.measure_result_angle_to_y
        ResultKey.Position -> Res.string.measure_result_position
    }

    /** The text of the add button of [action]. */
    fun addLabel(action: AddAction): StringResource = when (action) {
        is AddAction.AddSubDatum -> when (action.name) {
            MeasuredSubDatumName.MeasuredPoint -> Res.string.measure_add_position
            MeasuredSubDatumName.Midpoint -> Res.string.measure_add_midpoint
            MeasuredSubDatumName.FittedCenter -> Res.string.measure_add_center
            MeasuredSubDatumName.Corner -> Res.string.measure_add_corner
        }
        AddAction.AddCircleToDrawing -> Res.string.measure_add_circle
    }

    /** The name of a sub-datum of an add action, or null for the circle. */
    fun subDatumName(action: AddAction): StringResource? = when (action) {
        is AddAction.AddSubDatum -> when (action.name) {
            MeasuredSubDatumName.MeasuredPoint -> Res.string.measure_name_measured_point
            MeasuredSubDatumName.Midpoint -> Res.string.measure_name_midpoint
            MeasuredSubDatumName.FittedCenter -> Res.string.measure_name_fitted_center
            MeasuredSubDatumName.Corner -> Res.string.measure_name_corner
        }
        AddAction.AddCircleToDrawing -> null
    }

    /**
     * The text of the message line. Two notes have two spaces between
     * them. `FRAME_CHANGED` shows the angle change with the format `"%.3f°"`.
     */
    fun message(message: MeasurementMessage): UiText = when (message) {
        is MeasurementMessage.Step -> UiText.of(step(message.step))
        is MeasurementMessage.ProbeError -> UiText.of(refusal(message.refusal))
        is MeasurementMessage.Failure -> UiText.of(failure(message.failure))
        is MeasurementMessage.Notes -> message.notes.map(::note).reduce { first, second -> UiText.of(Res.string.measure_notes_join, first, second) }
    }

    /** The text of one point note. */
    fun note(note: NoteMessage): UiText = when (note.note) {
        PointNote.Uncompensated -> UiText.of(Res.string.measure_note_uncompensated)
        PointNote.FrameChanged -> UiText.of(Res.string.measure_note_frame_changed, angleChangeText(note.angleChangeDegrees ?: 0.0))
    }

    /** Gives the angle change in degrees with 3 decimals and the sign "°", for example "0.010°". */
    fun angleChangeText(degrees: Double): String = MeasurementFormat.fixed(degrees, 3) + "°"
}
