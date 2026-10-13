package org.superdro.core.ui.adapter.encoder.map

import org.superdro.core.functions.errormap.MapImportProblem
import org.superdro.core.functions.errormap.MapProblem
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_map_problem_backlash_exceeds
import org.superdro.core.resources.encoder_map_problem_dead_band
import org.superdro.core.resources.encoder_map_problem_duplicate
import org.superdro.core.resources.encoder_map_problem_negative
import org.superdro.core.resources.encoder_map_problem_reversal
import org.superdro.core.resources.encoder_map_import_binary
import org.superdro.core.resources.encoder_map_import_choose_csv
import org.superdro.core.resources.encoder_map_import_decimal
import org.superdro.core.resources.encoder_map_import_malformed
import org.superdro.core.resources.encoder_map_import_no_points
import org.superdro.core.resources.encoder_map_import_not_csv
import org.superdro.core.resources.encoder_map_import_too_many
import org.superdro.core.ui.text.UiText

/** The texts of the map problems of `core:functions`. */
object MapEditorTexts {

    /** The text of one problem of the map validators. */
    fun problem(problem: MapProblem): UiText = when (problem) {
        is MapProblem.DuplicatePosition -> UiText.of(Res.string.encoder_map_problem_duplicate, problem.position)
        is MapProblem.Reversal -> UiText.of(Res.string.encoder_map_problem_reversal, problem.firstPosition, problem.secondPosition)
        is MapProblem.DeadBand -> UiText.of(Res.string.encoder_map_problem_dead_band, problem.firstPosition, problem.secondPosition)
        is MapProblem.NegativeBacklash -> UiText.of(Res.string.encoder_map_problem_negative, problem.position, problem.backlash)
        is MapProblem.BacklashExceedsScrewRev ->
            UiText.of(Res.string.encoder_map_problem_backlash_exceeds, problem.position, problem.backlash, problem.countsPerScrewRev)
    }

    /** The text of one problem of a map import. */
    fun importProblem(problem: MapImportProblem): UiText = when (problem) {
        MapImportProblem.ChooseCsvFile -> UiText.of(Res.string.encoder_map_import_choose_csv)
        MapImportProblem.LooksBinary -> UiText.of(Res.string.encoder_map_import_binary)
        MapImportProblem.TooManyPoints -> UiText.of(Res.string.encoder_map_import_too_many, MAX_POINTS)
        MapImportProblem.NoValidPoints -> UiText.of(Res.string.encoder_map_import_no_points)
        MapImportProblem.NotCsv -> UiText.of(Res.string.encoder_map_import_not_csv)
        is MapImportProblem.MalformedLine -> UiText.of(Res.string.encoder_map_import_malformed, problem.line)
        MapImportProblem.DecimalValues -> UiText.of(Res.string.encoder_map_import_decimal)
        is MapImportProblem.Invalid -> problem(problem.problem)
    }

    private const val MAX_POINTS = 20_000
}
