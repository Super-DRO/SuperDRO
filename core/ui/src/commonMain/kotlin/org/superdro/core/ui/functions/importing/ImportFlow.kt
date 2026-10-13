package org.superdro.core.ui.functions.importing

import org.superdro.core.store.files.Report
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.navigation.ImportPreviewRoute
import org.superdro.core.ui.navigation.ReportRoute
import org.superdro.core.ui.navigation.TabEntryScope

/** The kinds of the file imports of the functions area. */
enum class ImportKind {
    /** A tool CSV file of SuperDRO or of TouchDRO 3.25.03 and 3.32.00. */
    ToolCsv,

    /** A sub-datum CSV file of SuperDRO or of TouchDRO 3.25.03 and 3.32.00. */
    SubDatumCsv,

    /** A SuperDRO profile file or a TouchDRO `.tdset` file. */
    Profile,
}

/** Gives the import flow of the tab of this scope. */
fun TabEntryScope.importFlow(services: FunctionServices): ImportFlowPresenter =
    tabPresenter(ImportFlowPresenter.NAME) { ImportFlowPresenter(deviceId.value, services) }

/** Gives the report of the tab of this scope. */
fun TabEntryScope.reportHolder(services: FunctionServices): ReportPresenter =
    tabPresenter(ReportPresenter.NAME) { ReportPresenter(services) }

/**
 * Starts the import of [kind] in the tab of this scope: the file picker,
 * then the import preview screen ([ImportPreviewRoute]) with the three lists (rows to
 * import, rows skipped, and warnings). The import writes only after the user confirms the
 * preview. The preview screen shows a progress while the file picker is open, and closes when
 * the user cancels the file picker.
 */
fun TabEntryScope.startImport(services: FunctionServices, kind: ImportKind) {
    importFlow(services).start(kind)
    navigator.push(ImportPreviewRoute)
}

/**
 * Shows [report] on the report screen of the tab: the title, the summary, the sections,
 * and the text export through `FilePicker.share`. The import and the error map report
 * use it.
 *
 * @param kind The kind part of the name of the text file, for example `import-report`.
 * @param name The name part of the text file, for example the profile name.
 */
fun TabEntryScope.showReport(
    services: FunctionServices,
    report: Report,
    kind: String = Report.IMPORT_REPORT_KIND,
    name: String = "",
) {
    reportHolder(services).show(ReportRequest(report, kind, name))
    navigator.push(ReportRoute)
}
