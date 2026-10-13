package org.superdro.core.ui.functions.importing

import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.import_action_add
import org.superdro.core.resources.import_action_already_here
import org.superdro.core.resources.import_action_not_in_file
import org.superdro.core.resources.import_action_skip
import org.superdro.core.resources.import_action_unchanged
import org.superdro.core.resources.import_action_update
import org.superdro.core.resources.import_op_add_as_new
import org.superdro.core.resources.import_op_add_new_only
import org.superdro.core.resources.import_op_merge_matching
import org.superdro.core.resources.import_op_replace_all
import org.superdro.core.resources.import_op_update_matching
import org.superdro.core.resources.import_row_problem
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.ImportTexts
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.Report
import org.superdro.core.store.files.RowAction
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The texts of the import preview and the report. */
object ImportUiTexts {

    /** The text of an import message of `core:store` (the strings of `strings_files.xml`). */
    fun message(message: ImportMessage): UiText = message.key.toUiText(*message.args.toTypedArray())

    /** The text of a problem: "Row <n>: <message>", or the message alone for a problem of the full file. */
    fun problem(problem: ImportProblem): UiText {
        val text = message(problem.message)
        val row = problem.row ?: return text
        return UiText.of(Res.string.import_row_problem, MessageKey.Row.toUiText(row), text)
    }

    /** The text of the action of a preview row. */
    fun action(action: RowAction): StringResource = when (action) {
        RowAction.Add -> Res.string.import_action_add
        RowAction.Update -> Res.string.import_action_update
        RowAction.Unchanged -> Res.string.import_action_unchanged
        RowAction.AlreadyHere -> Res.string.import_action_already_here
        RowAction.NotInFile -> Res.string.import_action_not_in_file
        RowAction.Skip -> Res.string.import_action_skip
    }

    /** The text of an import operation. */
    fun operation(operation: ImportOperation): StringResource = when (operation) {
        ImportOperation.UpdateMatching -> Res.string.import_op_update_matching
        ImportOperation.AddAsNew -> Res.string.import_op_add_as_new
        ImportOperation.AddNewOnly -> Res.string.import_op_add_new_only
        ImportOperation.MergeMatching -> Res.string.import_op_merge_matching
        ImportOperation.ReplaceAll -> Res.string.import_op_replace_all
    }

    /**
     * Gives the [Report] of [report] in the language of the system. The store
     * renders the report with an [ImportTexts]. The first pass collects the messages of the report.
     * Then the function loads the text of each message from the string resources, and the second
     * pass uses these texts.
     */
    suspend fun render(
        report: ImportReport,
        environment: ResourceEnvironment?,
        title: ImportMessage = ImportMessage(MessageKey.ReportTitle),
    ): Report {
        val asked = LinkedHashSet<ImportMessage>()
        report.render(ImportTexts { message -> asked += message; ImportTexts.English.text(message) }, title)
        val texts = HashMap<ImportMessage, String>()
        for (message in asked) texts[message] = message(message).loadString(environment)
        return report.render(ImportTexts { message -> texts[message] ?: ImportTexts.English.text(message) }, title)
    }
}
